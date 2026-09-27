#!/usr/bin/env python3
"""Standalone Fabric test servers for CI (.github/workflows/build.yml).

Each test job assembles a server from the jars the build job made, so no Gradle or compiling is needed there:

  setup     Fabric server launcher, Fabric API, Polymer, the GOML and test mod jars, and optional extra mods or Geyser
  gametest  runs GOML's game tests (src/gametest) on it and turns the JUnit report into the job summary
  boot      starts it normally, waits until it's up (and for expected log lines), checks the log and stops it
  bedrock   starts it with Geyser and runs the Bedrock bot (.github/bedrock-test/bot.mjs) against it

Only the Python standard library is used. Downloads are cached in ~/.cache/goml-test-server by URL (only immutable
URLs are used) and checked against the hashes Modrinth / GeyserMC publish.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path(os.environ.get("GOML_TEST_CACHE", Path.home() / ".cache" / "goml-test-server"))
USER_AGENT = "pokeyt59/get-off-my-lawn-reserved CI test server (github.com/pokeyt59/get-off-my-lawn-reserved)"
FABRIC_META = "https://meta.fabricmc.net/v2"
MODRINTH = "https://api.modrinth.com/v2"
GEYSER_DOWNLOADS = "https://download.geysermc.org/v2/projects"

# Stack frames and log lines that mean GOML itself broke (test code and assertion failures are reported by the tests)
MIXIN_ERRORS = re.compile(r"Mixin apply.*failed|InvalidInjectionException|MixinApplyError|MixinTransformerError")
GOML_FRAME = re.compile(r"^\s*at [^\s]*(draylar\.goml|goml\$)")
TEST_FRAME = re.compile(r"^\s*at [^\s]*(draylar\.goml\.test\.|draylar\.goml\.other\.ClaimMessagesTests)")
GOML_ERROR_LINE = re.compile(r"/(ERROR|FATAL)\]:? \(goml\)")


def log(message):
    print(message, flush=True)


def summary(markdown):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as file:
            file.write(markdown + "\n")
    else:
        log(markdown)


def request(url, accept="application/json"):
    return urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": accept})


def get_json(url, missing_ok=True):
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request(url), timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code in (400, 404) and missing_ok:
                return None
            if attempt == 3 or error.code < 500 and error.code != 429:
                raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 3:
                raise
        time.sleep(2 ** (attempt + 1))
    return None


def download(url, target, hashes=None):
    """Downloads url to target through the cache. hashes: {"sha1"|"sha256"|"sha512": hex}"""
    hashes = {name: value for name, value in (hashes or {}).items() if value}
    cached = CACHE / "downloads" / hashlib.sha256(url.encode()).hexdigest()

    def valid(path):
        for name, expected in hashes.items():
            digest = hashlib.new(name)
            with open(path, "rb") as file:
                for chunk in iter(lambda: file.read(1 << 20), b""):
                    digest.update(chunk)
            if digest.hexdigest() != expected.lower():
                return False
        return True

    if not cached.exists() or not valid(cached):
        cached.parent.mkdir(parents=True, exist_ok=True)
        partial = cached.with_suffix(".part")
        for attempt in range(4):
            try:
                with urllib.request.urlopen(request(url, "*/*"), timeout=300) as response, open(partial, "wb") as file:
                    shutil.copyfileobj(response, file)
                break
            except (urllib.error.URLError, TimeoutError):
                if attempt == 3:
                    raise
                time.sleep(2 ** (attempt + 1))
        if not valid(partial):
            partial.unlink()
            raise RuntimeError(f"{url} doesn't match its published hash")
        partial.replace(cached)

    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cached, target)


def gradle_properties():
    properties = {}
    for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    return properties


def normalize(name):
    return re.sub(r"[^a-z0-9]", "", name.lower())


class Mods:
    """Installs mods from Modrinth with their required dependencies, and remembers what it did for the summary."""

    def __init__(self, directory, minecraft):
        self.directory = directory
        self.minecraft = minecraft
        self.installed = {}  # project id -> row
        self.rows = []
        self.skipped = []

    def project(self, slug, name=None):
        project = get_json(f"{MODRINTH}/project/{urllib.parse.quote(slug)}")
        if project is None and name:
            facets = json.dumps([["project_type:mod"]])
            query = urllib.parse.urlencode({"query": name, "facets": facets, "limit": 10})
            hits = (get_json(f"{MODRINTH}/search?{query}") or {}).get("hits", [])
            for hit in hits:
                if normalize(hit["title"]) == normalize(name):
                    project = get_json(f"{MODRINTH}/project/{hit['project_id']}")
                    break
        return project

    def version(self, project_id):
        query = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([self.minecraft])})
        versions = get_json(f"{MODRINTH}/project/{project_id}/version?{query}") or []
        # Newest release if there is one, otherwise the newest beta / alpha (like a server owner would pick)
        for kind in ("release", "beta", "alpha"):
            for version in versions:
                if version["version_type"] == kind:
                    return version
        return None

    def install(self, slug, name=None, required=True, reason="requested"):
        project = self.project(slug, name)
        if project is None:
            return self.skip(slug, name, "not on Modrinth", required)
        if project["id"] in self.installed:
            return True
        version = self.version(project["id"])
        if version is None:
            return self.skip(slug, project["title"], f"no Fabric build for {self.minecraft}", required)

        files = version["files"]
        file = next((f for f in files if f.get("primary")), files[0])
        download(file["url"], self.directory / file["filename"], {"sha512": file["hashes"].get("sha512"), "sha1": file["hashes"].get("sha1")})
        self.installed[project["id"]] = True
        self.rows.append((project["title"], version["version_number"], version["version_type"], reason))
        log(f"  + {project['title']} {version['version_number']} ({reason})")

        for dependency in version.get("dependencies", []):
            if dependency.get("dependency_type") == "required" and dependency.get("project_id") and dependency["project_id"] not in self.installed:
                self.install(dependency["project_id"], required=required, reason=f"needed by {project['title']}")
        return True

    def skip(self, slug, name, why, required):
        if required:
            raise RuntimeError(f"Can't install {name or slug}: {why}")
        self.skipped.append((name or slug, why))
        log(f"  - skipped {name or slug}: {why}")
        return False

    def add_file(self, path, title, reason):
        shutil.copyfile(path, self.directory / path.name)
        self.rows.append((title, path.name, "", reason))
        log(f"  + {path.name} ({reason})")


def geyser_download(project):
    """Newest Geyser / Floodgate build for Fabric from GeyserMC, None when there isn't one."""
    build = get_json(f"{GEYSER_DOWNLOADS}/{project}/versions/latest/builds/latest")
    if not build or "fabric" not in build.get("downloads", {}):
        return None
    file = build["downloads"]["fabric"]
    url = f"{GEYSER_DOWNLOADS}/{project}/versions/{build['version']}/builds/{build['build']}/downloads/fabric"
    return url, file["name"], file.get("sha256"), f"{build['version']} build {build['build']}"


