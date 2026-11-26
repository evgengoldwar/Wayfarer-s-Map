<!-- wayfarmap:auto — the mod writes this file and updates it with the mod. Delete this line to make the mod leave it alone. -->

# Wayfarer's Map custom themes

This folder (`.minecraft/wayfarmap/CustomThemes`) holds your own themes. A theme changes the **colors** of the whole
mod interface and, if you want, the **pictures** of its parts: windows, buttons, text fields, switches, sliders,
scrollbars, tooltips, the minimap frame and any icon.

A ready-made example is the `example` folder ("Copper (example)" in the settings). Its `theme.json` lists **every**
key the mod understands, with comments.

## Making your own theme

1. Copy the `example` folder and rename it, for example to `mytheme`. The folder name is the theme's name in the
   settings file: use Latin letters, digits, `-` and `_`.
2. Change the name (`"name"`), the colors and the pictures in `mytheme/theme.json`. Draw the texture atlas in
   `mytheme/atlas.png`.
3. Pick the theme in game: **Settings → World map → Theme** (the `<` `>` arrows).

The theme is read again on its own:

* when you **save** `theme.json` or any `.png` in the theme's folder while a screen of the mod is open (checked once
  a second);
* when the theme is **chosen** in the settings (the file and the atlas are read again);
* on **F3+T** (resource reload), which also reloads themes from resource packs.

Problems (a typo in the JSON, a missing atlas file, a sprite reaching out of the atlas) are written to `errors.txt`
in this folder and to the game log. When there are none, there is no such file.

A theme can also be a single file: `CustomThemes/mytheme.json`. Its atlas is then looked for in the `CustomThemes`
folder itself. Folders whose name starts with `_` or `.` are not themes (for example `_icons`).

## theme.json

This is JSON with `//` and `/* */` comments allowed. A comma after the last element is not allowed. Any key can be
left out: its value then comes from the `"extends"` theme.

| Key | What it does |
|---|---|
| `"name"` | Name in the settings: `"My theme"` or per language `{ "en_US": "...", "ru_RU": "...", "uk_UA": "...", "zh_CN": "..." }`. Without it the folder name is shown. |
| `"extends"` | The theme everything not given is taken from: `graphite`, `forest`, `nether`, `ocean`, `end`, `desert`, `snow`, `vanilla` or the name of another theme of yours. Default `graphite`. |
| `"light"` | `true`: a light theme (dark text on a light background). Affects the colors that are worked out automatically. |
| `"bevel"` | `true`: raised edges on panels and buttons, like vanilla Minecraft (for parts without pictures). |
| `"titleShadow"` | Shadow under window titles. By default on for dark themes, off for light ones. |
| `"decor"` | The strip under a window's header: `{ "run": 4, "flows": false, "colors": ["#FF..", ...] }`. The colors take turns, each `run` pixels long; `flows: true` makes the strip move like lava. `null` means no strip. |
| `"colors"` | Colors, see below. |
| `"atlas"` | The atlas file next to `theme.json`, for example `"atlas.png"`. Without it the theme uses the pictures of its `extends` theme (if that one has any). |
| `"sprites"` | Pictures of interface parts cut from the atlas, see below. |

### Colors (`"colors"`)

Format: `"#RRGGBB"` (opaque), `"#AARRGGBB"` (`AA` is the opacity, `00` to `FF`) or `"#RGB"`.

**Base colors.** If the file sets at least one base color (or `light`), the derived colors are worked out again from
them, except those the file sets too.

| Color | Where |
|---|---|
| `screenDim` | Shade over everything behind a mod screen |
| `panel` | Window background |
| `panelAlt` | Second window background: bands, headers |
| `border` | Borders |
| `control` | Buttons and controls |
| `controlHover` | A control under the mouse |
| `controlDisabled` | A disabled control |
| `accent` | Accent: selected, active, lines |
| `accentDim` | Dimmed accent: background of a button that is on |
| `danger` | Destructive action (delete) |
| `success` | Success |
| `text` | Main text |
| `textMuted` | Secondary text |
| `textDisabled` | Text of something disabled |
| `labelBg` | Background of labels on the map |

**Derived colors** (worked out from the base ones when not given): `header` (window header background), `field`
(text field), `fieldText`, `buttonText`, `textBright`, `textSoft`, `accentSoft`, `deep` (lists, previews), `card`,
`cardHover`, `cardHeader`, `cardSelected`, `cardSelectedHover`, `sidebar`, `section`, `chosen`, `dangerDim`,
`dangerDimHover`, `dangerStrong`, `dangerStrongHover`, `rowHover`, `tooltipText`. What each one is for is in the
comments of `example/theme.json`.

## Atlas and pictures (`"sprites"`)

The atlas is a single PNG of any size, with transparency. `"sprites"` maps each interface part to a rectangle of
the atlas:

```json
"button": { "uv": [0, 32, 20, 16], "border": 4 }
```

A part without a picture (or set to `null`) is drawn in the theme's colors. So you can redraw only the buttons and
leave everything else in colors.

Pictures are drawn as a **9-slice**: the corners (`border` in size) stay as they are, the edges stretch along, the
middle stretches both ways. That way a button of any width looks right.

```
 border
 ┌──┬────────┬──┐
 │co│  edge  │co│
 ├──┼────────┼──┤
 │ed│ middle │ed│   ← stretched (or repeated with "tile": true)
 ├──┼────────┼──┤
 │co│  edge  │co│
 └──┴────────┴──┘
```

