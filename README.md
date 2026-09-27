# Get Off My Lawn ReServed

*Get Off My Lawn ReServed* is a take on the popular concept of player claims for Survival/Freebuild Fabric servers. 
This mod works fully server side (no client mod required!) while being compatible with major Fabric modpacks

This project is a fork [Get Off My Lawn by Draylar](https://github.com/Draylar/get-off-my-lawn), with focus on improving and building on top of the original.


# Video Showcase

* English: https://youtu.be/R9-PuMRbNEc

* Polish: https://youtu.be/1V8kh0h3NoU

# Getting started

To get started, you'll have to craft a *Claim Anchor*. Each anchor has a different (configurable by admin) claim radius; after placing one, a box around it will be formed. This box is yours!

* **Makeshift**, default radius of 10
* **Reinforced**, default radius of 25
* **Glistening**, default radius of 50
* **Crystal**, default radius of 75
* **Emeradic**, default radius of 125
* **Withered**, default radius of 200

To see claim areas, you'll have to craft a *Goggles of (Claim) Revealing*

When this item equipped in the helmet, mainhand or offhand slot, claim outlines become visible.

## [Recipes](recipes.md)

## Claim configuration:
To configure your claim, you can interact with the anchor block. A UI will appear that offers several configuration options:
- The player list can be used to add and remove access of players to your claim
- The Augment list, that can be used for checking and configuring active augments

## Claim upgrades:
To upgrade your claim, place an Anchor Augment next to the core Claim Anchor. Anchor Augments available include:
- Ender Binding: Prevents Endermen from teleporting
- Villager Core: Prevents Zombies from damaging Villagers
- Greeter: MOTD to visitors
- Angelic Aura: Regen to all players inside region
- Withering Seal: Prevents wither status effect
- Force Field: non-whitelisted players get launched out of the claim
- Heaven's Wings: flight
- Lake Spirit's Grace: water breathing, water sight, and better breathing
- Chaos Zone: Strength to all players inside region
- PvP Arena: Allows changing pvp state in claim
- Explosion Controller: Allows toggling explosion protection

## Config:
You can find config file in `./config/getoffmylawn.json`. To reload it, just type `/goml admin reload` in chat/console (it only checks for updates when the update options changed). `/goml admin update` checks for updates right away.

```json5
{
  "makeshiftRadius": 10,                // Radius of makeshift claim
  "reinforcedRadius": 25,               // Radius of reinforced claim
  "glisteningRadius": 50,               // Radius of glistening claim
  "crystalRadius": 75,                  // Radius of crystal claim
  "emeradicRadius": 125,                // Radius of emeradic claim
  "witheredRadius": 200,                // Radius of withered claim
  "claimProtectsFullWorldHeight": false,// Makes claim protect area from bottom of the world to top
  "dimensionBlacklist": [               // Allows to blacklist specific dimensions
    "example:dim"
  ],             
  "regionBlacklist": {                  // Allows to blacklist specific regions
    "example:dim": [
      {
        x1: -200,
        y1: -64,
        z1: -200,
        x2: 200,
        y2: 512,
        z2: 200,
      }
    ]
  },
  "enabledAugments": {                  // Allows to enable/disable augments per their id
    "goml:lake_spirit_grace": true,
    "goml:angelic_aura": true,
    "goml:greeter": true,
    "goml:force_field": true,
    "goml:village_core": true,
    "goml:withering_seal": true,
    "goml:ender_binding": true,
    "goml:heaven_wings": true,
    "goml:chaos_zone": true
  },
  "allowedBlockInteraction": [          // Allows to interact with specific blocks in claim
    "somemod:store"
  ],
  "allowedEntityInteraction": [         // Allows to interact with specific entities in claim
    "minecraft:villager"
  ],
  "messagePrefix": "<dark_gray>[<#a1ff59>GOML</color>]", // Default prefix used in messages
  "placeholderNoClaimInfo": "<gray><italic>Wilderness",
  "placeholderNoClaimOwners": "<gray><italic>Nobody",
  "placeholderNoClaimTrusted": "<gray><italic>Nobody",
  "placeholderClaimCanBuildInfo": "${owners} <gray>(<green>${anchor}</green>)",
  "placeholderClaimCantBuildInfo": "${owners} <gray>(<red>${anchor}</red>)",
  "claimColorSource": "location",       // either "location" or "player" - "location" will chose the color based on the location of the claim (hash of coordinates), "player" will chose the color based on the owner of the claim (hash of UUID).
  "claimEnterLeaveMessages": false,     // Show "Entering/Leaving <owner>'s claim" in the action bar when players walk into or out of a claim
  "checkForUpdates": true,              // Checks GitHub for a newer version and tells the console and admins (goml.update_notify permission, or level 3) about it. Nothing is downloaded.
  "updateChannel": "release",           // either "release" (normal releases) or "alpha" (the latest development build, from the main and claude/* branches)
  "updateCheckIntervalHours": 12,       // How often to check again while the server runs, 0 to only check on startup
  "autoUpdate": false,                  // Dedicated servers only: download the new version (checked against GitHub's checksum) and install it when the server stops, so it's used from the next start. The previous jar is kept as <name>.jar.old in the mods folder. Needs checkForUpdates.
  "autoUpdateBackups": 1                // How many older versions autoUpdate keeps as .jar.old backups, 0 for none, -1 to keep all
}
```


## Testing

Every push is built once, then tested in parallel on separate test servers before anything is published
(`.github/workflows/build.yml`):

* **Game tests** (`src/gametest`): a standalone Fabric server with the built jar runs GOML's game tests. Fake players
  break, use and attack things in claims. The tests also cover fluids, pistons, dispensers, falling blocks,
  explosions, augments, `/goml` commands (including every message's translation) and claim storage.
* **Compat**: the same tests next to the performance and gameplay mods listed in `.github/test-mods.txt`.
* **Bedrock**: a Bedrock client (`.github/bedrock-test`) joins through Geyser. It checks that GOML detects it, that
  protection and enter/leave messages arrive translated, that goggles draw particles, that Chaos Zone doesn't spam
  effect updates, and that menus open. A second boot checks detection through Floodgate.

The alpha (and releases) only publish when all of these pass. Locally, `./gradlew runGameTest` runs the game tests.

A **full mod list test** runs alongside them but only reports, so the alpha doesn't wait for it. It uses the whole mod
list of the server the alphas are installed on (`.github/server-mods.txt`), at that server's versions where Modrinth
has them. It runs the game tests with all of those mods, then a normal server with them:

* claims on generated terrain;
* Chunky pre-generation around those claims, after which each claim's loaded chunk count must still be right;
* BlueMap's claim markers;
* a restart, after which the claims must still be there;
* a Bedrock player through Geyser and Floodgate.

After updating the server's mods, update the versions in that file.

## License
*Get Off My Lawn ReServed* is available under the MIT license. The project, code, and assets found in this repository are available for free public use (as long as credited).