SERVER_PROPERTIES = """\
online-mode=false
enforce-secure-profile=false
spawn-protection=0
view-distance=4
simulation-distance=4
max-tick-time=-1
level-type=minecraft\\:flat
generator-settings={"biome"\\:"minecraft\\:plains","layers"\\:[{"block"\\:"minecraft\\:bedrock","height"\\:1},{"block"\\:"minecraft\\:dirt","height"\\:2},{"block"\\:"minecraft\\:grass_block","height"\\:1}]}
generate-structures=false
spawn-monsters=false
server-port=25565
motd=GOML test server
"""

GEYSER_CONFIG = """\
# Written by .github/scripts/test_server.py: offline Bedrock logins, only for the CI test server
config-version: 8
java:
  auth-type: offline
advanced:
  bedrock:
    validate-bedrock-login: false
"""


def setup(args):
    properties = gradle_properties()
    minecraft = properties["minecraft_version"]
    server = Path(args.dir)
    (server / "SKIPPED").unlink(missing_ok=True)
    mods_dir = server / "mods"
    if mods_dir.exists():
        shutil.rmtree(mods_dir)
    mods_dir.mkdir(parents=True)

    loaders = get_json(f"{FABRIC_META}/versions/loader", missing_ok=False)
    installers = get_json(f"{FABRIC_META}/versions/installer", missing_ok=False)
    loader = next(entry["version"] for entry in loaders if entry.get("stable"))
    installer = next(entry["version"] for entry in installers if entry.get("stable"))
    log(f"Minecraft {minecraft}, Fabric Loader {loader}, installer {installer}")
    download(f"{FABRIC_META}/versions/loader/{minecraft}/{loader}/{installer}/server/jar", server / "fabric-server-launch.jar")

    mods = Mods(mods_dir, minecraft)
    mods.rows.append(("Fabric Loader", loader, "", "server"))
    for slug in ("fabric-api", "polymer"):
        mods.install(slug, reason="GOML dependency")
    mods.add_file(Path(args.goml), "Get Off My Lawn ReServed", "built by this run")
    if args.test_jar:
        mods.add_file(Path(args.test_jar), "GOML tests", "built by this run")

    if args.mods_file:
        for line in Path(args.mods_file).read_text(encoding="utf-8").splitlines():
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            slug, _, name = (part.strip() for part in line.partition("|"))
            mods.install(slug, name or None, required=False, reason="compat test")

    for project in ("geyser", "floodgate"):
        if not getattr(args, project):
            continue
        found = geyser_download(project)
        if found:
            url, filename, sha256, version = found
            download(url, mods_dir / filename, {"sha256": sha256})
            mods.rows.append((project.capitalize(), version, "", "GeyserMC downloads"))
            log(f"  + {filename}")
        elif not mods.install(project, project.capitalize(), required=False, reason="Bedrock test"):
            if project == "geyser":
                raise RuntimeError(f"No Geyser build for Fabric {minecraft}")
            # Floodgate often lags behind new Minecraft versions, that shouldn't block the tests
            reason = f"Floodgate has no Fabric build for {minecraft} yet"
            (server / "SKIPPED").write_text(reason)
            summary(f"### {args.title}: skipped, {reason}\n")
            log(f"::warning::{reason}, skipping the Floodgate check")
            return

    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(SERVER_PROPERTIES)
    config = {"checkForUpdates": False, **json.loads(args.goml_config)}
    (server / "config").mkdir(exist_ok=True)
    (server / "config" / "getoffmylawn.json").write_text(json.dumps(config, indent=2))
    if args.geyser:
        # Geyser-Fabric keeps its config in config/Geyser-Fabric
        (server / "config" / "Geyser-Fabric").mkdir(parents=True, exist_ok=True)
        (server / "config" / "Geyser-Fabric" / "config.yml").write_text(GEYSER_CONFIG)

    table = ["| Mod | Version | | Why |", "|---|---|---|---|"]
    table += [f"| {title} | {version} | {kind} | {reason} |" for title, version, kind, reason in mods.rows]
    summary(f"### {args.title}: {len(mods.rows)} mods\n\n<details><summary>Mod list</summary>\n\n" + "\n".join(table) + "\n\n</details>\n")
    if mods.skipped:
        summary("Skipped (not available for this Minecraft version): " + ", ".join(f"{name} ({why})" for name, why in mods.skipped) + "\n")


