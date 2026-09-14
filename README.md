# Villager News Addon Port

A Fabric port of the **Villager News Add-On** for Minecraft Java Edition 26.2.
It brings the original Villager News characters, models, animations, textures,
voice acting, and contextual dialogue to Java Edition while retaining normal
Minecraft villager gameplay.

This fork converts the mod to run **entirely client-side**. The original port
required installing the mod on both the client and the server. This version
only needs to be installed on your client — it works in singleplayer, over
LAN, and on any multiplayer server, including vanilla servers that don't have
the mod (or any mod) installed at all. See
[Differences from the server-sided version](#differences-from-the-server-sided-version)
for what that trade-off costs.

## Community

Join the [Villager News Addon Port Discord server](https://discord.gg/vEpbtj2ChP)
for support, updates, and discussion.

## Features

- Detailed animated Villager News models converted for Entity Model Features
- Biome, profession, and profession-level villager textures
- The Mayor, Testificate Man, Villager Number 5, Villager Number 9, and
  Villager Unreachable as named characters
- Wooly the Sheep and the Villager News wandering trader
- 2,212 original voice clips across 523 dialogue groups
- Context-aware dialogue for player actions, nearby mobs, weather, dimensions,
  combat, trading, work, sleep, growth, and other world events
- Multi-part conversations between nearby villagers
- Facial expressions and gestures synchronized with each voice line
- Fully client-side dialogue selection, sound playback, cooldowns, and
  villager behavior — no server-side install required
- Speakers look toward the player, entity, block, or villager they are talking
  about
- Ringing a bell rouses every awake villager in earshot, each reacting in
  their own voice a moment later
- Villagers keep reacting for as long as they are poisoned, burning, freezing,
  or stuck in a block, and say something relieved once it stops
- 3D handbook and microphone models while held in first and third person
- Villager News subtitles have their own on/off toggle in the Handbook,
  separate from Minecraft's own sound subtitles
- Automatic, deterministic villager noses, cosmetics, and sign boards, with
  keybinds to toggle or cycle them per villager
- Optional Mod Menu configuration screen

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API for Minecraft 26.2
- Entity Model Features 3.3.5 or newer
- Entity Texture Features 7.2.1 or newer
- Entity Sound Features 0.8.2 or newer

EMF, ETF, and ESF are external dependencies. This project does not bundle or
modify them.

Mod Menu is optional. When installed, its Configure button opens the Villager
News settings directly. Without Mod Menu, the same settings remain available
in the Villager News Handbook.

## Installation

1. Install Fabric Loader for Minecraft 26.2.
2. Download Fabric API, EMF, ETF, and ESF for the same Minecraft version.
3. Put the dependency jars and the Villager News Addon Port jar in your
   **client's** Minecraft `mods` folder.
4. Start Minecraft with the Fabric profile.

That's it — nothing needs to be installed on the server. Everyone who wants to
see and hear Villager News content needs their own client install of the mod;
players without it simply see ordinary vanilla villagers, and the mod doesn't
require anyone's permission or cooperation from the server to work.

## Characters

Use a name tag on a villager to select a character model and voice:

| Name tag | Character |
| --- | --- |
| `Mayor`, `Mayor Villager`, or `The Mayor` | Mayor Villager |
| `Testificate Man` | Testificate Man |
| `Villager Number 5` or `Villager #5` | Villager Number 5 |
| `Villager Number 9` or `Villager #9` | Villager Number 9 |
| `Villager Unreachable` or `Can't Catch Me!` | Villager Unreachable |

Name a sheep `Wooly` or `Wooly The Sheep` to use Wooly's model, animations,
and sounds. Ordinary villagers and wandering traders receive their Villager
News appearance and dialogue automatically.

## Cosmetics, noses, and sign boards

Cosmetics (the Mayor's hat, Testificate Man's helmet, Villager #5's
moustache, Villager #9's microphone) now render automatically on the matching
named character — there's nothing to buy or equip.

Every villager has a nose by default, and roughly one in seven carries a sign
board with a message, both assigned automatically and consistently per
villager. Two keybinds (configurable in Controls, under "Villager News Addon
Port") let you override a specific villager while looking at it:

| Default key | Action |
| --- | --- |
| `N` | Toggle that villager's nose on/off |
| `B` | Cycle that villager's sign message (hold Shift to cycle/remove the sign's wood type) |
| `H` | Open the Villager News Handbook |

## Dialogue

Villagers react to what happens around them. They can comment when a player
approaches, stares, changes game mode, wears armor, receives an effect, breaks
or places a block, uses an item, completes a trade, or spawns a villager. They
also react to their profession, workstation, level, biome, weather, time of
day, nearby entities, damage, and other villagers.

Each client selects the exact voice variant and plays its matching animation
locally. Each speaker remains occupied for the real length of the clip,
preventing unrelated lines from overlapping. Conversation partners take turns
and continue looking at each other throughout multi-part exchanges.

## Differences from the server-sided version

Making the mod fully client-side means a few things that relied on
server-side authority had to be redesigned or dropped:

- **No spawn eggs and no natural special-villager spawning.** Custom items
  can't be reliably obtained on a server that doesn't have this mod, so the
  6 special-villager spawn eggs and the automatic natural spawning of
  characters in distant villages have been removed. Name a villager with a
  name tag instead — that still works everywhere, since names are ordinary
  synced vanilla data.
- **Cosmetics are automatic instead of purchasable.** Buying a hat from the
  Mayor relied on the server injecting a custom trade offer. Cosmetics are now
  assigned automatically and deterministically instead (see above).
- **Reputation- and raid-gated dialogue lines are approximated**, using a
  local per-client proxy (trades/gifts given to that villager, nearby raiders)
  instead of the real server-side gossip and raid state, which vanilla
  doesn't expose to clients.
- **The `/dialoguetest` developer command has been removed.**
- **In multiplayer, only players with the mod installed see or hear anything**
  — there's no server broadcasting a single shared decision anymore, so each
  client independently decides when and what a villager says. Multiple
  modded clients on the same server generally converge on the same lines at
  the same time for shared, world-driven triggers (weather, time of day,
  trading, villager conversations), since variant selection and timing are
  derived from the synced world clock rather than each client's own random
  state — but it isn't guaranteed to be frame-perfect, and reactions tied to
  one specific player's own actions (breaking a block, using an item,
  attacking) are only ever visible to that player's own client, since there's
  no server relaying them to bystanders.

Everything else — the models, animations, textures, 2,212 voice clips, and
the full contextual dialogue system — is unchanged.

## Building from source

On Windows:

```powershell
.\gradlew.bat build
```

On Linux or macOS:

```bash
./gradlew build
```

The distributable jar is written to `build/libs`.

Run the asset and dialogue verification with:

```powershell
node tools/verify-port.mjs
```

After extracting the original Bedrock packs into `build/bedrock-source`, create
a formatted copy of the complete add-on, a dialogue symbol map, a feature
inventory, and a Java dialogue coverage report with:

```powershell
node tools/deobfuscate-addon.mjs
```

The output is written to `build/deobfuscated-bedrock-source/full-addon`.
Wooly's smaller focused source map can also be generated with:

```powershell
node tools/deobfuscate-wooly.mjs
```

The focused output is written to `build/deobfuscated-bedrock-source/wooly`. The
known source symbols are documented in
[`docs/bedrock-deobfuscation/wooly.md`](docs/bedrock-deobfuscation/wooly.md).

## Credits

Villager News and the original add-on assets were created by **Oreville
Studios Ltd** and **Element Animation**. The converted models, textures,
animations, and audio remain the property of their respective owners. See
[`LICENSE`](LICENSE) for repository licensing details.