| Sprite key | Default | What it does |
|---|---|---|
| `"uv": [x, y, w, h]` | required | Rectangle in the atlas (atlas pixels). Can also be given as `"x"`, `"y"`, `"w"`, `"h"`. |
| `"border"` | `0` | Edges that don't stretch: one number, `[horizontal, vertical]` or `[left, top, right, bottom]`. |
| `"scale"` | `1` | Interface pixels per atlas pixel. `0.5` makes an atlas twice as detailed (HD). |
| `"tile"` | `false` | Repeat the edges and the middle instead of stretching them (patterns, bricks). |
| `"center"` | `true` | Draw the middle. `false` draws the frame only. |
| `"tint"` | `"#FFFFFFFF"` | Multiply the picture by a color; the first pair of digits is the opacity. |
| `"recolor"` | `true` for `icon.*`, else `false` | Paint the picture in the part's own color. A white icon then takes the theme's color and the state (under the mouse, disabled). |
| `"frames"`, `"frameTime"` | `1`, `100` | Animation: frames lie under each other in the atlas (each `h` high), `frameTime` is milliseconds per frame. |
| `"width"`, `"height"` | — | Own size for thin parts: width of the scrollbar and the slider knob, height of the slider track and the `decor` strip, size of an icon. |

States are looked up from the most exact to the most general: `button.active.hover` → `button.active` → `button`.
So drawing only `button` is enough, and the other states are optional. The `.hover` state fades in over the normal
picture when the mouse is over the control.

### All interface parts

Sizes are in interface pixels, as the part is usually drawn (buttons and windows come in different widths).

**Windows**

| Picture | What it is | Size |
|---|---|---|
| `screen` | Shade behind a window, over the whole screen (handy with `"tile": true`) | whole screen |
| `panel` | Window background | window |
| `header` | Window header | window width × 30 |
| `header.accent` | Line under the window's title | × 1 |
| `decor` | Strip under the header; repeated along its width (moves if `decor.flows`) | × 2 (`height`) |
| `pill` | Small box on the right of the header (a count, a size) | × 12 |
| `tooltip` | Tooltip, including Minecraft's tooltips in mod screens | fits the text |
| `dropdown` | An open list under its button | fits the list |
| `toast` | A pop-up message at the bottom of a window | fits the text |
| `label` | Labels on the map | fits the text |
| `drag` | A waypoint being dragged with the mouse | — |

**Buttons:** `button`, `button.hover`, `button.active` (on, the selected tab), `button.active.hover`,
`button.disabled`, `button.danger` (destructive action), `button.danger.hover`. Usually 16–20 px high.

**Icon buttons** (map toolbars, the close cross): `icon_button` and the same states (`.hover`, `.active`,
`.active.hover`, `.disabled`). Without them the `button.*` pictures are used. Size 20 × 16.

**Text fields:** `field`, `field.focused` (being typed in), `value` and `value.hover` (the number left of a slider in
the settings; `field` when not given).

**Settings**

| Picture | What it is | Size |
|---|---|---|
| `switch.off`, `switch.off.hover` | Switch that is off (row under the mouse) | 24 × 12 |
| `switch.on`, `switch.on.hover` | Switch that is on (fades in) | 24 × 12 |
| `switch.knob` | Switch knob | 8 × 8 |
| `segment`, `segment.hover`, `segment.selected`, `segment.selected.hover` | Values of a list when all are shown at once | × 16 |
| `selector`, `selector.hover` | A list showing one value with `<` `>` arrows (`button` when not given) | 104 × 16 |
| `slider.track` | Slider track | × 3 (`height`) |
| `slider.fill`, `slider.fill.hover` | Filled part of the track | × 3 (`height`) |
| `slider.knob`, `slider.knob.hover` | Slider knob | 6 (`width`) × 12 |

**Scrollbar:** `scrollbar.track`, `scrollbar.thumb`, `scrollbar.thumb.hover`. Usually 2 px wide; wider with
`"width"`.

**Minimap:** `minimap.frame` (frame of the square minimap; handy with `"center": false`) and `minimap.frame.round` (a
ring picture for the round one). Drawn when the frame is turned on in the minimap settings; the opacity comes from
there too. The thickness is the "frame width" setting or `"width"`.

**Icons:** `icon.<name>`, for example `icon.close`, `icon.settings`, `icon.waypoints`. Each start the mod writes to
the `_icons` folder:

* `icons.png`: every icon of the mod in white, one dot per pixel;
* `icons.json`: ready-made `"icon.<name>": { "uv": [...] }` lines for all icons.

To redraw icons, paste `icons.png` into your atlas (for example below everything else), redraw the ones you want and
copy the lines from `icons.json` into `"sprites"`, adding your offset to `y`. An icon is drawn at its original size;
another size is set with `"width"`/`"height"`. White icons with `"recolor": true` (the default) are painted to match
the theme; draw colored icons with `"recolor": false`.

## Themes in a resource pack

A resource pack can add themes and change the built-in ones:

```
assets/wayfarmap/themes/themes.json         { "themes": ["mytheme"] }   — the pack's list of themes
assets/wayfarmap/themes/mytheme/theme.json  — the theme, same format
assets/wayfarmap/themes/mytheme/atlas.png   — its atlas
```

A pack changes a built-in theme without listing it: `assets/wayfarmap/themes/nether/theme.json`. `"atlas"` can also
be a full resource path: `"wayfarmap:textures/gui/my_atlas.png"`. Pack themes are reloaded on F3+T and when packs
change.

Order: built-in themes → resource pack themes → themes of this folder. A theme with the same name replaces the earlier
one; its `"extends"` defaults to the theme it replaces.

## The chosen theme

The chosen theme is stored by name in `config/wayfarmap.cfg` (`themeName`). If the theme is deleted or its resource
pack is turned off, the mod shows graphite for the time being and goes back to it once it is there again.