class Server:
    def __init__(self, directory, jvm_args=(), log_name="server.log"):
        self.directory = Path(directory)
        self.jvm_args = list(jvm_args)
        self.log_path = self.directory / log_name
        self.process = None

    def start(self):
        command = ["java", "-Xms1G", "-Xmx3G", *self.jvm_args, "-jar", "fabric-server-launch.jar", "nogui"]
        log("$ " + " ".join(command))
        self.log_file = open(self.log_path, "w", encoding="utf-8")
        self.process = subprocess.Popen(command, cwd=self.directory, stdin=subprocess.PIPE, stdout=self.log_file,
                                        stderr=subprocess.STDOUT, text=True)

    def text(self):
        return self.log_path.read_text(encoding="utf-8", errors="replace") if self.log_path.exists() else ""

    def running(self):
        return self.process.poll() is None

    def wait_for(self, pattern, timeout):
        regex = re.compile(pattern, re.M)
        deadline = time.time() + timeout
        while time.time() < deadline:
            match = regex.search(self.text())
            if match:
                return match
            if not self.running():
                return regex.search(self.text())
            time.sleep(0.5)
        return None

    def command(self, line):
        if self.running():
            self.process.stdin.write(line + "\n")
            self.process.stdin.flush()

    def wait_exit(self, timeout):
        try:
            return self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            log(f"::error::Server didn't finish within {timeout} seconds, killing it")
            self.process.kill()
            self.process.wait()
            return None

    def stop(self, timeout=90):
        self.command("stop")
        return self.wait_exit(timeout)

    def goml_errors(self):
        errors = []
        lines = self.text().splitlines()
        for number, line in enumerate(lines, 1):
            if MIXIN_ERRORS.search(line) or GOML_ERROR_LINE.search(line) or (GOML_FRAME.search(line) and not TEST_FRAME.search(line)):
                errors.append(f"{number}: {line.strip()}")
        return errors


