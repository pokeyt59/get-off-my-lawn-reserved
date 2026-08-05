# Handoff 2: GOML fork — build, verify, and land the Bedrock fixes

Continuation of `CLAUDE_CODE_HANDOFF.md`. Tasks 1–3 are **written but never compiled**.
Your job is to build them, fix whatever the compiler finds, test, and land them.

## Why nothing was compiled

The previous session ran in a sandboxed container whose egress policy blocked every
Minecraft-ecosystem host: `maven.fabricmc.net`, `maven.nucleoid.xyz`,
`maven.ladysnake.org`, `maven.jamieswhiteshirt.com`, `repo.bluecolored.de`,
`repo.mikeprimm.com`, `api.modrinth.com`, and Mojang's own `piston-meta` /
`piston-data` / `libraries.minecraft.net` all returned 403 at the proxy. Only Maven
Central and `plugins.gradle.org` were reachable, so Fabric Loom itself could not
resolve and `./gradlew compileJava` never ran.

Everything below is therefore **verified by reading the source, not by javac**. The
logic was traced against real call sites; the API signatures are the risk.

**Do not build on the game server** — 2 Sandy Bridge cores at 96–98% CPU. Build on the
dev box.

---

## Branch

`claude/read-this-fully-gq1etl` on `pokeyt59/get-off-my-lawn-reserved`, four commits on
top of upstream `a0a3c01`:

| Commit | Task | Files |
|---|---|---|
| `16e5c95` | 1 — BlueMap/Dynmap NPE (P0) | `compat/webmap/player/PlayerRecord.java` |
| `613638c` | 2 — Bedrock claim outlines | `api/ClaimUtils.java`, `block/augment/ForceFieldAugmentBlock.java`, `other/FloodgateBridge.java` (new) |
| `48c035a` | 3 — Floodgate name resolution | `other/FloodgateBridge.java`, `ui/NamePlayerSelectorGui.java` |
| `71d64c8` | — `admin list` crash (#103) | `other/ClaimCommand.java` |

Each commit is self-contained and intended to become its own upstream PR against
`Patbox/get-off-my-lawn-reserved`. Keep them separate — don't squash.

---

## First thing: compile

```
./gradlew build
```

Fix compile errors before anything else. Ranked by how likely they are to be wrong:

### 1. `DustParticleOptions` constructor — highest risk

Used in `ClaimUtils.gogglesClaimParticle()` and `ForceFieldAugmentBlock.onPlayerEnter()`:

```java
new DustParticleOptions(webMapClaimColor(claim), GOGGLES_DUST_SCALE)  // (int rgb, float scale)
```

This assumes the **packed-int** signature that Mojang moved to around 1.21.4. Older
versions took `(Vector3f color, float scale)`. If 26.2 still wants `Vector3f`:

```java
new DustParticleOptions(Vec3.fromRGB24(webMapClaimColor(claim)).toVector3f(), GOGGLES_DUST_SCALE)
```

There is no existing `DustParticleOptions` or `Vector3f` usage anywhere in GOML to
crib the correct form from — this was unverifiable without the Minecraft jar.

Also double-check the import spelling. GOML uses Mojang mappings, where the block one
is `BlockParticleOption` (no `s`) but the dust one is `DustParticleOptions` (with `s`).
That inconsistency is real in mojmap, but confirm it rather than trusting this note.

### 2. Floodgate reflection targets

In `FloodgateBridge`:

- `FabricLoader.getInstance().isModLoaded("floodgate")` — confirm the **actual mod id**
  of the Fabric Floodgate port. If it differs, `IS_LOADED` is permanently false and
  everything silently falls back to UUID detection, which still works but never reads
  the configured prefix.
- `org.geysermc.floodgate.api.FloodgateApi` with `getInstance()`,
  `isFloodgatePlayer(UUID)`, `getPlayerPrefix()`.

These are reflective, so **they will compile even if wrong** — a wrong name only shows
up at runtime as the info-level log line "Floodgate is present but its API couldn't be
reached". Watch for that line on first boot. Verify against the Floodgate jar on the
server:

```
unzip -l floodgate-fabric.jar | grep -i floodgateapi
javap -cp floodgate-fabric.jar org.geysermc.floodgate.api.FloodgateApi | grep -iE 'getInstance|isFloodgatePlayer|getPlayerPrefix'
```

The UUID fallback (`getMostSignificantBits() == 0`) is the safety net and is correct
independently of the API, so Task 2 works even if all of this is wrong.

---

## What each change actually does

### Task 1 — the P0 (`16e5c95`)

Root cause confirmed exactly as the first handoff described: in the
`PlayerRecord(UUID, MinecraftServer, String)` constructor the fallback
`playerIcon = new PlayerHeadIcon(null)` sat **inside** the `if (this.name == null)`
guard. A Floodgate UUID misses at the Mojang session server (so no icon is built) but
hits the local user cache (so a name *is* found), the guard never fires, and
`getHeadIcon()` returns null.

Two changes: the icon now falls back independently of the name, and `getHeadIcon()`
lazily builds a default so it can never return null.

**Correction to the first handoff:** it said `ClaimMarker.renderHtml()` dereferences
`getHeadIcon()` in two places. It's **four** — owners, trusted, augments, and the
`claimAnchorType` at the end. The hardened getter covers all four.

**Second correction:** it said to fix both `BluemapCompat` and `DynmapCompat`. Not
needed — both route through the same `ClaimMarker.renderHtml()`, so neither file is
touched. Upstream #107 (the Dynmap report) is fixed by this same commit.

### Task 2 — Bedrock outlines (`613638c`)

`ClaimUtils.gogglesClaimParticle(player, claim)` picks the particle per viewer: dust
for Bedrock, unchanged `BLOCK_MARKER` for Java. `drawClaimInWorld` and the force field
wall both call it.

The colour comes from the **existing** `CLAIM_COLORS_RGB` array, which is already
index-parallel to `CLAIM_COLORS_BLOCKS` through `claimColorIndex(claim)` — so Bedrock
gets exactly the colour Java gets, and the `claimColorSource` config option keeps
working for both with no new plumbing. The force field wall uses a fixed red
(`0xD00000`) since its Java form is a barrier marker, not a claim colour.

### Task 3 — name resolution (`48c035a`)

`FloodgateBridge.resolveName(server, name)` tries the plain name, then retries with the
prefix read from Floodgate's config. Wired into `NamePlayerSelectorGui` (the trust GUI).
The GUI's `input.length() > 16` check was also relaxed by the prefix length, since a
prefixed name can otherwise be rejected before it's ever looked up.

**Deliberately not done:** the `/goml trust`, `/goml untrust`, `/goml admin removeowner`
and `/goml admin list` commands all take vanilla's `GameProfileArgument.gameProfile()`.
Making *those* prefix-tolerant needs a mixin into vanilla argument resolution — far more
invasive and much less likely to be accepted upstream. Decide whether that's worth a
separate PR after seeing whether the GUI path covers real usage. Note that Bedrock
players who have joined **are** in the user cache under their prefixed name, so
`/goml trust .SomeName` already works today; only the unprefixed form fails.

### #103 (`71d64c8`)

Separate from Task 3, found while reading `ClaimCommand`. The `admin list` unknown-player
branch printed its error then fell through to `player[0]` on an empty array. Added the
missing `return 0`. This may or may not be the whole of #103 — vanilla's
`GameProfileArgument` usually throws before reaching that branch, so confirm the reported
symptom actually goes away before claiming the issue is fixed.

---

## Test checklist

Compile first, then in order:

1. **Task 1 (P0).** Boot with the existing world, which still contains the claim owned by
   a Floodgate UUID. Server should reach "Done" instead of crash-looping in
   `afterSetupServer`. Open BlueMap, click the claim marker — the Bedrock owner should
   render with the placeholder head and their cached name, not throw.
   - Test fixture from handoff 1: `.altpromrs1010` / `00000000-0000-0000-0009-01fb7b30d961`.
   - Also check the Dynmap path if Dynmap is installed (#107).
2. **Task 2.** Join on Bedrock, equip Goggles of Revealing, stand in a claim. Outline
   should render as coloured dust. Stand near two adjacent claims and confirm the colours
   differ. Then join on **Java** and confirm the outline is still the block marker and
   looks exactly as before — this is the main regression risk.
   - Also trigger a force field wall on Bedrock; it should show as red dust.
3. **Task 3.** From the trust GUI, type a Bedrock player's name **without** the `.` and
   confirm it resolves. Confirm a Java name still resolves unchanged.
4. **#103.** `/goml admin list <nonexistent>` should print the unknown-player message
   rather than throwing.

Watch the log on first boot for `Floodgate is present but its API couldn't be reached` —
that means the reflection targets in §2 above need correcting.

---

## Upstream PRs

MIT licensed, maintainer active. Commits 1, 2, 3 are each PR-ready; `71d64c8` is a
trivial fourth. Reference #107 in the Task 1 PR and #103 in the `admin list` one.

**Issue creation is restricted on the repo** — coordinate with the maintainer before
filing anything. Check for overlap with known open issues first: #109 (26.2 startup),
#107, #103, #102, #98, #73.

Do not bump dependency versions in any of these PRs.

---

## Not started

**Task 4** (`PolymerHeadBlock` → Polymer textured blocks) is untouched. It needs ~19
authored block textures under `assets/goml/`, which currently contains only `icon.png`,
and it's a genuine fork-level change rather than an upstream-able patch. Per the original
handoff, only pick this up if Steve heads on Bedrock are still a real complaint after
1–3 are deployed.

## Still server-operator work, not code

Unchanged from handoff 1: update Geyser-Fabric past `2.11.0-b1205` (Bedrock 26.40 clients
fail with the `Chain` / `InitialConnection-34` login error) plus matching Floodgate and
MCXboxBroadcast; remove Async (axalotl) per its issue #109; fix the JVM flags
(`-Xmx8000M` against ~7.9 GB visible with no swap — reduce it and set `-Xms` equal to
`-Xmx`); disable AMP's crash auto-restart while debugging.
