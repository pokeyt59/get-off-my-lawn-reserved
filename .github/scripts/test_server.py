#!/usr/bin/env python3
"""Standalone Fabric test servers for CI (.github/workflows/build.yml).

Each test job assembles a server from the jars the build job made, so no Gradle or compiling is needed there:

  setup     Fabric server launcher, Fabric API, Polymer, the GOML and test mod jars, and optional extra mods (at the
            versions a mods file names) or Geyser
  gametest  runs GOML's game tests (src/gametest) on it and turns the JUnit report into the job summary
  boot      starts it normally, waits until it's up (and for expected log lines), checks the log and stops it
  bedrock   starts it with Geyser and runs the Bedrock bot (.github/bedrock-test/bot.mjs) against it
  server    the full server test: a normal world with a real server's whole mod list (.github/server-mods.txt),
            claims, Chunky pre-generation, BlueMap markers, a restart and the Bedrock bot

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
            if error.code == 429:
                # Modrinth says how long until the rate limit resets
                reset = error.headers.get("X-Ratelimit-Reset", "")
                time.sleep(min(int(reset) + 1, 60) if reset.isdigit() else 2 ** (attempt + 1))
                continue
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


def similar(title, name):
    """Modrinth title vs the name in a mod list: the same, or a few letters more ("Cloth Config API" / "Cloth Config",
    "MES - Moog's End Structures" / "MoogsEndStructures"), but "FerriteCore" isn't "Ferrite"
    """
    longer, shorter = sorted((normalize(title), normalize(name)), key=len, reverse=True)
    return bool(shorter) and shorter in longer and len(longer) - len(shorter) <= 3


def primary_file(version):
    files = version["files"]
    return next((f for f in files if f.get("primary")), files[0])


def pinned_version(versions, pin, minecraft):
    """The Modrinth version matching the version a mod list shows (fabric.mod.json's, e.g. "0.25.2+mc26.2" for Lithium's
    "mc26.2-0.25.2-fabric"): the exact version number, else the version inside the version number or file name, else
    the part before "+" inside them. Numbers must not continue ("2.5" isn't "2.5.1" or "12.5"). Newest first, builds
    for this Minecraft version before others."""
    pin = pin.strip().lower()
    if not pin:
        return None

    def contains(text):
        return lambda version: any(text.search(value.lower()) for value in (version["version_number"], primary_file(version)["filename"]))

    def bounded(text):
        return re.compile(r"(?<![0-9.])" + re.escape(text) + r"(?![.]?[0-9])")

    tests = [lambda version: version["version_number"].lower() == pin, contains(bounded(pin))]
    core = pin.split("+", 1)[0]
    # Some mod lists show a build prefix ("1-v2.3.9" for "v2.3.9+mod")
    for text in dict.fromkeys((core, re.sub(r"^\d+-v", "", core))):
        if text != pin and re.search(r"[0-9]\.[0-9]", text):
            tests.append(contains(bounded(text)))
    for test in tests:
        matches = [version for version in versions if test(version)]
        if matches:
            return next((version for version in matches if minecraft in version["game_versions"]), matches[0])
    return None


class ModLine:
    """A line of a mods file: "slug | Name | version | tags". Only the slug is needed; with the name Modrinth is
    searched when the slug doesn't exist; with the version that version is used; a https:// URL instead of the slug is
    downloaded as-is."""

    def __init__(self, line):
        parts = [part.strip() for part in line.split("|")]
        self.slug = parts[0]
        self.name = parts[1] if len(parts) > 1 and parts[1] else None
        self.pin = parts[2] if len(parts) > 2 and parts[2] else None
        self.tags = set(parts[3:])
        self.url = self.slug if self.slug.startswith("https://") else None


def read_mods_file(path):
    lines = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip() if not line.lstrip().startswith("https://") else line.split(" #", 1)[0].strip()
        if line:
            lines.append(ModLine(line))
    return lines


class Mods:
    """Installs mods from Modrinth with their required dependencies, and remembers what it did for the summary."""

    def __init__(self, directory, minecraft):
        self.directory = directory
        self.minecraft = minecraft
        self.installed = {}  # project id -> version
        self.versions = {}  # project slug -> installed version number
        self.projects = {}  # slug or id -> project, from prefetch
        self.version_lists = {}  # project id -> its Fabric versions, newest first
        self.pending = []  # (project id, needed by, required) dependencies still to install
        self.rows = []
        self.skipped = []
        self.pinned = self.fallback = 0

    def prefetch(self, slugs):
        """Looks up many projects in one request instead of one each"""
        for chunk in range(0, len(slugs), 100):
            ids = json.dumps(slugs[chunk:chunk + 100], separators=(",", ":"))
            for project in get_json(f"{MODRINTH}/projects?ids={urllib.parse.quote(ids)}") or []:
                self.projects[project["id"]] = self.projects[project["slug"]] = project

    def project(self, slug):
        if slug not in self.projects:
            self.projects[slug] = get_json(f"{MODRINTH}/project/{urllib.parse.quote(slug)}")
        return self.projects[slug]

    def search(self, name):
        facets = json.dumps([["project_type:mod"]])
        hits = []
        # Mod lists show some names without spaces ("LetMeDespawn"), Modrinth's search wants the words
        for text in dict.fromkeys((name, re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name))):
            query = urllib.parse.urlencode({"query": text, "facets": facets, "limit": 10})
            hits += [hit for hit in (get_json(f"{MODRINTH}/search?{query}") or {}).get("hits", [])
                     if similar(hit["title"], name) and hit["project_id"] not in {known["project_id"] for known in hits}]
        # Exact title matches first
        hits.sort(key=lambda hit: normalize(hit["title"]) != normalize(name))
        return [self.project(hit["project_id"]) for hit in hits[:3]]

    def version_list(self, project_id):
        if project_id not in self.version_lists:
            query = urllib.parse.urlencode({"loaders": json.dumps(["fabric"])})
            self.version_lists[project_id] = get_json(f"{MODRINTH}/project/{project_id}/version?{query}") or []
        return self.version_lists[project_id]

    def newest(self, project_id):
        versions = [version for version in self.version_list(project_id) if self.minecraft in version["game_versions"]]
        # Newest release if there is one, otherwise the newest beta / alpha (like a server owner would pick)
        for kind in ("release", "beta", "alpha"):
            for version in versions:
                if version["version_type"] == kind:
                    return version
        return None

    def resolve(self, slug, name, pin):
        """(project, version, how) for a mod, the project is None when it isn't on Modrinth"""
        project = self.project(slug)
        candidates = [project] if project is not None else []
        # A wrong slug can point at another mod: then the name decides
        if name and (project is None or not similar(project["title"], name)
                     or pin and not pinned_version(self.version_list(project["id"]), pin, self.minecraft)):
            known = {candidate["id"] for candidate in candidates}
            candidates += [found for found in self.search(name) if found and found["id"] not in known]
            # Projects named like the mod before the slug's project
            candidates.sort(key=lambda candidate: not similar(candidate["title"], name))
        if pin:
            for candidate in candidates:
                version = pinned_version(self.version_list(candidate["id"]), pin, self.minecraft)
                if version:
                    return candidate, version, "your version"
        for candidate in candidates:
            version = self.newest(candidate["id"])
            if version:
                return candidate, version, f"newest, {pin} isn't on Modrinth" if pin else "newest"
        return (candidates[0] if candidates else None), None, None

    def install(self, slug, name=None, required=True, reason="requested", pin=None):
        """Installs the mod now and queues its required dependencies for install_dependencies()"""
        project, version, how = self.resolve(slug, name, pin)
        if project is None:
            return self.skip(slug, name, "not on Modrinth", required)
        if project["id"] in self.installed:
            return True
        if version is None:
            return self.skip(slug, project["title"], f"no Fabric build for {self.minecraft}", required)

        file = primary_file(version)
        download(file["url"], self.directory / file["filename"], {"sha512": file["hashes"].get("sha512"), "sha1": file["hashes"].get("sha1")})
        self.installed[project["id"]] = version
        self.versions[project["slug"]] = version["version_number"]
        if pin:
            if how == "your version":
                self.pinned += 1
            else:
                self.fallback += 1
        why = f"{reason}, {how}" if pin else reason
        self.rows.append((project["title"], version["version_number"], version["version_type"], why))
        log(f"  + {project['title']} {version['version_number']} ({why})")

        for dependency in version.get("dependencies", []):
            if dependency.get("dependency_type") == "required" and dependency.get("project_id"):
                self.pending.append((dependency["project_id"], project["title"], required))
        return True

    def install_dependencies(self):
        """After the listed mods, so a dependency that's listed too gets the listed version"""
        while self.pending:
            project_id, needed_by, required = self.pending.pop(0)
            if project_id not in self.installed:
                self.install(project_id, required=required, reason=f"needed by {needed_by}")

    def install_url(self, url, name, reason):
        filename = urllib.parse.unquote(url.rsplit("/", 1)[-1].split("?", 1)[0])
        if not filename.endswith(".jar"):
            filename += ".jar"
        download(url, self.directory / filename)
        self.rows.append((name or filename, filename, "", f"{reason}, direct link"))
        log(f"  + {filename} ({reason}, direct link)")

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


FABRIC_MAVEN = "https://maven.fabricmc.net/net/fabricmc/fabric-api"


def fabric_api_module(fabric_api_version, module):
    """Downloads info for a Fabric API module the release jar leaves out (the game test API is dev-only there),
    with the version the Fabric API POM pins for it."""
    pom_url = f"{FABRIC_MAVEN}/fabric-api/{fabric_api_version}/fabric-api-{fabric_api_version}.pom"
    with urllib.request.urlopen(request(pom_url, "*/*"), timeout=60) as response:
        pom = ElementTree.parse(response).getroot()
    namespace = {"m": pom.tag.split("}")[0].strip("{")} if pom.tag.startswith("{") else {}
    prefix = "m:" if namespace else ""
    for dependency in pom.iter(f"{{{namespace['m']}}}dependency" if namespace else "dependency"):
        artifact = dependency.find(f"{prefix}artifactId", namespace)
        version = dependency.find(f"{prefix}version", namespace)
        if artifact is not None and artifact.text == module and version is not None:
            base = f"{FABRIC_MAVEN}/{module}/{version.text}/{module}-{version.text}.jar"
            with urllib.request.urlopen(request(base + ".sha1", "*/*"), timeout=60) as response:
                sha1 = response.read().decode().split()[0]
            return base, f"{module}-{version.text}.jar", sha1, version.text
    raise RuntimeError(f"{module} isn't in the Fabric API {fabric_api_version} POM")


SERVER_PROPERTIES = """\
online-mode=false
enforce-secure-profile=false
spawn-protection=0
view-distance=4
simulation-distance=4
max-tick-time=-1
spawn-monsters=false
server-port=25565
motd=GOML test server
"""

WORLDS = {
    # Quick to create and nothing in the way of the tests
    "flat": """\
level-type=minecraft\\:flat
generator-settings={"biome"\\:"minecraft\\:plains","layers"\\:[{"block"\\:"minecraft\\:bedrock","height"\\:1},{"block"\\:"minecraft\\:dirt","height"\\:2},{"block"\\:"minecraft\\:grass_block","height"\\:1}]}
generate-structures=false
""",
    # A real world, so world generation mods run. No monsters, they'd attack the Bedrock bot.
    "normal": """\
level-seed=goml-full-server-test
generate-structures=true
difficulty=peaceful
""",
}

GEYSER_CONFIG = """\
# Written by .github/scripts/test_server.py: unauthenticated Bedrock logins, only for the CI test server
config-version: 8
java:
  auth-type: {auth}
advanced:
  bedrock:
    validate-bedrock-login: false
"""

# BlueMap only starts (and with it GOML's claim markers) once the Minecraft client download is accepted
BLUEMAP_CORE_CONFIG = """\
# Written by .github/scripts/test_server.py
accept-download: true
data: "bluemap"
render-thread-count: 1
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
    # Listed mods first, so the listed versions win over what GOML or other mods would pull in
    if args.mods_file:
        reason = Path(args.mods_file).name
        lines = read_mods_file(args.mods_file)
        mods.prefetch([line.slug for line in lines if not line.url])
        for line in lines:
            if args.gametest and "not-gametest" in line.tags:
                mods.skipped.append((line.name or line.slug, "left out of the game test server"))
            elif line.url:
                mods.install_url(line.url, line.name, reason)
            else:
                mods.install(line.slug, line.name, required=False, reason=reason, pin=line.pin)
    for slug in ("fabric-api", "polymer"):
        mods.install(slug, reason="GOML dependency")
    mods.install_dependencies()
    if args.gametest:
        # The Fabric API release jar leaves the game test runner out, dev environments get it from Maven
        url, filename, sha1, version = fabric_api_module(mods.versions["fabric-api"], "fabric-gametest-api-v1")
        download(url, mods_dir / filename, {"sha1": sha1})
        mods.rows.append(("Fabric Game Test API", version, "", "runs the game tests"))
        log(f"  + {filename}")
    mods.add_file(Path(args.goml), "Get Off My Lawn ReServed", "built by this run")
    if args.test_jar:
        mods.add_file(Path(args.test_jar), "GOML tests", "built by this run")

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
            if args.mods_file:
                # Just one of many mods on this server
                continue
            (server / "SKIPPED").write_text(reason)
            summary(f"### {args.title}: skipped, {reason}\n")
            log(f"::warning::{reason}, skipping the Floodgate check")
            return
    mods.install_dependencies()

    (server / "eula.txt").write_text("eula=true\n")
    (server / "server.properties").write_text(SERVER_PROPERTIES + WORLDS[args.world])
    config = {"checkForUpdates": False, **json.loads(args.goml_config)}
    (server / "config").mkdir(exist_ok=True)
    (server / "config" / "getoffmylawn.json").write_text(json.dumps(config, indent=2))
    if args.geyser:
        # Geyser-Fabric keeps its config in config/Geyser-Fabric
        (server / "config" / "Geyser-Fabric").mkdir(parents=True, exist_ok=True)
        (server / "config" / "Geyser-Fabric" / "config.yml").write_text(GEYSER_CONFIG.format(auth=args.geyser_auth))
    if "bluemap" in mods.versions:
        (server / "config" / "bluemap").mkdir(parents=True, exist_ok=True)
        (server / "config" / "bluemap" / "core.conf").write_text(BLUEMAP_CORE_CONFIG)
    # What the later commands can test on this server
    (server / "installed.json").write_text(json.dumps(sorted(mods.versions) + [row[0].lower() for row in mods.rows if row[3] == "GeyserMC downloads"]))

    table = ["| Mod | Version | | Why |", "|---|---|---|---|"]
    table += [f"| {title} | {version} | {kind} | {reason} |" for title, version, kind, reason in mods.rows]
    pinned = f", {mods.pinned} at your version, {mods.fallback} at the newest because yours isn't on Modrinth" if mods.pinned or mods.fallback else ""
    summary(f"### {args.title}: {len(mods.rows)} mods{pinned}\n\n<details><summary>Mod list</summary>\n\n" + "\n".join(table) + "\n\n</details>\n")
    if mods.skipped:
        summary(f"Skipped {len(mods.skipped)}: " + ", ".join(f"{name} ({why})" for name, why in mods.skipped) + "\n")


class Server:
    def __init__(self, directory, jvm_args=(), log_name="server.log", xmx="3G"):
        self.directory = Path(directory)
        self.jvm_args = list(jvm_args)
        self.log_path = self.directory / log_name
        self.xmx = xmx
        self.process = None

    def start(self):
        command = ["java", "-Xms1G", f"-Xmx{self.xmx}", *self.jvm_args, "-jar", "fabric-server-launch.jar", "nogui"]
        log("$ " + " ".join(command))
        self.log_file = open(self.log_path, "w", encoding="utf-8")
        self.process = subprocess.Popen(command, cwd=self.directory, stdin=subprocess.PIPE, stdout=self.log_file,
                                        stderr=subprocess.STDOUT, text=True)

    def text(self):
        return self.log_path.read_text(encoding="utf-8", errors="replace") if self.log_path.exists() else ""

    def running(self):
        return self.process.poll() is None

    def wait_for(self, pattern, timeout, since=0):
        """Waits for a log line matching pattern, only looking after the first `since` characters of the log"""
        regex = re.compile(pattern, re.M)
        deadline = time.time() + timeout
        while time.time() < deadline:
            match = regex.search(self.text(), since)
            if match:
                return match
            if not self.running():
                return regex.search(self.text(), since)
            time.sleep(0.5)
        return None

    def command(self, line):
        if self.running():
            self.process.stdin.write(line + "\n")
            self.process.stdin.flush()

    def step(self, name, timeout=300):
        """Runs "/gomltest <name>" (src/gametest/.../E2ECommands.java) on the console, returns (ok, answer)"""
        since = len(self.text())
        self.command(f"gomltest {name}")
        match = self.wait_for(rf"\[gomltest\] {re.escape(name)} (ok|error)(.*)$", timeout, since)
        if not match:
            return False, "no answer"
        return match.group(1) == "ok", match.group(2).strip()

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
        """(GOML errors, other mods' mixin errors): with many mods installed, a mixin error of another mod isn't GOML's"""
        errors, others = [], []
        lines = self.text().splitlines()
        for number, line in enumerate(lines, 1):
            if GOML_ERROR_LINE.search(line) or (GOML_FRAME.search(line) and not TEST_FRAME.search(line)):
                errors.append(f"{number}: {line.strip()}")
            elif MIXIN_ERRORS.search(line):
                (errors if "goml" in line.lower() else others).append(f"{self.log_path.name}:{number}: {line.strip()}")
        return errors, others


def fail(message, server=None):
    log(f"::error::{message}")
    if server:
        log("----- last 150 log lines -----")
        log("\n".join(server.text().splitlines()[-150:]))
    sys.exit(1)


def report_errors(server, title):
    errors, others = server.goml_errors()
    if others:
        log(f"::warning::{len(others)} mixin errors of other mods in {server.log_path.name}, first: {others[0]}")
        summary(f"**{title}: mixin errors of other mods** (not GOML's)\n\n```\n" + "\n".join(others[:20]) + "\n```\n")
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
    server = Server(server_dir, jvm, xmx=args.xmx)
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


def run_bot(directory, timeout):
    """Runs the Bedrock bot (.github/bedrock-test/bot.mjs) against the server, returns (exit status, its checks)"""
    bot_dir = ROOT / ".github" / "bedrock-test"
    results = Path(directory).resolve() / "bedrock-results.json"
    results.unlink(missing_ok=True)
    env = dict(os.environ, BEDROCK_HOST="127.0.0.1", BEDROCK_PORT="19132", RESULTS_FILE=str(results))
    try:
        status = subprocess.run(["node", "bot.mjs"], cwd=bot_dir, env=env, timeout=timeout).returncode
    except subprocess.TimeoutExpired:
        status = None
        log("::error::The Bedrock bot didn't finish in time")
    return status, json.loads(results.read_text()) if results.exists() else []


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

    bot_status, steps = run_bot(args.dir, args.bot_timeout)
    server.stop()

    table = ["| | Check | Details |", "|---|---|---|"]
    for step in steps:
        table.append(f"| {'✅' if step['ok'] else '❌'} | {step['name']} | {str(step.get('details', '')).replace('|', '/')[:300]} |")
    passed = sum(1 for step in steps if step["ok"])
    summary(f"### {args.title}: {passed}/{len(steps)} checks passed (detected via {detection.group(1)})\n\n" + "\n".join(table) + "\n")

    report_errors(server, args.title)
    if bot_status != 0 or not steps or passed != len(steps):
        fail("The Bedrock end-to-end test failed", server)
    log("Bedrock end-to-end test passed")


def bluemap_claim_markers(expected, timeout):
    """GOML's claim markers per BlueMap map ({map id: count}), read from BlueMap's web server until a map has
    `expected` of them or the time is up. None when the web server never answered."""
    local = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def read(path):
        with local.open(f"http://127.0.0.1:8100/{path}", timeout=10) as response:
            return json.load(response)

    counts = None
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            maps = read("settings.json").get("maps", [])
            ids = list(maps) if isinstance(maps, dict) else [entry if isinstance(entry, str) else entry.get("id") for entry in maps]
            counts = {}
            for map_id in ids:
                marker_set = read(f"maps/{urllib.parse.quote(str(map_id))}/live/markers.json").get("gomlMarkerSet") or {}
                counts[map_id] = len(marker_set.get("markers") or {})
            if expected in counts.values():
                return counts
        except (OSError, ValueError, AttributeError):
            pass
        time.sleep(5)
    return counts


def full_server(args):
    """A normal server with every mod of a real server's list: claims on generated terrain, Chunky pre-generation
    around them, BlueMap's claim markers, a restart, and the Bedrock bot through Geyser (+ Floodgate)."""
    directory = Path(args.dir)
    installed = set(json.loads((directory / "installed.json").read_text()))
    rows = []
    servers = []

    def record(state, check, details=""):
        rows.append((state, check, str(details)))
        label = {True: "PASS", False: "FAIL", "warn": "WARN"}[state]
        log(f"{label} {check}{' - ' + str(details) if details else ''}")

    def start(log_name, check):
        server = Server(directory, ["-Dgoml.e2e=true"], log_name=log_name, xmx=args.xmx)
        servers.append(server)
        started = time.time()
        server.start()
        done = server.wait_for(r"Done \(\d", args.timeout)
        record(bool(done), check, f"in {time.time() - started:.0f}s" if done else "didn't finish starting")
        if not done:
            server.stop(30)
        return server if done else None

    server = start("server.log", "Server with all mods starts")
    if server:
        ok, answer = server.step("claims", 600)
        record(ok, "Claim grid created on generated terrain", answer)
        grid = re.search(r"center=(-?\d+),(-?\d+) claims=(\d+)", answer) if ok else None

        if grid and "chunky" in installed:
            since = len(server.text())
            # Chunky Offline starts its own pre-generation around spawn while nobody is online, and Chunky runs one task
            # per world: pause it (it stops once the chunks in progress are done), then replace it with ours (Chunky
            # asks to confirm replacing a saved task)
            server.command("chunky pause")
            if server.wait_for(r"Task paused for minecraft:overworld", 10, since):
                server.wait_for(r"Task stopped for minecraft:overworld", 180, since)
            for command in ("chunky world minecraft:overworld", f"chunky center {grid.group(1)} {grid.group(2)}",
                            f"chunky radius {args.chunky_radius}", "chunky start", "chunky confirm"):
                server.command(command)
                time.sleep(2)
            done = server.wait_for(r"Task finished for minecraft:overworld.*$", args.chunky_timeout, since)
            if done:
                record(True, "Chunky pre-generates the area around the claims", done.group(0).strip()[:200])
            else:
                # What Chunky (and mods controlling it) said, to see why
                chunky_lines = [line.strip() for line in server.text()[since:].splitlines() if "chunky" in line.lower()]
                log("Chunky's first and last messages:\n  " + "\n  ".join(chunky_lines[:10] + ["..."] + chunky_lines[-10:]))
                last = chunky_lines[-1] if chunky_lines else "no Chunky output"
                record("warn", "Chunky pre-generates the area around the claims", f"not done after {args.chunky_timeout}s, last message: {last}")
                # Chunky asks to confirm cancelling
                server.command("chunky cancel")
                server.command("chunky confirm")

        ok, answer = server.step("check")
        record(ok, "Claims found and unchanged, loaded chunk counts right", answer)

        if grid and "bluemap" in installed:
            expected = int(grid.group(3))
            counts = bluemap_claim_markers(expected, args.bluemap_timeout)
            if counts is None:
                record("warn", "BlueMap shows the claims", "BlueMap's web server didn't answer")
            else:
                record(expected in counts.values(), "BlueMap shows the claims", f"claim markers per map: {counts}, expected {expected} on one")

        status = server.stop(180)
        record(status == 0, "Server stops cleanly", f"exit status {status}")

        server = start("restart.log", "Server starts again")
        if server:
            ok, answer = server.step("check")
            record(ok, "Claims survive the restart", answer)

            if args.bedrock:
                detection = server.wait_for(r"Bedrock player detection enabled using (\S+)", 5)
                api = "FloodgateApi" if "floodgate" in installed else "GeyserApi"
                record(bool(detection) and api in detection.group(1), "Bedrock player detection", detection.group(1) if detection else "not enabled")
                if not server.wait_for(r"Started Geyser on|geyser help", 60):
                    log("::warning::Didn't see Geyser's startup message, trying anyway")
                bot_status, steps = run_bot(directory, args.bot_timeout)
                for step in steps:
                    record(bool(step["ok"]), f"Bedrock: {step['name']}", step.get("details", ""))
                if not steps or not all(step["ok"] for step in steps):
                    # How Geyser and Floodgate handled the bot's login, from both starts
                    for started in servers:
                        lines = [line.strip() for line in started.text().splitlines() if re.search(r"(?i)floodgate|geyser|bedrockbot", line)]
                        log(f"Geyser / Floodgate lines in {started.log_path.name}:\n  " + "\n  ".join(lines[:60]))
                if not steps or bot_status != 0 and all(step["ok"] for step in steps):
                    record(False, "Bedrock bot", f"exit status {bot_status}, {len(steps)} checks")

            status = server.stop(180)
            record(status == 0, "Server stops cleanly after the test", f"exit status {status}")

    icon = {True: "✅", False: "❌", "warn": "⚠️"}
    table = ["| | Check | Details |", "|---|---|---|"]
    table += [f"| {icon[state]} | {check} | {details.replace('|', '/').replace(chr(10), ' ')[:300]} |" for state, check, details in rows]
    passed = sum(1 for row in rows if row[0] is True)
    failed = [row for row in rows if row[0] is False]
    warnings = sum(1 for row in rows if row[0] == "warn")
    summary(f"### {args.title}: {passed}/{len(rows)} checks passed" + (f", {warnings} warnings" if warnings else "") + "\n\n" + "\n".join(table) + "\n")

    for server in servers:
        report_errors(server, args.title)
    if failed:
        fail(f"{len(failed)} checks failed, first: {failed[0][1]} ({failed[0][2][:200]})", servers[-1] if servers else None)
    log("Full server test passed")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    setup_parser = commands.add_parser("setup")
    setup_parser.add_argument("--dir", required=True)
    setup_parser.add_argument("--goml", required=True, help="the GOML jar to test")
    setup_parser.add_argument("--test-jar", help="the goml-test jar")
    setup_parser.add_argument("--gametest", action="store_true", help="add Fabric's game test runner")
    setup_parser.add_argument("--mods-file", help="extra Modrinth mods, see .github/test-mods.txt")
    setup_parser.add_argument("--geyser", action="store_true")
    setup_parser.add_argument("--floodgate", action="store_true")
    setup_parser.add_argument("--goml-config", default="{}", help="JSON merged into config/getoffmylawn.json")
    setup_parser.add_argument("--world", choices=sorted(WORLDS), default="flat")
    setup_parser.add_argument("--geyser-auth", choices=("offline", "floodgate"), default="offline")
    setup_parser.add_argument("--title", default="Test server")
    setup_parser.set_defaults(run=setup)

    gametest_parser = commands.add_parser("gametest")
    gametest_parser.add_argument("--dir", required=True)
    gametest_parser.add_argument("--filter")
    gametest_parser.add_argument("--timeout", type=int, default=900)
    gametest_parser.add_argument("--xmx", default="3G", help="server heap")
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

    server_parser = commands.add_parser("server", help="the full server test, see full_server()")
    server_parser.add_argument("--dir", required=True)
    server_parser.add_argument("--xmx", default="6G", help="server heap")
    server_parser.add_argument("--timeout", type=int, default=900, help="seconds to wait for the server to start")
    server_parser.add_argument("--chunky-radius", type=int, default=192)
    server_parser.add_argument("--chunky-timeout", type=int, default=600)
    server_parser.add_argument("--bluemap-timeout", type=int, default=300)
    server_parser.add_argument("--bedrock", action="store_true", help="run the Bedrock bot after the restart")
    server_parser.add_argument("--bot-timeout", type=int, default=330)
    server_parser.add_argument("--title", default="Full server")
    server_parser.set_defaults(run=full_server)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