def fail(message, server=None):
    log(f"::error::{message}")
    if server:
        log("----- last 150 log lines -----")
        log("\n".join(server.text().splitlines()[-150:]))
    sys.exit(1)


def report_errors(server, title):
    errors = server.goml_errors()
    if errors:
        summary(f"**{title}: GOML errors in the server log**\n\n```\n" + "\n".join(errors[:40]) + "\n```\n")
        fail(f"{len(errors)} GOML error lines in the server log, first: {errors[0]}", server)


def gametest(args):
    server_dir = Path(args.dir)
    report = server_dir / "gametest-report.xml"
    report.unlink(missing_ok=True)
    jvm = ["-Dfabric-api.gametest", f"-Dfabric-api.gametest.report-file={report.resolve()}"]
    if args.filter:
        jvm.append(f"-Dfabric-api.gametest.filter={args.filter}")
    server = Server(server_dir, jvm)
    started = time.time()
    server.start()
    status = server.wait_exit(args.timeout)
    took = time.time() - started

    cases = []
    if report.exists():
        for case in ElementTree.parse(report).getroot().iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            skipped = case.find("skipped") is not None
            state = "failed" if problem is not None else "skipped" if skipped else "passed"
            detail = (problem.get("message") or problem.text or "").strip() if problem is not None else ""
            cases.append((case.get("name", "?"), state, float(case.get("time") or 0), detail))

    passed = sum(1 for case in cases if case[1] == "passed")
    failed = [case for case in cases if case[1] == "failed"]
    icon = {"passed": "✅", "failed": "❌", "skipped": "⏭️"}
    table = ["| | Test | Time | Problem |", "|---|---|---|---|"]
    for name, state, seconds, detail in sorted(cases, key=lambda case: (case[1] != "failed", case[0])):
        table.append(f"| {icon[state]} | `{name}` | {seconds:.2f}s | {detail.replace('|', '/').replace(chr(10), ' ')[:300]} |")
    summary(f"### {args.title}: {passed}/{len(cases)} game tests passed in {took:.0f}s\n\n" + "\n".join(table) + "\n")

    if not cases:
        fail("No game test results (the test server didn't start or didn't find the tests)", server)
    if failed:
        for name, _, _, detail in failed:
            log(f"::error title=Game test {name} failed::{detail}")
        fail(f"{len(failed)} of {len(cases)} game tests failed", server)
    if status not in (0, None) and not failed:
        log(f"::warning::The test server exited with status {status} although every test passed")
    report_errors(server, args.title)
    log(f"All {len(cases)} game tests passed")


def boot(args):
    if (Path(args.dir) / "SKIPPED").exists():
        log("Skipped: " + (Path(args.dir) / "SKIPPED").read_text())
        return
    server = Server(args.dir, log_name=args.log)
    server.start()
    if not server.wait_for(r"Done \(\d", args.timeout):
        server.stop(30)
        fail("The server didn't finish starting", server)
    missing = [pattern for pattern in args.expect if not server.wait_for(pattern, 60)]
    status = server.stop()
    if missing:
        fail("Expected log lines didn't show up: " + ", ".join(missing), server)
    if status != 0:
        fail(f"The server exited with status {status}", server)
    report_errors(server, args.title)
    summary(f"### {args.title}: started and stopped cleanly\n")
    log("Server started and stopped cleanly")


