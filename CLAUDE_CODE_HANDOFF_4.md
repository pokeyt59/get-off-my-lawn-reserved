# Handoff 4: GOML fork on Minecraft 26.3

Written 2026-11-17 at the end of the session that did handoff 3's follow-ups. It continues
`CLAUDE_CODE_HANDOFF_3.md` (in this branch), and repeats what's still true from it, so this file is enough to start.

## Start here

1. Check that the session runs on the cloud environment **`get-off-my-lawn-reserved`** and has Java 25
   (`ls /usr/lib/jvm`). Its setup script worked on this session's first start: Java 25 was there and Gradle pointed
   at it. See [Environment](#environment).
2. Base new work on **`claude/gallant-clarke-p9xawk`**. It has everything from `claude/lucid-hawking-t7p7mf` (the
   26.3 update) plus this session's fixes. The fork's `main` is still plain upstream 26.2.
3. Build, then run the game tests. All 40 should pass:

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
| Working branch | `claude/gallant-clarke-p9xawk`, code at `6ce5c99` |
| Published | The `alpha` pre-release is `1.22.37+26.3`, from run 30 (`3777833`), before the Bedrock lookup fix. Run 32 (`6ce5c99`, the fix) was still running when this was written: if it passed, the alpha is `1.22.39+26.3` |
| Upstream | `Patbox/get-off-my-lawn-reserved` `main` still at `9915f14` (their 1.22.0+26.3), fully merged. Nothing new on 2026-11-17 |
| Fork `main` | `a0a3c01`, upstream's 26.2 release. Untouched, no PR opened |
| Older branches | `claude/lucid-hawking-t7p7mf` (`73213b4`, handoff 3) is fully contained in the working branch. `claude/read-this-fully-gq1etl` (`98d34fb`) has nothing left to port: its two remaining fixes are done here, differently (below) |

## What this session did

`git log --oneline 73213b4..6ce5c99`, oldest first. Each commit message explains its change in detail.

| Commit | What |
|---|---|
| `35ab8ac` | **`/goml admin list <player>` failed for every player**, not just unknown ones. 26.3's player argument returns `NameAndId` records, which the command copied into a `GameProfile[]`: that compiles but throws `ArrayStoreException`. `ClaimListGui` now takes `NameAndId`. The unknown-player branch handoff 3 asked about (`71d64c8`) can't be reached through vanilla, which refuses unknown names first, but now returns instead of reading an empty array. New game test; it failed before the fix |
| `01b7d60` | `.github/test-mods.txt` and `server-mods.txt`: `mineable-spawner` and `invisible-frames-mod`, the server's mods. The old slugs were a Forge 1.20.1 mod and a Bukkit plugin, so the compat job had always skipped them. All 13 compat mods now install and the tests pass with them |
| `1318275` | **"Select by name" finds Bedrock players without Floodgate's prefix** ("Steve" finds ".Steve"). Ported from `48c035a`, but the Bedrock lookup goes first, see [Found along the way](#found-along-the-way). New `BedrockTests` game test, and the Bedrock bot checks it too |
| `3777833` | `server-mods.txt`: the server's "Inventory Sorter 3.0.0" is kyrptonaught's `inventory-sorting` on Modrinth. The old slug didn't exist and the name search found a client-only mod with the same title, so the full mod list test skipped it on 26.2 and installed the wrong mod on 26.3. Now 81 of the 83 lines resolve to the server's own versions on 26.2 |
| `8108e0b` | Fixes `1318275`, which the full mod list test (runs 29 and 30) caught: the Bedrock name and the same-name Java player are looked up in the server's name cache only (`profilesByName`, opened in `goml.accesswidener`), so they never ask Mojang, and the all-zero UUID of logins without Xbox authentication counts as a Floodgate UUID |
| `6ce5c99` | `BedrockTests` runs every lookup against a real `CachedUserNameToIdResolver` too (with a stand-in for Mojang that records what it's asked), because game test servers only have a mock name lookup, so `8108e0b`'s cache path had never run in a test |

Also in these commits: `RecordingPlayer` records the menus the server tries to open (Fabric's fake players never open
one), and a command failing with "An unexpected error occurred" now shows the exception in the test's message.

## What was verified

- **Locally:** translation lint, build, the 40 game tests in the dev environment, the dev server startup check
  (the `startup` job's script), and the 40 game tests on standalone servers with GOML alone and with
  `.github/test-mods.txt`. Each new test was confirmed to fail with its fix or rule switched off (for
  `BedrockTests`, eight rules one at a time, the real cache's lowercase keys and "never ask Mojang" included).
- **CI:**
  - [Run 29](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/44139572991) (`1318275`): the game
    tests, compat and Bedrock jobs passed, but the startup job hit Maven Central's rate limit, so nothing was
    published. The full mod list test (report only) caught the Bedrock lookup's problems: the bot wasn't found by
    "BedrockBot" (`foundByOwnName=false`, 12 of 13 checks), and a Mojang lookup logged a stack trace through GOML.
  - [Run 30](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/44141059956) (`3777833`): every gating
    job passed and `1.22.37+26.3` was published. The full mod list test failed the same way.
  - [Run 31](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/44143043306) (`8108e0b`): the build job
    hit Maven Central's rate limit.
  - [Run 32](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/44144427120) (`6ce5c99`): still
    running when this was written. Check it first: its full mod list job is the first end-to-end run of the
    cache-only lookup, with the bot joining through Floodgate. All 13 of the bot's checks should pass.
- **Not verified locally:** the Bedrock bot (needs Node 24 and a Geyser server, see [Environment](#environment)).
  Its new check is only a real check in the full mod list test, where the bot joins through Floodgate as
  `.BedrockBot`. In the gating Bedrock job it joins through Geyser alone, without a prefix, so it passes trivially
  there.

## Open items

1. **The live server's move to 26.3** (operator work, unchanged). Install the jar by hand. Check `white-list` in
   `server.properties` (26.3 turns it on for new files) and the Geyser transport (`transport: both` with its own
   `webrtc-port` for RakNet clients). On 2026-11-17 these of the server's mods still had no 26.3 build on Modrinth:
   Deeper Oceans, Fall Through End, Mud is Wet and UncraftableNetherWartBlock (Fabric refuses their 26.2 builds on
   26.3, so the server won't start with them), Ender Dragon Fight Remastered (its 26.2 build loads but is untested on
   26.3), and Dungeons+ (no Fabric build at all). Once the server has moved, put its new versions in
   `.github/server-mods.txt`. Of its 83 lines, two can't be the server's exact version: Dungeons+ (no Fabric build)
   and Clear End City (its builds are numbered V1 to 7, while the mod calls itself 1.0.0, so the newest is used).
2. **What `main` should be** is the user's call. It's still upstream 26.2.
3. **26.2 builds:** the last 26.2 jar (`1.21.26+26.2`) is only in the artifacts of
   [run 25](https://github.com/pokeyt59/get-off-my-lawn-reserved/actions/runs/36323891845), until about
   26 December 2026 (GitHub's default 90-day retention). A tagged release from `claude/exciting-wright-x2oybv`
   (Actions, build, Run workflow, with a version) would keep one. Ask the user.
4. **`.github/workflows/release.yml` is upstream's.** It runs when a GitHub release is published by hand, attaches a
   jar numbered from `gradle.properties` (no CI patch number), and publishes to Patbox's Modrinth and CurseForge
   projects if `MODRINTH`/`CURSEFORGE` secrets are set. The fork's releases come from `build.yml`, whose releases
   don't trigger it. Worth retiring or retargeting, after asking.
5. **Optional:** Polymer textured blocks so heads show on Bedrock (handoff 2's Task 4), not started. Upstreaming the
   fork's fixes to Patbox, not started either. The `admin list` fix is likely the crash in their issue
   [Patbox/get-off-my-lawn-reserved#103](https://github.com/Patbox/get-off-my-lawn-reserved/issues/103), which
   handoff 3 linked to it (this session didn't read upstream's issues). Handoff 2 notes that issue creation there is
   restricted, so ask the user first.

## Environment

The build needs Fabric, Mojang and mod Maven hosts that the account's **Default** cloud environment blocks, so
nothing builds there. The personal environment **`get-off-my-lawn-reserved`**, saved for this repository, allows
them. Besides package registries it allows:

`maven.fabricmc.net`, `meta.fabricmc.net`, `maven.nucleoid.xyz`, `maven.ladysnake.org`,
`maven.jamieswhiteshirt.com`, `repo.bluecolored.de`, `repo.mikeprimm.com`, `www.cursemaven.com`,
`edge.forgecdn.net`, `mediafilez.forgecdn.net`, `jitpack.io`, `oss.sonatype.org`, `maven.blamejared.com`,
`maven.shedaniel.me`, `maven.terraformersmc.com`, `maven.resourcefulbees.com`, `api.modrinth.com`,
`cdn.modrinth.com`, `download.geysermc.org`, `libraries.minecraft.net`, `piston-meta.mojang.com`,
`piston-data.mojang.com`

Its setup script installs Java 25 and points Gradle at it (`org.gradle.java.home` in `~/.gradle/gradle.properties`).
`JAVA_HOME` keeps naming the image's Java 21; Gradle follows the property. If Java 25 is ever missing:

```bash
apt-get update && apt-get install -y openjdk-25-jdk-headless
```

```bash
mkdir -p ~/.gradle && echo 'org.gradle.java.home=/usr/lib/jvm/java-25-openjdk-amd64' >> ~/.gradle/gradle.properties
```

Still unreachable there:

- `launchermeta.mojang.com`: Fabric's server launcher downloads the vanilla server from it, so the servers
  `test_server.py` sets up don't start on their own. After any Gradle build, put Loom's cached copy where the launcher
  looks first (`<server dir>/.fabric/server/26.3-server.jar`), before or after `setup` (it only clears `mods/`):

```bash
mkdir -p server/.fabric/server && cp ~/.gradle/caches/fabric-loom/26.3/minecraft-server.jar server/.fabric/server/26.3-server.jar
```

- `api.minecraftservices.com` and `discovery.minecraftservices.com`: these only cause startup warnings.
- The GitHub API from the shell. Use the GitHub MCP tools for Actions runs and logs.

Other limits:

- The image has Node 22. The Bedrock client needs Node 24 (`bedrock-protocol` 3.60 and newer), and `npm install`
  failed building `raknet-native`. Booting a Geyser/Floodgate server locally was refused by the permission checks in
  handoff 3's session. So the Bedrock tests only run in CI. Ask the user before working around either.
- The worker and container can restart. Background commands, and anything not committed and pushed, are lost then.
- `test_server.py` sends a User-Agent naming this repository. From a Claude session, swap in a generic one with a
  small wrapper kept outside the repo (the scratchpad works):

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

- `server/` isn't in `.gitignore`. Put local test servers in the scratchpad (`--dir <scratchpad>/compat-server`),
  so they can't end up in a commit.
- Reading Minecraft's API with `javap` (signatures only) on `~/.gradle/caches/fabric-loom/26.3/minecraft-merged.jar`
  works and answers most "what does this return in 26.3" questions. Decompiling was refused in handoff 3's session
  and wasn't tried again.

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
  and GOML errors. Keep its log and FIFO outside the repo.
- **Standalone test servers**, like CI's game test jobs (local builds are numbered `1.22.0+26.3`), through the
  wrapper above:

```bash
python3 run_test_server.py . setup --dir <scratchpad>/compat-server --goml build/libs/goml-1.22.0+26.3.jar --test-jar build/test-libs/goml-test-1.22.0+26.3.jar --gametest --mods-file .github/test-mods.txt --title compat
```

```bash
python3 run_test_server.py . gametest --dir <scratchpad>/compat-server --title "compat game tests"
```

  Leave out `--mods-file` for GOML alone. For the server's mod list use `.github/server-mods.txt`, and
  `--xmx 6G` on `gametest`.
- **What a mods file would install**, without downloading: load `test_server.py` as in the wrapper, then for each
  line of `ts.read_mods_file(path)` call `ts.Mods(Path("/nonexistent"), "26.3").resolve(line.slug, line.name,
  line.pin)`. It returns `(project, version, how)`.

CI numbers builds itself: the patch number is the count of commits since `mod_version` last changed. Every push to
`main` or a `claude/*` branch that passes the gating jobs replaces the `alpha` release, docs-only pushes included,
and two `claude/*` branches pushed in turn take it from each other. Put `[skip ci]` in the message of a commit that
shouldn't publish.

## Found along the way

- **26.3's `GameProfileArgument.getGameProfiles` returns `Collection<NameAndId>`.** Code that treats the result as
  `GameProfile`s can still compile (`toArray(T[])` takes any array type, and both types have `id()` and `name()`),
  then fail at runtime. The `admin list` command was the only such place.
- **The server's name cache asks Mojang about every name it doesn't know, in offline mode too**
  (`CachedUserNameToIdResolver.get(String)`), and when Mojang doesn't know it either, an offline-mode server makes up
  an offline player. So it rarely comes back empty, and "look the name up, try something else if nobody is found"
  doesn't work with it. To look in the cache only, read `profilesByName` (lowercase names), which GOML's access
  widener opens; `BedrockCompat.cached` does. From GitHub's runners Mojang sometimes answers with an HTML error page,
  and authlib then logs an ERROR with a stack trace: if that runs through GOML code, `test_server.py` counts it as a
  GOML error.
- **No game test server has that cache.** Vanilla's `GameTestServer` has a `MockUserNameToIdResolver`: it keeps added
  names in a set, matches them case-sensitively, makes up an offline player for any other name and never asks
  Mojang. The standalone test servers (CI's game test jobs) run `GameTestServer` too, as Fabric's game test module
  starts it on dedicated servers (`server.MainMixin`). To test against the real cache, build a
  `CachedUserNameToIdResolver` with a stand-in `GameProfileRepository`, like `BedrockTests` does. The end-to-end
  servers (Bedrock job, full mod list test) are normal servers with the real cache.
- **Bedrock logins without Xbox authentication** (the test bot's) get Xbox id 0 from Floodgate, so the all-zero UUID.
- **Floodgate's players** have UUIDs with a zero first half (made from the Xbox id), and names with its prefix.
  Its bundled `config.yml` defaults to `username-prefix: "."` and `replace-spaces: true` (spaces become `_`). Java
  names can't be longer than 16 characters, so the lookup also cuts the prefixed name to 16; how Floodgate shortens
  longer names wasn't checked in its code. Its API
  (`org.geysermc.floodgate.api.FloodgateApi`, checked in 2.2.7-b69) has `getInstance()`, `getPlayerPrefix()`,
  `isFloodgatePlayer(UUID)` (online players) and `isFloodgateId(UUID)`.
- **26.3 keeps player data in `world/players/data/<uuid>.dat`**, not `world/playerdata`. Use
  `server.getWorldPath(LevelResource.PLAYER_DATA_DIR)`.
- **A mod's Modrinth title can differ from the name in a server's mod list** (kyrptonaught's Inventory Sorter is
  "Inventory Sorting" there), and `test_server.py`'s name search only accepts close titles. Give such mods their
  real slug. To spot them, resolve the list for the server's current Minecraft version: every line should say
  "your version".
- **Fabric's `FakePlayer.openMenu` opens nothing.** Tests see GUIs through `RecordingPlayer.menus` (the menu's
  `getDisplayName()` is the GUI's title).
- From handoff 3, still true: Minecraft 26.3 turns the whitelist on for a new `server.properties`; Geyser 2.12
  removed its RakNet-only transport (`transport: both` and a separate `webrtc-port` bring RakNet back); Floodgate
  has no Fabric download on GeyserMC's API, so the script installs it from Modrinth; `bedrock-protocol` 3.60.1
  (pinned in `.github/bedrock-test/package.json`) joins as Bedrock 1.26.60, bump it if joins start failing after a
  Geyser update; the update checker never offers a build for another Minecraft version (`Candidate.of` in
  `UpdateChecker.java`), so 26.2 servers aren't told about 26.3 builds.
- **CI's Gradle cache is never written for `claude/*` branches:** setup-gradle only saves it on the default branch,
  and the fork's `main` isn't built. So every Gradle job downloads all dependencies again, and Maven Central
  sometimes answers HTTP 429 (Too Many Requests): in run 29 the dev server startup job, in run 31 the build job, both
  while resolving Loom's dependencies, so neither run published. Re-run the failed jobs, or, if it keeps happening,
  look at letting this branch write the cache (setup-gradle's `cache-read-only: false`; its cache key is per job, so
  check that the startup job can restore what the build job saved).
- Compiling shows deprecation warnings (`MutableObject#getValue`, `Item#appendHoverText`,
  `FabricBlockEntityTypeBuilder#build(Type)`, `GsonBuilder#setLenient`). They were left alone.

## Conventions

- Commit messages explain what changed and why in plain words, user-visible effects first. `git log` shows the
  style.
- `changelog-next` gets one line per user-facing change. It becomes the next release's notes.
- The README documents every config option and, under Testing, every CI job. Keep both current with the code.
