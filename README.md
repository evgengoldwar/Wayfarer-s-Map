<div align="center">

# 🗺️ Wayfarer's Map

**A fast, modern minimap and world map for Minecraft 1.7.10 — built for GT New Horizons.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.7.10-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)](#-installation)
[![Forge](https://img.shields.io/badge/Forge-10.13.4.1614-E04E14?style=for-the-badge)](https://files.minecraftforge.net/)
[![Build](https://img.shields.io/github/actions/workflow/status/evgengoldwar/Wayfarer-s-Map/build-and-test.yml?style=for-the-badge&label=build)](https://github.com/evgengoldwar/Wayfarer-s-Map/actions/workflows/build-and-test.yml)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=for-the-badge)](src/main/resources/LICENSE)

[![Boosty](https://img.shields.io/badge/Support_on-Boosty-F15F2C?style=for-the-badge&logo=boosty&logoColor=white)](https://boosty.to/evgenwargold)
[![Telegram](https://img.shields.io/badge/Telegram-Channel-2AABEE?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/Shaterplay4)

[Features](#-features) •
[Installation](#-installation) •
[Controls](#%EF%B8%8F-controls) •
[Integrations](#-mod-integrations) •
[Team map](#-team-map) •
[Building](#%EF%B8%8F-building-from-source)

</div>

---

Wayfarer's Map draws everything you have **already seen**: a minimap in the corner of the screen, a fullscreen world map, an isometric **3D map like Dynmap**, waypoints, caves, biomes and topography — plus first-class layers for **GregTech ore veins, underground fluids, power failures, ServerUtilities claims, Thaumcraft nodes, AE2 wireless coverage, wireless chargers, drone centres and mob protection**.

It is **client-side**: the map is built from the chunks your client loads, so it works on any server. Install it on the server as well to unlock the shared **team map**, live teammate positions and `/wf chunkload`.

## ✨ Features

### 🧭 Minimap & world map

- **Minimap** — terrain, player arrow, players and mobs, coordinates and biome. Square or round, optionally **rotating with your view** with N / E / S / W on its edge.
- **Fullscreen world map** (`M`) — drag to pan, scroll to zoom from **1:8 to 32:1** towards the cursor, `Space` to jump back to the player. It reopens exactly where you left it.
- **JourneyMap-style shading** — colors come from the average of the block textures (resource packs and modded blocks included), tinted by biome, with relief shading, water depth and see-through glass.
- **Four map modes** — 🟩 2D, 🧊 3D, ⛰️ Topography (height bands with contour lines) and 🌳 Biomes.
- **Caves** — underground and in the Nether the map shows the cave floor in your 16-block layer. Auto / on / off with `K`, plus a slider to browse any layer from Y 0 to 255.
- **Day & night** — the map darkens with the game clock, and torches, lamps and lava glow warm. Can be locked to always day or always night.
- **Other dimensions** — browse any saved dimension without going there (Nether coordinates are converted 1:8).
- **Search** — in biome, ore or fluid view, type to grey out everything else and outline the matches.
- **Players & mobs** — shown with their face icon (cut from the mob texture, modded mobs too) in a colored frame: 🔴 hostile, 🟢 animals, 🟡 others. Filters per category.
- **Chunk grid**, **right-click teleport** (when you have `/tp` permission) and a built-in **help screen** (`?` on the map).

### 🧊 3D map

An isometric view of the world in the style of **Dynmap HD**, ray-traced from the real blocks:

- Block textures with resource packs and mods, biome-tinted grass, leaves and water, sky light and torch light, shade under trees.
- Water as one body — shallows show the floor, depth fades to blue.
- Blocks the game draws specially — **Chisel connected textures, chests, signs, modded machines, GregTech pipes and cables**, crops, beds, rails, redstone — are captured from the game's own renderer, so they look exactly like in game.
- A day **and** a night version of every tile, with a smooth transition.
- Turn the view to all four sides with `Q` / `E`, and pick a quality of **8, 16, 32 or 64 px per block**.
- Tiles render in background threads, nearest first; far zoom levels are cached on disk.

### 📍 Waypoints

- Name, coordinates, **outline color** (full color picker) and **any item as an icon**, drawn as in the inventory.
- Visible on the map, on the minimap (sticking to its edge when out of view) and in the world with distance.
- Optional **beacon beam** in the waypoint's color.
- **Groups** — drag waypoints into groups and hide or show a whole group with one toggle.
- **Death markers** — a skull waypoint where you died, kept in a *Deaths* group.
- **Share to chat** — players with the mod get a clickable **[Add]** button that imports the waypoint together with its group. Works on any server.

### 📸 Map export

The camera button saves the whole explored map — 2D (1–16 px/block) or 3D (2–64 px/block, day or night) — to `screenshots/wayfarmap/`:

- `index.html` — an **offline, Dynmap-like web viewer**: zoom from the whole map down to single blocks in any browser, no server needed.
- `overview.png` — the entire map as one image at full resolution (streamed row by row, so any size works).
- `preview.png` — a small thumbnail.

### ⚡ Performance

- Zoomed far out, the map draws from downscaled region copies (16× less memory).
- Off-screen regions are unloaded, region files are read in the background — no stutter at region borders.
- New textures and search highlights are built a little per frame; layers are drawn in batches.

## 🔌 Mod integrations

All integrations are **optional** — buttons appear only when the mod is installed. Turn them on from the **Mod layers** menu on the world map.

| Mod | What you get |
|---|---|
| ⛏️ **[VisualProspecting](https://github.com/GTNewHorizons/VisualProspecting)** | Prospected GregTech **ore veins** (ore icon, contents tooltip, *depleted* mark, **track** a vein in the world with distance) and **underground fluid** fields with amounts. Searchable by ore or fluid name. |
| 🏰 **[ServerUtilities](https://github.com/GTNewHorizons/ServerUtilities)** | **Claims** in their team colors and **chunk loading**. Drag a rectangle with `Ctrl` to claim, `Shift` to chunk-load, `Ctrl+Shift` for both; right-drag to undo. Limits shown in the corner. |
| ⚡ **[GregTech 5 Unofficial](https://github.com/GTNewHorizons/GT5-Unofficial)** | **Power failures** — machines that ran out of energy, with a red frame, details on hover and right-click to clear or set a waypoint. |
| 🔮 **[TCNodeTracker](https://github.com/GTNewHorizons/TCNodeTracker)** + Thaumcraft | **Aura nodes** in the color of their strongest aspect, searchable by aspect (`ignis`, `aer ordo`…), trackable in the world. |
| 📡 **[Applied Energistics 2](https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial)** | **Wireless coverage** — a circle around each wireless access point where the wireless terminal reaches it, at your height and at the full range. With the mod on the server: your networks' access points with their exact range; without it: those seen nearby, the range learned when you open one. |
| 🔋 **GregTech** and **[EnderIO](https://github.com/GTNewHorizons/EnderIO)** wireless chargers | **Charging range** of each wireless charger, at your height and at the full range. A GregTech charger's mode is known to the server only: without the mod there the range of every mode is shown until you right-click the charger. |
| 🛸 **GregTech** Drone Centre | **Range** of each drone centre, where it reaches the drone downlink modules, by its drone's level. |
| 🔦 **GregTech** Monster Repellator, **Extra Utilities** Magnum Torch and Chandelier | **Mob protection**: where monsters don't spawn around each. A repellator's power is known to the server only: without the mod there its range without power is shown. |

## 👥 Team map

With the mod on the **server** together with **ServerUtilities**, every team shares one map:

- 🔄 Join a team and your whole explored map (all dimensions and cave layers) syncs in the background — and you receive the team's.
- 🕒 Every chunk is timestamped, so the **most recently mapped** version always wins when maps merge.
- 🌍 Dimensions your team explored are browsable right away — without visiting them.
- 🧑‍🤝‍🧑 **Teammates are always visible** on the map and minimap, even far away or in another dimension; click one in the team list to jump to them.
- 🔒 Without a team, your map stays yours only. Servers without the mod keep working as before.

### `/wf chunkload`

Map a large area without walking it — the server loads (and generates, if needed) the chunks and your map draws them. Requires operator rights.

```
/wf chunkload 2d <radius>   # flat map, radius in chunks
/wf chunkload 3d <radius>   # flat + 3D map (slower)
/wf chunkload status        # progress
/wf chunkload stop          # stop
```

Or do it with the mouse in the world map's **Area loading** mode: drag with **Shift+LMB** to generate and load a rectangle of chunks, **Ctrl+LMB** to load only what is saved in the world, **Ctrl+Shift+LMB** to include caves; **Shift+RMB** cancels queued chunks, **Ctrl+RMB** deletes them from the map, **Esc** drops a drag. A hint by the cursor tells what the drag will do and how many chunks it takes. The toolbar has a **3D map** switch (picked chunks go onto the 3D map too, shown orange while queued — even with block recording off), **Stop**, and a progress bar with speed and time left.

## 📦 Installation

1. Install **Minecraft Forge 1.7.10** (or play the [GT New Horizons](https://www.gtnewhorizons.com/) pack).
2. Download the latest jar from [**Releases**](https://github.com/evgengoldwar/Wayfarer-s-Map/releases).
3. Drop it into your `mods/` folder.
4. *(Optional)* Put it into the server's `mods/` folder too, for the team map and `/wf chunkload`.

## ⌨️ Controls

All keys can be rebound in **Options → Controls → Wayfarer's Map**.

| Key | Action |
|:---:|---|
| `M` | Open / close the world map |
| `N` | Show / hide the minimap |
| `=` / `-` | Minimap zoom in / out |
| `B` | New waypoint where you stand |
| `U` | Waypoint list and groups |
| `K` | Cave mode: auto / off / on |
| `Space` | *(world map)* Back to the player |
| `Q` / `E` | *(3D map)* Rotate the view |
| `Right click` | *(world map)* Teleport, new waypoint, edit a waypoint |

<details>
<summary><b>Unbound by default</b> — bind them to toggle things without opening the map</summary>

<br>

Map view (2D, 2D without plants, topography, biomes) · ore veins · underground fluids · claims · power failures · Thaumcraft nodes · AE2 wireless coverage · wireless chargers · drone centres · mob protection · chunk grid · hostile / neutral / ambient / friendly mobs · pets · players · lighting (auto / day / night).

Each of them also has a **minimap-only** key, under *Wayfarer's Map: Minimap*: pressing one gives that part of the minimap its own setting, apart from the world map.

</details>

## ⚙️ Configuration

Every option is available in game from the **gear button** on the world map, grouped into *Minimap*, *World map*, *2D map*, *3D map*, *Entities*, *Waypoints*, *Commands* and *Logs* — hover any option for a full description. Settings are stored in `config/wayfarmap.cfg`.

<details>
<summary><b>Where is my data stored?</b></summary>

<br>

```
.minecraft/wayfarmap/<singleplayer|multiplayer>/<world or server>/player-<UUID>/
├── waypoints.json
├── ae2_wireless.json      # AE2 wireless access points seen without the mod on the server
├── chargers.json          # wireless chargers you have seen
├── drones.json            # drone centres you have seen
├── guards.json            # repellators, magnum torches and chandeliers you have seen
└── dim<id>/
    ├── r.X.Z.png          # 2D map regions, 512×512 blocks each
    ├── caves/<layer>/     # cave layers
    ├── blocks/r.X.Z.wfb   # recorded blocks for the 3D map
    └── iso/               # cached 3D tiles
```

Each account has its own map and waypoints, so several accounts on one PC never see each other's exploration. The team map is kept on the server in `<world>/wayfarmap/teams/<team>/`.

The **Stats** and **Bin** buttons on the world map show disk usage and clean up old maps.

</details>

## 🛠️ Building from source

Requires **JDK 25** (see `.java-version`).

```bash
git clone https://github.com/evgengoldwar/Wayfarer-s-Map.git
cd Wayfarer-s-Map
./gradlew build
```

The jar is written to `build/libs/`. Run `./gradlew spotlessApply` before committing to format the code.

## 🤝 Contributing

Bug reports, ideas and pull requests are welcome! Please open an [issue](https://github.com/evgengoldwar/Wayfarer-s-Map/issues) describing what you saw — and, for map glitches, the logs from **Settings → Logs**.

## 📄 License

Released under the [MIT License](src/main/resources/LICENSE).

<div align="center">

<br>

Made with ❤️ by [**EvgenWarGold**](https://github.com/evgengoldwar)

</div>
