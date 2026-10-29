<!--
  The mod's changelog: shown in the "What's new" window (after the mod is updated, and from a button in "About").
  One file per language: en_US.md, ru_RU.md, uk_UA.md, zh_CN.md. A language without a file shows en_US.md.

  How to write it (newest version on top):

  # 1.2.0 | 2026-10-08        a version; the date after "|" (optional)
  ## Map                      a heading inside a version
  - [new] text                a point with a tag: [new], [fix], [change], [remove]
  - text                      a point without a tag
    - text                    a point inside the one above (indented by 2 spaces)
  > text                      a note in a frame
  text                        a plain paragraph

  In any text: **bold**, `key`, *italic*, and Minecraft's § colors.
  A line right under a point carries it on. Comments like this one are left out.
-->

# 0.0.21

## Coverage zones
- [new] Mob protection (GregTech, Extra Utilities): where monsters don't spawn around a Monster Repellator (green), a Magnum Torch (orange, 128 blocks around but only 32 up and down) and a Chandelier (yellow). Switched off — red. A repellator's energy is known only to the server: without the mod there its circle is the range without power, gray. Only the blocks you have seen are shown
  - The thin ring can be turned off: settings → Mod layers → Mob protection → Full range ring

# 0.0.20

## Coverage zones
- [new] Three mod layers on the map and the minimap (the mod layers button) show where machines reach: a circle around each, filled at your height (the distance is counted in 3D) and with a thin ring at the full range; circles of one color merge where they overlap
  - The thin ring can be turned off for each layer: settings → Mod layers → AE2 wireless, Wireless chargers or Drone centres → Full range ring
- [new] AE2 wireless coverage: where a wireless terminal reaches each wireless access point; blue — working, red — no power or no channel. With the mod on the server: the access points of your terminals' networks with their exact range; without it: those seen nearby, the range learned when you open one
- [new] Wireless chargers (GregTech, EnderIO): where each charges your items; GregTech amber, EnderIO violet, red — switched off or empty. EnderIO's range comes from its config. A GregTech charger's mode is known only to the server: without the mod there the circle is the range of every mode until you right click the charger. Only the chargers you have seen are shown; with the mod on the server it tells their real mode, hides others' locked ones and keeps the state of those in loaded chunks current even from afar
- [new] Drone centres (GregTech): where each reaches the drone downlink modules, by its drone's level; teal — working, red — no drone or not running. The client learns the drone level from GregTech 5.09.52.615 on; on older versions the range comes with the mod on the server. Only the centres you have seen are shown
- [new] Each machine is marked by its layer's icon, with a red warning triangle when it doesn't work. On the world map the circle under the cursor is outlined in a lighter shade of its color, with whose it is by the cursor
- [new] Welcome window: a "Coverage zones" card among what the mod can do

# 0.0.17

- [new] Minimap: names of other players and teammates under their heads (settings → Minimap → Player names, on by default)
- [new] Themes for all of the mod's screens: Graphite (as before, the default), Forest, Nether, Ocean, End, Desert, Snow (light) and Vanilla (like Minecraft's own windows, with raised buttons). Most have a strip of their own under the window headers: leaves, flowing lava, prismarine, sandstone. Settings → World map → Theme, and a page with a picture of each in the welcome window, after the language
- [change] The VisualProspecting ore layer is no longer rebuilt every second with nothing new, and draws only the veins near what is shown

## World map
- [fix] Follow: the map keeps the player in the middle as they walk; dragging it pauses following until `Space` or the button
- [new] 2D map and the map without plants: the biome under the cursor next to the coordinates at the bottom
- [change] Biome view: the biome's name shows by the cursor instead of at the bottom
- [change] Biome view: no day and night, the biome colors always as by day
- [new] Grid: Default (chunks and regions) or Regions only; the grid button goes off → default → regions only
- [new] The player icon can be hidden completely (settings → Player icon)
- [fix] 3D map: teleport to the floor under the cursor — at a wall onto the floor of that storey, on a roof into the building onto the floor below it (and a separate "Teleport onto the roof")
- [new] 3D map: block recording switch REC under the quality slider, no trip to the settings
- [new] Teleport into another dimension from the map and the waypoint menu: through the mod on the server (operators) or `/cofh tpx`
- [new] "Button layout" with the map buttons settings: classic (the default, as before: screens and a mod layers menu on the left, the name in the middle, view switches on the right) or modern (the new one: the name on the left, view switches in the middle, screens on the right, mod layers as a bar of switches on the left of the map, the zoom in the footer)
- [new] The welcome window asks for the map's button layout before the language: a picture of each, classic and modern
- [change] Mob icons and player heads grow up to a block's size when zoomed in (up to 6 times their usual size) instead of stopping at 1:1
- [fix] The frame around mob icons is now exactly 1 screen pixel ("Frame width" counts screen pixels) and even on every side: it was in GUI pixels (2 to 4 on screen), and between pixels it came out 1 on one side and 2 on another
- [fix] Cave mode no longer always shows flowers and grass: with the map without plants, the cave layers are drawn without them too (places mapped before clear up once mapped again)
- [fix] Area selection when claiming chunks or loading regions: dragged left or up, it no longer slides after the cursor once at its limit
- [fix] Area loading with caves (Ctrl+Shift+LMB) maps all 16 cave layers of the world's height, not only those up to the chunk's highest blocks
- [new] Ctrl+F on the map puts the cursor into the search (when it shows), its text selected, to type at once
- [new] Quick waypoint presets: the "Quick waypoint" entry of the right-click menu opens a list of presets (its look, name and group set once — e.g. for ores or miners); a click puts one down where you clicked, only the coordinates are new. The pencil edits a preset, "New preset" adds one; presets are the same in every world