def bedrock(args):
    server = Server(args.dir, ["-Dgoml.e2e=true"])
    server.start()
    if not server.wait_for(r"Done \(\d", args.timeout):
        server.stop(30)
        fail("The server didn't finish starting", server)
    if not server.wait_for(r"Started Geyser on|geyser help", 60):
        log("::warning::Didn't see Geyser's startup message, trying anyway")
    detection = server.wait_for(r"Bedrock player detection enabled using (\S+)", 5)
    if not detection:
        server.stop(30)
        fail("GOML didn't enable Bedrock player detection with Geyser installed", server)

    bot_dir = ROOT / ".github" / "bedrock-test"
    results = Path(args.dir).resolve() / "bedrock-results.json"
    results.unlink(missing_ok=True)
    env = dict(os.environ, BEDROCK_HOST="127.0.0.1", BEDROCK_PORT="19132", RESULTS_FILE=str(results))
    try:
        bot = subprocess.run(["node", "bot.mjs"], cwd=bot_dir, env=env, timeout=args.bot_timeout)
        bot_status = bot.returncode
    except subprocess.TimeoutExpired:
        bot_status = None
        log("::error::The Bedrock bot didn't finish in time")
    server.stop()

    steps = json.loads(results.read_text()) if results.exists() else []
    table = ["| | Check | Details |", "|---|---|---|"]
    for step in steps:
        table.append(f"| {'✅' if step['ok'] else '❌'} | {step['name']} | {str(step.get('details', '')).replace('|', '/')[:300]} |")
    passed = sum(1 for step in steps if step["ok"])
    summary(f"### {args.title}: {passed}/{len(steps)} checks passed (detected via {detection.group(1)})\n\n" + "\n".join(table) + "\n")

    report_errors(server, args.title)
    if bot_status != 0 or not steps or passed != len(steps):
        fail("The Bedrock end-to-end test failed", server)
    log("Bedrock end-to-end test passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    setup_parser = commands.add_parser("setup")
    setup_parser.add_argument("--dir", required=True)
    setup_parser.add_argument("--goml", required=True, help="the GOML jar to test")
    setup_parser.add_argument("--test-jar", help="the goml-test jar")
    setup_parser.add_argument("--mods-file", help="extra Modrinth mods, see .github/test-mods.txt")
    setup_parser.add_argument("--geyser", action="store_true")
    setup_parser.add_argument("--floodgate", action="store_true")
    setup_parser.add_argument("--goml-config", default="{}", help="JSON merged into config/getoffmylawn.json")
    setup_parser.add_argument("--title", default="Test server")
    setup_parser.set_defaults(run=setup)

    gametest_parser = commands.add_parser("gametest")
    gametest_parser.add_argument("--dir", required=True)
    gametest_parser.add_argument("--filter")
    gametest_parser.add_argument("--timeout", type=int, default=900)
    gametest_parser.add_argument("--title", default="Game tests")
    gametest_parser.set_defaults(run=gametest)

    boot_parser = commands.add_parser("boot")
    boot_parser.add_argument("--dir", required=True)
    boot_parser.add_argument("--expect", action="append", default=[], help="regex that must show up in the log")
    boot_parser.add_argument("--log", default="server.log")
    boot_parser.add_argument("--timeout", type=int, default=600)
    boot_parser.add_argument("--title", default="Server boot")
    boot_parser.set_defaults(run=boot)

    bedrock_parser = commands.add_parser("bedrock")
    bedrock_parser.add_argument("--dir", required=True)
    bedrock_parser.add_argument("--timeout", type=int, default=600)
    bedrock_parser.add_argument("--bot-timeout", type=int, default=330)
    bedrock_parser.add_argument("--title", default="Bedrock (Geyser)")
    bedrock_parser.set_defaults(run=bedrock)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
