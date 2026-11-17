# Handoff 3: GOML fork on Minecraft 26.3

Written 2026-11-17 at the end of the session that updated the fork to Minecraft 26.3. It continues
`CLAUDE_CODE_HANDOFF_2.md`, which lives on branch `claude/read-this-fully-gq1etl` and was never merged here.

Read the whole file before starting. The environment notes alone save a lot of trial and error.

## Start here

1. Check that the session runs on the cloud environment **`get-off-my-lawn-reserved`** and has Java 25
   (`ls /usr/lib/jvm`). See [Environment](#environment).
2. Base new work on `claude/lucid-hawking-t7p7mf`. The fork's `main` is plain upstream 26.2 and has none of the
   fork's work.
3. Build, then run the game tests. All 38 should pass:

```bash
./gradlew build gametestJar -x runGameTest && ./gradlew runGameTest
```

4. Look for new upstream commits. A fresh clone has no `upstream` remote, and anonymous reads of the public repo work:

```bash
git remote add upstream https://github.com/Patbox/get-off-my-lawn-reserved && git fetch upstream main && git log --oneline HEAD..upstream/main
```

## Where things stand

| | |
|---|---|
| Working branch | `claude/lucid-hawking-t7p7mf` at `248c2e1` |
| Published | The `alpha` pre-release is `1.22.32+26.3`, built from that commit ([release](https://github.com/pokeyt59/get-off-my-lawn-reserved/releases/tag/alpha), [CI run 28](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/43992032596), every job green) |
| Upstream | `Patbox/get-off-my-lawn-reserved` `main` at `9915f14` (their 1.22.0+26.3), fully merged. Their one open PR, [Patbox/get-off-my-lawn-reserved#111](https://github.com/Patbox/get-off-my-lawn-reserved/pull/111) (command translation keys), was already fixed here |
| Fork `main` | `a0a3c01`, upstream's 26.2 release. Untouched, and no PR was opened |
| Older branches | `claude/exciting-wright-x2oybv` (`113164c`) was the 26.2 alpha and is fully contained in the working branch. `claude/read-this-fully-gq1etl` (`98d34fb`) holds uncompiled Bedrock fixes and handoff 2, see [Handoff 2 items](#handoff-2-items) |

## What the last session did

`git log --oneline claude/exciting-wright-x2oybv..claude/lucid-hawking-t7p7mf`, oldest first:

| Commit | What |
|---|---|
| `9915f14` | Upstream's "Update to 26.3": 26.3-rc-1, `EnderMan` became `Enderman`, `FarmlandBlock#turnToDirt` became `turnToBaseBlock`, `randomTeleport` gained a block tag argument, mod-publish-plugin for their publishing |
| `ab334e5` | Merge of the above, no conflicts |
| `5cfa591` | Build for the 26.3 release instead of the release candidate, with current libraries (below). The mod now requires Minecraft `>=26.3-` |
| `6c5ee75` | Game tests for the three places 26.3 changed: farmland trampling, Ender Binding, Force Field |
| `9bb306b` | `test_server.py`: a listed mod version that isn't for the tested Minecraft version gives way to that mod's newest build for it |
| `1da3db5` | Test servers' Geyser config: `transport: both` with its own `webrtc-port`, so the RakNet Bedrock client can connect |
| `248c2e1` | Test servers' `server.properties`: `white-list=false` |

Each commit message explains its change in detail.

Versions now (`gradle.properties`): Minecraft 26.3, Fabric Loader 0.20.1, Fabric API 0.166.2+26.3,
Polymer 0.18.7+26.3, sgui 2.2.1+26.3, Server Translations API 3.2.0+26.3, Placeholder API 3.2.0+26.3,
Cardinal Components 8.1.0, PlayerAbilityLib 1.16.0 (the newest, works on 26.3), `mod_version` 1.22.0.
Gradle 9.2.1, Loom 1.15.

The fork's own code needed no changes for 26.3. All 25 mixins apply (checked with `-Dmixin.debug.verbose=true`).
`compat.DynmapClientMixin` has no target without Dynmap, so `Error loading class: org/dynmap/Client` at startup is
expected.

## What was verified

- **CI run 28**, every job passed:
  - build, translation lint, dev server startup with the mixin audit
  - the 38 game tests on a standalone server, again next to `.github/test-mods.txt` (11 of its 13 mods install,
    see [Open items](#open-items)), and again next to the server's mod list
  - Bedrock: a Bedrock client joins through Geyser, 12 of 12 checks, then Floodgate detection on a second boot
  - report only, "your server's mods with this build": every check passed with no warnings. 25 claims on
    generated terrain, Chunky pre-generation (625 chunks), right loaded chunk counts afterwards, 25 BlueMap markers,
    a restart with the claims intact, detection through Floodgate, and the Bedrock client's 12 checks (its name
    arrives as `.BedrockBot`, with Floodgate's prefix)
- **Locally, before pushing:** the same build, game tests and startup check, plus the game tests on standalone
  servers alone, with the compat mods and with the server's mod list. Each new game test was confirmed to fail with
  its protection switched off.
- **Not verified:** a source diff of the vanilla classes GOML hooks into, 26.2 against 26.3. Decompiling them was
  refused by the session's permission checks. The mixin audit and the tests stand in for it.

## Environment

The build needs Fabric, Mojang and mod Maven hosts that the account's **Default** cloud environment blocks, so
nothing builds there. The last session created a personal environment, **`get-off-my-lawn-reserved`**, saved for
this repository. Besides package registries it allows:

`maven.fabricmc.net`, `meta.fabricmc.net`, `maven.nucleoid.xyz`, `maven.ladysnake.org`,
`maven.jamieswhiteshirt.com`, `repo.bluecolored.de`, `repo.mikeprimm.com`, `www.cursemaven.com`,
`edge.forgecdn.net`, `mediafilez.forgecdn.net`, `jitpack.io`, `oss.sonatype.org`, `maven.blamejared.com`,
`maven.shedaniel.me`, `maven.terraformersmc.com`, `maven.resourcefulbees.com`, `api.modrinth.com`,
`cdn.modrinth.com`, `download.geysermc.org`, `libraries.minecraft.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`

Its setup script installs Java 25 and points Gradle at it. A fresh session on this environment is the script's
first real run, so check. If Java 25 is missing:

```bash
apt-get update && apt-get install -y openjdk-25-jdk-headless
```

```bash
mkdir -p ~/.gradle && echo 'org.gradle.java.home=/usr/lib/jvm/java-25-openjdk-amd64' >> ~/.gradle/gradle.properties
```

`JAVA_HOME` keeps naming the image's Java 21. Gradle follows the property.

Still unreachable there:

- `launchermeta.mojang.com`: Fabric's server launcher downloads the vanilla server from it, so the servers
  `test_server.py` sets up don't start. After any Gradle build, Loom's cached copy can go where the launcher looks
  first (or ask the user to allow the host):

```bash
mkdir -p server/.fabric/server && cp ~/.gradle/caches/fabric-loom/26.3/minecraft-server.jar server/.fabric/server/26.3-server.jar
```

- `api.minecraftservices.com` and `discovery.minecraftservices.com`: these only cause startup warnings.

Other limits:

- The image has Node 22. The Bedrock client needs Node 24 (`bedrock-protocol` 3.60 and newer), and `npm install`
  failed building `raknet-native`. Booting a Geyser/Floodgate server locally was also refused by the session's
  permission checks. So the Bedrock tests only ran in CI. Ask the user before working around either.
- The worker and container restarted several times. Background commands, and anything not committed and pushed,
  are lost on a restart.
- `test_server.py` sends a User-Agent naming this repository. From a Claude session, swap in a generic one with a
  small wrapper kept outside the repo:

```python
# python3 run_test_server.py <repo dir> <test_server.py arguments>
import importlib.util, sys
spec = importlib.util.spec_from_file_location("ts", sys.argv[1] + "/.github/scripts/test_server.py")
ts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ts)
ts.USER_AGENT = "fabric-test-server-setup/1.0"
sys.argv = ["test_server.py"] + sys.argv[2:]
ts.main()
```

## How to check a change

In the order CI runs them (`.github/workflows/build.yml`):

```bash
python3 .github/scripts/check_translations.py
```

```bash
./gradlew build gametestJar -x runGameTest
```

```bash
./gradlew runGameTest
```

- **Dev server startup:** copy the `startup` job's script from `build.yml`. It runs `./gradlew runServer` with a
  FIFO on stdin, waits for `Done (` and the update checker's log line, sends `stop`, then greps the log for mixin
  and GOML errors.
- **Standalone test servers**, like CI's game test jobs. Local builds are numbered `1.22.0+26.3`:

```bash
python3 .github/scripts/test_server.py setup --dir server --goml build/libs/goml-1.22.0+26.3.jar --test-jar build/test-libs/goml-test-1.22.0+26.3.jar --gametest --mods-file .github/test-mods.txt
```

```bash
python3 .github/scripts/test_server.py gametest --dir server --title "game tests"
```

  Leave out `--mods-file` for GOML alone. For the server's mod list use `.github/server-mods.txt`, and
  `--xmx 6G` on `gametest`.
- **What a mods file would install**, without downloading: load `test_server.py` as in the wrapper above, then call
  `ts.Mods(Path("/nonexistent"), "26.3").resolve(line.slug, line.name, line.pin)` for each line of
  `ts.read_mods_file(path)`. It returns `(project, version, how)`.

CI numbers builds itself: the patch number is the count of commits since `mod_version` last changed, so `248c2e1`
is `1.22.32`. Every push to `main` or a `claude/*` branch that passes the gating jobs replaces the `alpha` release,
docs-only pushes included, and two `claude/*` branches pushed in turn take it from each other. Put `[skip ci]` in
the message of a commit that shouldn't publish.

## Found along the way

- **Minecraft 26.3 turns the whitelist on** for a new `server.properties`: `white-list=true` is the new default,
  the only default that differs from 26.2. Existing files keep their value.
- **Geyser 2.12 removed its RakNet-only transport** and listens for NetherNet alone by default. RakNet still works
  with `transport: both` and a `webrtc-port` different from `port`. The test servers use 19132 and 19133. Their
  config still says `config-version: 8`, which Geyser (now at 12) migrates.
- **Floodgate has no Fabric download on GeyserMC's API**, so the script installs it from Modrinth (2.2.7-b69 in
  run 28).
- **`bedrock-protocol` 3.60.1**, pinned in `.github/bedrock-test/package.json`, joins as Bedrock 1.26.60 while
  Geyser advertises 26.63. 3.61.0 is out. Bump it if joins start failing after a Geyser update.
- **The update checker never offers a build for another Minecraft version** (`Candidate.of` in
  `UpdateChecker.java`), so servers on 26.2 aren't told about 26.3 builds. That move is manual.
- **Cardinal Components 8.1.0:** for the two modules GOML bundles, the only change from 8.0.0 is 8.0.1's sync
  buffer fix (and a Launchpad marker). Saved data is unaffected.
- Compiling shows deprecation warnings (`MutableObject#getValue`, `Item#appendHoverText`,
  `FabricBlockEntityTypeBuilder#build(Type)`, `GsonBuilder#setLenient`). They were left alone.

## Open items

1. **The live server's move to 26.3** (operator work). Install the jar by hand, see above. Check `white-list` in
   `server.properties` and the Geyser transport. On Modrinth, these of the server's mods have no 26.3 build:
   - Deeper Oceans, Fall Through End, Mud is Wet ("Wet n Muddy") and UncraftableNetherWartBlock. Fabric refuses to
     load their 26.2 builds on 26.3, which stops the server from starting.
   - Ender Dragon Fight Remastered. Fabric accepts its 26.2 build on 26.3, but nothing tested it there.
   - Dungeons+, which has no Fabric build on Modrinth for 26.2 or 26.3, so the tests never included it.

   Once the server has moved, put its new versions in `.github/server-mods.txt`.
2. **Three follow-ups were suggested to the user as separate tasks.** They may be done already, so check
   `git log` and the branches first.
   - `/goml admin list <player>` reads `player[0]` right after reporting an unknown player (`ClaimCommand.java`,
     around line 154). The fix is `71d64c8` on `claude/read-this-fully-gq1etl`. Vanilla's argument may throw before
     that point, so find out whether it can happen.
   - `.github/test-mods.txt` lists `mineable-spawners` and `invisible-frames`, Modrinth projects without Fabric
     builds, so the compat job always skips them. The server's mods are `mineable-spawner` and
     `invisible-frames-mod`. That job gates publishing, so check it still passes with them.
   - Bedrock players can't be found in the trust menu by the name shown in chat, as Floodgate adds a prefix.
     `48c035a` on `claude/read-this-fully-gq1etl` did this but was never compiled. Port it next to `BedrockCompat`.
     The full-server test's Floodgate player (`.BedrockBot`) could drive a test.
3. **What `main` should be** is the user's call. It's still upstream 26.2.
4. **26.2 builds:** the `alpha` release is 26.3 now. The last 26.2 jar (`1.21.26+26.2`) is only in the artifacts of
   [run 25](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/36323891845), until about
   26 December 2026 at GitHub's default 90-day retention. A tagged release from `claude/exciting-wright-x2oybv`
   (Actions, build, Run workflow, with a version) would keep one. Ask the user.
5. **`.github/workflows/release.yml` is upstream's.** It runs when a GitHub release is published by hand, attaches a
   jar numbered from `gradle.properties` (no CI patch number), and publishes to Patbox's Modrinth and CurseForge
   projects if `MODRINTH`/`CURSEFORGE` secrets are set. The fork's releases come from `build.yml`, whose releases
   don't trigger it. Worth retiring or retargeting, after asking.
6. **Optional, carried over from handoff 2:** Polymer textured blocks so heads show on Bedrock (its Task 4), not
   started. Upstreaming the fork's fixes to Patbox, not started either. Handoff 2 notes that issue creation there is
   restricted, so ask the user first.

## Handoff 2 items

| Handoff 2 | Now |
|---|---|
| Task 1, web map crash on unresolvable UUIDs ([Patbox/get-off-my-lawn-reserved#107](https://github.com/Patbox/get-off-my-lawn-reserved/issues/107)) | Fixed differently on `claude/exciting-wright-x2oybv`: `PlayerRecord` falls back to a default head icon on its own, and lookups run off-thread in `PlayerLookups` |
| Task 2, claim outlines for Bedrock players | Done there too (`BedrockCompat`, dust particles in `ClaimUtils` and `ForceFieldAugmentBlock`). The Bedrock test checks it |
| Task 3, Floodgate names in the trust menu | Not ported, see open item 2 |
| `admin list` crash ([Patbox/get-off-my-lawn-reserved#103](https://github.com/Patbox/get-off-my-lawn-reserved/issues/103)) | Not ported, see open item 2 |
| Task 4, Polymer textured blocks | Not started |
| Server-operator work listed at its end | Unknown here |

## Conventions

- Commit messages explain what changed and why in plain words, user-visible effects first. `git log` shows the
  style.
- `changelog-next` gets one line per user-facing change. It becomes the next release's notes.
- The README documents every config option and, under Testing, every CI job. Keep both current with the code.