## Mod layers
- [change] "Add-on layers" are now called "Mod layers", and their options are gathered in a new Mod layers settings tab: the vein names, the minimap's mod layers (was "Own mod layers") and how tracked veins and nodes look
- [change] Ore vein, Thaumcraft node and power failure icons grow with the zoom (up to 64 pixels) instead of staying smaller than a block
- [new] Ore vein icons get a thin black frame right around them, so they stand out on any map, the Nether too
- [fix] Ore vein names on the map no longer run into each other: the name of the vein under the mouse takes its place first and the ones in its way are left out
- [new] Like waypoint names: "Vein names" (always / on hover) and "Vein name width"
- [new] Del over an ore vein marks it depleted (again: not depleted)
- [fix] Underground fluids: zoomed in close, each chunk's amount shrinks to fit the chunk (dropping the L/Op unit when it would be too small to read) instead of running into its neighbours, and the field's range moves onto the name's line

## Settings
- [change] Switches the world map sets with its own buttons and menus (view, light, caves, grid, layers, mobs, 3D, follow, area loading) are no longer repeated in the settings window; the Entities tab is gone, and Reset all leaves them as they are

## Map data
- [new] A button that refreshes the map (read again from disk, the loaded chunks mapped anew) and one that clears all map data of this world
- [fix] "All dimensions": its 3D map shows that it includes the block pictures the dimensions share (why it is more than the dimensions added up)
- [fix] After deleting a dimension's map it stays picked instead of jumping to "All dimensions"
- [fix] Percentages: a small share shows "<1%", not 0%
- [fix] Empty world folders no longer count in "Worlds"

## Memory
- [fix] Leaving a world or changing dimension no longer keeps the old world in memory: mob and item icons (with their textures), the 3D map's pictures, VisualProspecting's data, the area loading view and the settings' mob preview are let go
- [fix] The 3D map no longer keeps empty regions it was scrolled over, nor leaves the block pictures file open; its learned picture caches are capped
- [fix] The 2D map forgets far empty regions, and the log's debug data when the world is left
- [fix] Server: /wf chunkload no longer leaves chunks loaded for good (a paused job, another dimension, the ring by a player); the team map and teleport keep no old players and worlds after death, logout or server stop

## Waypoints
- [change] 20 death waypoints are kept by default (was 3), up to 100
- [new] With OpenBlocks: the death waypoint goes away by itself once your grave next to it is gone (emptied or broken); this can be turned off in the settings
- [new] The waypoint's beam color is set in the editor on its own (right click on the sample: as the outline)
- [new] "Distance next to the name" setting: in the world the distance is right of the name, not on its own line
- [new] An "Enabled" switch in the waypoint's menu on the map (like "Share with team"); "Share" is now "Share in chat"
- [new] Double left click on the map: on a waypoint opens its editor, elsewhere a new waypoint at the point under the mouse
- [new] Keys work on the open map: U opens the waypoint list, B a new waypoint at the point under the mouse
- [change] "Fade when near" moved to the "Where to show" section
- [fix] On a language change the death group and the death markers are renamed into it at once (no new death needed, no empty groups left)
- [fix] A tracked vein or node shows in the world at any distance: the waypoints' distance limit no longer hides it
- [new] A GregTech power failure can be tracked (right click on the map → "Track"): it shows in the world at any distance like a tracked vein, with a beam as set
- [new] Tracked ore veins and Thaumcraft nodes: their look in the world, marker or marker and beam

## Minimap
- [fix] Stutter at 1:2: no coarse copies of the regions, and the map moves in whole pixels
- [new] The world map's search highlights the same biomes, veins, fluids, power failures and nodes on the minimap

# 0.0.16

## Waypoints
- [new] Team waypoints: share a waypoint with your ServerUtilities team (on servers with the mod) from its editor, the list or its menu
- [new] A waypoint group can share its new waypoints by itself, and all it has with one button
- [new] A teammate's waypoint is tagged with their name; hide it, or save it as your own to edit it: the copy says whose it is a copy of and leads back to the original
- [new] Teammates' waypoints can come hidden: for all groups in the settings, or for one group in the list

## Windows
- [change] "About" window: Navatusein added as a developer

# 0.0.15

## Minimap
- [new] Own settings apart from the world map: the map shown (2D, without plants, topography, biomes), lighting, mod layers, chunk grid, players and mobs
- [new] Keys for each of them (unbound by default); a key gives that part of the minimap its own settings
- [change] Controls: the mod's keys are split into general, world map and minimap

# 0.0.14

## Windows
- [new] Welcome window: what the mod can do, the first steps, who made it and the choice of the mod's language
- [new] "What's new" window, shown after the mod is updated and from a button in the "About" window
- [change] Redesigned "About" window: the author's and testers' avatars, links to GitHub, Boosty and Telegram
- [new] A **Mod language** section in the settings: all of the mod's texts in the chosen language, whatever Minecraft's is

## Map
- [change] Grass, leaves and vines on the 2D map colored by their own biome
- [change] Map without plants: grass blocks in their biome's own color
- [change] The Nether on the 3D map is dim at any time of day, lit by its lava and lamps
- [change] The End is lit as by day
- [new] Copy and paste waypoints on the world map
- [new] Waypoint list: groups pane, waypoint cards, compass arrows and hover actions

## 3D map
- [change] Block pictures are kept from game to game: the map builds faster
- [fix] Blocks with metadata above 15 no longer break the hidden-picture check

## Area loading
- [new] Its own 3D switch, a Stop button, a progress bar and hints
- [fix] `/wf chunkload` sends ForgeMultipart's parts, so microblocks aren't empty

## Other
- [remove] The GregTech ore vein (3x3 chunk) grid mode
