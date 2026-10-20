# Reel design language

Reel is a **Material 3** app with one cinematic idea on top: **the colour comes from the content**. When a screen shows a film or show, the whole Material scheme (surfaces, containers, text, primary, secondary, tertiary) is generated from that title's poster, so the screen takes on the poster's core colour. Without a title on screen, the scheme comes from the phone's own accent colour (Material You), or a colour the user picked. Light and dark follow the system; the TV is always dark.

## 1. Colour (`ui/theme/Accent.kt`, `Tokens.kt`)

- **One seed → one scheme.** `AccentState.build(seed, dark, media)` runs Google's Material colour algorithm (MaterialKolor: HCT, tonal palettes):
  - poster seeds use the **Content** style (stays true to the image);
  - system and preset seeds use **Tonal spot** (what Android uses for wallpaper colours).
- **Where the seed comes from**, newest first:
  1. the poster in focus, when "Match what's on screen" is on (default). Any screen that calls `rememberAmbientColor(posterUrl)` registers it via `MediaAccent`. That covers Home (hero / focused poster), Title, Sources and Collection, on phone and TV. Grey posters give no colour.
  2. the user's choice in Settings → Appearance: **System** (`android.R.color.system_accent1_500`, Android 12+, the default), Blue, Teal, Green, Violet, Rose, Amber.
- **Light / dark:** System (default), Dark or Light on the phone; TV is always dark. Status- and navigation-bar icons follow.
- **Transitions:** `AccentHost` waits 120 ms (so scrolling past posters doesn't flash) and then cross-fades every role over 600 ms.
- **Tokens map to roles** (never hard-code a colour):

| Token | Material role |
|---|---|
| `canvas` | surface |
| `surface1 / 2 / 3` | surfaceContainer / High / Highest |
| `glass` | surfaceContainerHigh at 72 % (controls on images) |
| `hairline` / `hairlineStrong` | outlineVariant (55 % / 100 %) |
| `text` / `textSecondary` / `textTertiary` | onSurface / onSurfaceVariant / outline |
| `accent`, `accentBright` / `onAccent` | primary / onPrimary |
| `accentDeep` | primaryContainer |
| `tonal` / `onTonal` | secondaryContainer / onSecondaryContainer |
| `ringStart → ringEnd` | primary → tertiary |
| `danger` | error |

- **Player:** overlays sit on video, so they use `Tokens.Night`: the same seed, always dark.

## 2. Type, shape, size

- **Type** (Instrument Sans on the Material scale):

| Token | Size | Material equivalent |
|---|---|---|
| hero | 32/40 | headline large (TV hero) |
| display | 24/32 | headline small (page titles) |
| title | 20/28 | title large |
| heading | 16/24 | title medium |
| body | 14/20 | body medium |
| label | 14/20 medium | label large |
| caption | 12/16 | label medium |

- **Shape:** 4 / 8 / 12 / 16 / 28 dp and pill. Cards use 16; posters and stills use 12.
- **Size:** buttons 40 dp (large 48 dp). Icon buttons 40 dp. Touch area 48 dp on phones; it's turned off under the TV root (remote only).
- **Space:** a 4-pt grid. Phone gutter 16 dp. 16 dp between sections. TV safe area 48 × 27 dp.
- **Design for the user's phone:** a Pixel 8 with display size raised gives **360 × 800 dp**, and every layout must fit that. The TV is 960 × 540 dp.

## 3. Components (`ui/common/`)

- **ReelButton:**
  - Primary = filled primary, one per screen.
  - Secondary = tonal.
  - Ghost = outlined; also used as unselected chips.
  - Glass = for use on images.
  - TV focus: scale 1.06 plus a 2 dp ring.
- **ReelCard:** surfaceContainer, radius 16, hairline. Use `spacing = 0.dp` for list cards with dividers.
- **Pill:** meta chip. `accent = true` is filled primary.
- **GlassIconButton:** 40 dp circle.
- **RingAvatar:** primary → tertiary ring.
- **GradientProgress:** primary on surfaceHighest.
- **EmptyState:** icon in a tonal circle.
- **Phone navigation:** Material `NavigationBar` (Home, Search, Downloads, Settings) with a pill indicator, plus a FAB for "Add a magnet / .torrent" on Home.
- **TV navigation:** tabs across the top. The selected tab is a filled primary pill, the rest plain text; focus lifts the tab and adds a ring.
- **Title page (phone):** a Material top app bar (solid, with the title) appears once the header scrolls away.

## 4. Screen rules

1. **Cinematic first.** Title surfaces (home hero, title, sources) show a big image, and the scheme follows it.
2. **One primary action per screen** (Play / Resume / Play best / Add).
3. **Resume beats search.** Play resumes with the same source; Sources appears only when there's a choice to make.
4. **Glass over images, solid on canvas.**
5. **Compact by default.** Phone: three posters across (104 dp); collection and search grids use three columns. TV: posters 112 dp, collections 7 columns, search 8.
6. **TV:** focus is always obvious (ring + lift), focus is remembered per row, and there are no touch-only controls.

## 5. Settings

The root is a grouped list:
- **Watching:** Playback, Subtitles.
- **Sources:** Addons, Addon store, Plugins.
- **Devices:** Phone & TV.
- **App:** Appearance, Storage, About.

Each row shows where it stands and opens a nested page. Back goes up a level, and on TV focus returns to the row you came from. Appearance has:
- Theme: a segmented button (phone only);
- colour swatches, with System first;
- the "Match what's on screen" switch;
- a live preview.

## 6. Checking layouts without a device

`./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.ScreenRenders'` renders screens on the Mac (Robolectric + Roborazzi) at phone (360 × 800 dp, light and dark) and TV sizes, into `app/build/screens/`. Network images don't load in renders, so use the phone (USB) for poster-heavy screens.
