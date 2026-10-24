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
  - TV focus: scale 1.06 plus a 2 dp ring.
- **ReelCard:** surfaceContainer, radius 16, hairline. Use `spacing = 0.dp` for list cards with dividers.
- **Pill:** meta chip. `accent = true` is filled primary.
- **GlassIconButton:** 40 dp circle.
- **RingAvatar:** primary → tertiary ring.
- **GradientProgress:** primary on surfaceHighest.
- **EmptyState:** icon in a tonal circle.
- **Phone navigation:** a floating, fully rounded tab bar (iOS-style), centred 12 dp above the gesture bar (`Space.barGap`): four icon-only tabs (Home, Search, Downloads, Settings; no labels, each icon named for TalkBack), 56 × 48 dp each (`Size.barTabWidth / barTabHeight`) in a 236 × 60 dp capsule. Surface = `barGlass` (surfaceContainerHigh at 90 %: no blur, so more opaque than `glass`) + hairline + a soft shadow (`barShadow`, the only shadow in the app). Selected = filled icon in the accent on a soft accent pill (`barIndicator`, primary at 16 %) that slides between tabs (`Motion.stateMs`); the rest outlined in `textSecondary`. Hidden on pages stacked on top (title, sources…). Lists clear it with `Space.bottomBarClearance` (system bar + gap + bar). "Add a magnet / .torrent" is the + in the Home header (no FAB: it covered row titles).
- **TV navigation:** tabs across the top. The selected tab is a filled primary pill, the rest plain text; focus lifts the tab and adds a ring.
- **Title page (phone):** a Material top app bar (solid, with the title) appears once the header scrolls away.

### The cinematic layer (`ui/common/Cinema.kt`)

- **Hero:** a full-bleed picture under the status bar (`HeroImage`) with `HeroScrims`: the canvas fades in from the bottom (where the text sits), the top (status bar, floating buttons) and, on TV, the left. Text on a hero is always on the canvas colour, so it reads in light and dark.
  - Phone Home: the week's trending titles as a swipeable hero (two thirds of the screen, `Tokens.Cinema.heroShare`), pages by itself every 8 s unless you're swiping; the picture drifts at half speed (parallax).
  - Title pages cycle through the title's backdrops with a slow cross-fade (and a slow zoom on the phone only).
- **Title logo:** `TitleLogo` shows TMDB's logo artwork instead of the name, fitted in a box (`logoMaxWidth/HeightPhone/Tv`), anchored bottom-start; the name as text when there's none. A logo that wouldn't stand out on the canvas (a white logo in light mode) is drawn in the text colour.
- **Meta line:** `MetaLine` = "★ 8.2 · 2024 · 2h 46m · UA" (rating in the accent colour). It replaces rows of pills on heroes; it only breaks between parts.
- **Ratings (2026-10-24):** posters carry an IMDb pill top-left (brand yellow pill, black "IMDb" box, black score; fixed size); the meta line under a poster is "Film · 2024" (no TMDB ★). Title pages: eyebrow line (caps, letter-spaced) → logo → ratings row (IMDb · Rotten Tomatoes tomato / splat · Metascore square, only what exists) → plot → actions → **Reviews · N** cards (open the full review: sheet on phone, right panel on TV). Brand colours only from `ui/theme/Brand.kt`.
- **Action tiles (phone title page):** under Play, equal-width icon-over-label tiles: My list, Trailer, Sources, Share.
- **Trailers:** Trailer opens YouTube; other videos sit in a "Trailers & extras" row.
- **My list:** the watchlist (synced phone → TV). My list button on the hero and title pages; a "My list" row on Home.
- **Browse:** genres on Search (two rows of chips that scroll sideways) and on the title page; each opens a grid of the genre's most popular titles that loads more as you scroll. Cast opens a person page (photo, bio, their films and shows).
- **Motion:** screens cross-fade with a slight zoom (`Motion.pageMs`, `pageZoom`). On TV, posters and stills lift more when focused (`posterFocusScale` 1.1) and glow in the accent colour.
- **Release names** wrap at dots and dashes (`releaseName`), never mid-word.
- **Sources page:** "Choose a source to play / download", the title (logo) and episode on the hero; a Play | Download switch (each card does what it says); a slim progress line ("Searching · 4 of 7 · 4 results", "Still searching: …"); the **Recommended** card (ranker's pick, glowing ring, facts, tag pills, one big button); then two sideways rows of chips (Sort · quality · type, then provider · language) and the rest as cards: quality badge + type on the left, size and seed bars, provider · languages, tags in the accent colour, the release name (one line), and a round icon on the right showing the action. Seed bars: 3 strong, 2 OK, 1 weak (amber), 0 none.
- **Addon store:** search, then chips (kind · status, then content type · catalog), counts per chip, "Clear" when any is on.
- **Options sheet (long-press / hold OK):** the title art (16:9, or 2:3 for posters) with the name and a quiet line, then icon + label rows (48 dp+ tall; removing is in the error colour). Phone: a bottom sheet. TV: a 360 dp panel on the right over a scrim; focus starts on the first row and stays inside, and Back closes it. Removing a Continue watching card hides it with an **Undo** snackbar (surfaceHighest; on TV the Undo button takes focus).
- **Downloads:** downloads first, then a **Stream cache** section (what played recently; deleted when space is needed). Download cards: Play, Pause/Resume, Delete, More (Change source, Keep as stream cache, Go to title); cache cards: Play, Save as download, Delete.

## 4. Screen rules

1. **Cinematic first.** Title surfaces (home hero, title, sources) show a big image, and the scheme follows it.
2. **One primary action per screen** (Play / Resume / Play best / Add).
3. **Resume beats search.** Play resumes with the same source; Sources appears only when there's a choice to make.
4. **Glass over images, solid on canvas.**
5. **Compact by default.** Phone: three posters across (104 dp); collection and search grids use three columns. TV: posters 112 dp, collections 7 columns, search 8.
6. **TV:** focus is always obvious (ring + lift), focus is remembered per row, and there are no touch-only controls. Everything sits inside the overscan-safe area, the tab bar included (`TvTopBarHeight` = safe margin + button + 8 dp).
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
- the "Match what's on screen" switch;
- a live preview.

## 6. Checking layouts without a device

`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:recordRoborazziDebug --tests 'com.kirtanya.reel.ScreenRenders'` renders every screen × state on the Mac (Robolectric + Roborazzi) into `app/build/screens/`:

- `phone/<state>-<dark|light>-<100|130>.png`: 360 × 800 dp, light and dark, font scale 1.0 and 1.3;
- `tv/<state>.png`: 960 × 540 dp, remote (keyboard) input, so focus rings show.

States include loading, failed, empty, long names, no artwork, many sources / episodes, active and finished downloads. Nothing touches the network: TMDB answers come from `FakeCatalog` (via `LocalCatalog`), downloads / sources / addons from `RenderData` (via `LocalRenderData`), and `FakeArt` paints stand-in posters, backdrops and logos (some bright, to test the scrims).
