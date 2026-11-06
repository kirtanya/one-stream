# Reel design language

Reel is a **Material 3** app with one cinematic idea on top: **the colour comes from the content**. When a screen shows a film or show, the whole Material scheme (surfaces, containers, text, primary, secondary, tertiary) is generated from that title's poster, so the screen takes on the poster's core colour. Without a title on screen, the scheme comes from the phone's own accent colour (Material You), or a colour the user picked. Light and dark follow the system; the TV is always dark.

## 1. Colour (`ui/theme/Accent.kt`, `Tokens.kt`)

- **One seed → one scheme.** `AccentState.build(seed, dark)` runs Google's Material colour algorithm (MaterialKolor: HCT, tonal palettes) with the **Tonal spot** style (what Android uses for wallpaper colours).
- **Black & white (`AccentPreset.Mono`, the default, and the TV's only look):** a hand-made scheme (`AccentState.mono`), not seeded: pure black canvas (#000), neutral untinted greys for raised surfaces (#121212 / #1C1C1C / #262626), white text and white as the accent (black text on the main button), `textSecondary` #B3B3B3. Light mode is the same inverted. Pictures carry all the colour, like Netflix / YouTube on a TV. `AmbientGlow` draws nothing in it. The TV has no Appearance page.
- **Where the seed comes from otherwise (phone):** the user's choice in Settings → Appearance: **System** (`android.R.color.system_accent1_500`, Android 12+), Blue, Teal, Green, Violet, Rose, Amber. The scheme never follows the poster in focus (removed 2026-10-31, see DECISIONS.md: it recomposed the whole screen on every focus move). Only the TV's Afterglow light (`AmbientGlow`) takes the poster's colour, drawn without recomposing.
- **Light / dark:** System (default), Dark or Light on the phone; TV is always dark. Status- and navigation-bar icons follow.
- **Changes:** `AccentHost` swaps the scheme at once when the colour or light / dark changes (no cross-fade).
- **Tokens map to roles** (never hard-code a colour):

| Token | Material role |
|---|---|
| `canvas` | surface |
| `surface1 / 2 / 3` | surfaceContainer / High / Highest |
| `glass` | surfaceContainerHigh at 72 % (controls on images) |
| `barGlass` / `barIndicator` / `barShadow` | surfaceContainerHigh at 90 % / primary at 16 % / scrim at 32 % (floating tab bar) |
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
  - TV focus: the focused button, any kind, turns solid accent (white) with a 2 dp ring 3 dp outside it (`Size.focusGap`); no ripple wash, no scale, no glow.
- **ReelCard:** surfaceContainer, radius 16, hairline. Use `spacing = 0.dp` for list cards with dividers.
- **Pill:** meta chip. `accent = true` is filled primary.
- **GlassIconButton:** 40 dp circle.
- **RingAvatar:** primary → tertiary ring.
- **GradientProgress:** primary on surfaceHighest.
- **EmptyState:** icon in a tonal circle.
- **Phone navigation (2026-11-04):** a docked, full-width tab bar like Netflix / Hotstar: four tabs with icon + label (Home, Search, Downloads, Settings), 56 dp, solid `surface1` with a hairline on top, no shadow. A 28 × 3 dp marker on the top edge slides to the selected tab (`Motion.rowSlideMs`, drawn, no recomposition). Tab to tab cross-fades (180 / 90 ms); pages stacked on top keep the zoom. Hidden on pages stacked on top (title, sources…). "Add a magnet / .torrent" is the + in the Home header.
- **TV navigation:** text tabs across the top (see §4.6); pages fade in over `Motion.pageEnterMs` (opacity of the content only, the old page goes at once).
- **Title page (phone):** a Material top app bar (solid, with the title) appears once the header scrolls away.

### The cinematic layer (`ui/common/Cinema.kt`)

- **Hero:** a full-bleed picture under the status bar (`HeroImage`) with `HeroScrims`: the canvas fades in from the bottom (where the text sits), the top (status bar, floating buttons) and, on TV, the left. Text on a hero is always on the canvas colour, so it reads in light and dark.
  - Phone Home: the week's trending titles as a swipeable hero (two thirds of the screen, `Tokens.Cinema.heroShare`), pages by itself every 8 s unless you're swiping; the picture drifts at half speed (parallax).
  - Title pages cycle through the title's backdrops with a slow cross-fade (no zoom drift; on TV the first backdrop only).
- **Title logo:** `TitleLogo` shows TMDB's logo artwork instead of the name, fitted in a box (`logoMaxWidth/HeightPhone/Tv`), anchored bottom-start; the name as text when there's none. A logo that wouldn't stand out on the canvas (a white logo in light mode) is drawn in the text colour.
- **Meta line:** `MetaLine` = "★ 8.2 · 2024 · 2h 46m · UA" (rating in the accent colour). It replaces rows of pills on heroes; it only breaks between parts.
- **Ratings (2026-10-24):** posters carry an IMDb pill top-left (brand yellow pill, black "IMDb" box, black score; fixed size); the meta line under a poster is "Film · 2024" (no TMDB ★). Title pages: eyebrow line (caps, letter-spaced) → logo → ratings row (IMDb · Rotten Tomatoes tomato / splat · Metascore square, only what exists) → plot → actions → **Reviews · N** cards (open the full review: sheet on phone, right panel on TV). Brand colours only from `ui/theme/Brand.kt`.
- **Action tiles (phone title page):** under Play, equal-width icon-over-label tiles: My list, Trailer, Sources, Share.
- **Trailers:** Trailer opens YouTube; other videos sit in a "Trailers & extras" row.
- **My list:** the watchlist (synced phone → TV). My list button on the hero and title pages; a "My list" row on Home.
- **Browse:** genres on Search (two rows of chips that scroll sideways) and on the title page; each opens a grid of the genre's most popular titles that loads more as you scroll. Cast opens a person page (photo, bio, their films and shows).
- **Motion (docs/SMOOTH_UI_PLAN.md §3, `Tokens.Motion`):** one curve, "emphasised decelerate" `CubicBezier(0.2, 0, 0, 1)`. TV: focus ring fades in 120 ms; rows slide 260 ms under a **fixed focus** (the focused card stays at the row start, Netflix style, `FixedFocusRow`); the focused row glides 300 ms to a fixed line under the info block; a held D-pad shortens slides to 100 ms; Home's info block changes 120 ms after the remote rests (fade + 8 dp lift) and the hero picture 300 ms after, cross-fading 350 ms once loaded. Animations run in the draw / layout phase only (graphicsLayer, drawBehind, offset lambdas). Nothing animates while idle. Focus never scales: a white 2 dp ring 3 dp outside the card (rows on Home; inside the edge on full-width rows), unfocused Home cards dim slightly. Phone: press feedback (`pressScale`), shimmer skeletons while loading, pager dots follow the finger.
- **TV cards carry no text (2026-11-04):** Home, search, collections and "More like this" show artwork only; the focused title's name, facts, IMDb pill and story sit in the info block / one focused line. Fewer draw ops per frame on the TV's GPU, and it's how Netflix / Hotstar do it. Continue watching cards keep a thin progress strip. Episode cards keep "E1 · name".
- **TV performance rules (binding):** see docs/SMOOTH_UI_PLAN.md §2 (no full-screen gradient brushes except the baked `HeroScrims`, no blur / shadow / big alpha layers, cheap cards, `key` + `contentType`, image sizes near the slot). Measure with `tool/tv_jank.sh` on the TV.
- **Release names** wrap at dots and dashes (`releaseName`), never mid-word.
- **Sources page:** "Choose a source to play / download", the title (logo) and episode on the hero; a Play | Download switch (each card does what it says); a slim progress line ("Searching · 4 of 7 · 4 results", "Still searching: …"); the **Recommended** card (ranker's pick; a hairline card on phone, a 2 dp ring on TV, no glow shadow; facts, tag pills, one big button); then two sideways rows of chips (Sort · quality · type, then provider · language) and the rest as cards: quality badge + type on the left, size and seed bars, provider · languages, tags in the accent colour, the release name (one line), and a round icon on the right showing the action. Seed bars: 3 strong, 2 OK, 1 weak (amber), 0 none.
- **Addon store:** search, then chips (kind · status, then content type · catalog), counts per chip, "Clear" when any is on.
- **Options sheet (long-press / hold OK):** the title art (16:9, or 2:3 for posters) with the name and a quiet line, then icon + label rows (48 dp+ tall; removing is in the error colour). Phone: a bottom sheet. TV: a 360 dp panel on the right over a scrim; focus starts on the first row and stays inside, and Back closes it. Removing a Continue watching card hides it with an **Undo** snackbar (surfaceHighest; on TV the Undo button takes focus).
- **Downloads:** downloads first, then a **Stream cache** section (what played recently; deleted when space is needed). Download cards: Play, Pause/Resume, Delete, More (Change source, Keep as stream cache, Go to title); cache cards: Play, Save as download, Delete.

## 4. Screen rules

1. **Cinematic first.** Title surfaces (home hero, title, sources) show a big image, and the scheme follows it.
2. **One primary action per screen** (Play / Resume / Play best / Add).
3. **Resume beats search.** Play resumes with the same source; Sources appears only when there's a choice to make.
4. **Glass over images, solid on canvas.**
5. **Compact by default.** Phone: three posters across (104 dp); collection and search grids use three columns. TV: posters 112 dp, collections 7 columns, search 8.
6. **TV:** focus is always obvious (ring), focus is remembered per row, and there are no touch-only controls. Everything sits inside the overscan-safe area. **Navigation (2026-11-06, docs/TV_CLEAN_REDESIGN.md):** a slim icon-only left rail (`TvRail`, 88 dp: Search, Home, Downloads, Settings), vertically centred; selected = white icon + a 3 × 16 dp marker, the rest grey; ◄ from a screen's left-most item enters it, ► returns to where you were; hidden on title / collection / person pages. **Settings** is a right-side panel (`TvSettingsPanel`, 384 dp, solid, no blur) over the current screen so changes show live; Back closes a sub-page, then the panel. **Home cards:** Landscape | Portrait × Small | Medium | Large (default Landscape Small); no "On your services" row. A title's seasons use text tabs (`TvTab`). ↓ into a row lands on its first item, not the one right below (`SearchResults(rowStartOnDown)`, `EnterRowAtStart` + `Modifier.rowStart()`). **TV title page:** cinematic 50/50: backdrop on the right half edge to edge (one baked scrim on its left edge); left half = logo, one meta line, 3-line story, then for a film the sources list itself (5 best + "All sources…", OK plays, hold OK downloads, "Resume" on the top row) and for a series season tabs + episode list (up-next focused) whose OK opens `TvSourcePanel` (right-side overlay, same rows). No Play / Download buttons, no Recommended card; Trailer · My list · Mark watched, Cast and More like this sit below. The TV never opens the Sources page from a title. No per-season watched line (TV or phone); on the phone the same toggle is the "Seen" action tile.
7. **Nothing squeezes:** rows of chips wrap (`FlowRow`) or scroll sideways (`LazyRow`); long names get `maxLines` + ellipsis; a text column next to buttons always has `weight(1f)`. Check at font scale 1.3.

## 5. Settings

The root is a grouped list:
- **Watching:** Playback, Subtitles.
- **Sources:** Addons, Addon store, Plugins.
- **Devices:** Phone & TV.
- **App:** Appearance, Storage, About.

Each row shows where it stands and opens a nested page. Back goes up a level, and on TV focus returns to the row you came from. Appearance has:
- Theme: a segmented button (phone only);
- colour swatches, with System first;
- a live preview.

## 6. Checking layouts without a device

`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.ScreenRenders'` renders every screen × state on the Mac (Robolectric + Roborazzi) into `app/build/screens/`:

- `phone/<state>-<dark|light>-<100|130>.png`: 360 × 800 dp, light and dark, font scale 1.0 and 1.3;
- `tv/<state>.png`: 960 × 540 dp, remote (keyboard) input, so focus rings show.

States include loading, failed, empty, long names, no artwork, many sources / episodes, active and finished downloads. Nothing touches the network: TMDB answers come from `FakeCatalog` (via `LocalCatalog`), downloads / sources / addons from `RenderData` (via `LocalRenderData`), and `FakeArt` paints stand-in posters, backdrops and logos (some bright, to test the scrims).
