# Reel design language: "Afterglow"

Reel takes the *style* of the reference (a dark social-app shot: night canvas, violet light leak, glass circles, story rings, glowing centre action) but not its layout. What makes Afterglow Reel's own is that **the light comes from the content**: the ambient glow takes the colour of the film or show you're looking at, like light spilling from a projector.

## 1. What we took from the reference

| Pattern in the reference | Afterglow rule |
|---|---|
| Near-black canvas, almost monochrome | `canvas` #08080C. Everything is greyscale except light and accent |
| Violet light leak, top-left | **Ambient glow**: a soft radial light at the top of each screen, tinted by the current title's poster (violet when there's no title) |
| No drop shadows; depth from light and hairlines | Never shadows. Depth = surface steps + 1 px hairlines (white 7 %) + glows behind primary things |
| 24 px cards, 20 px media | Radii: `s` 12 · `m` 16 · `l` 24 (cards, hero) · `xl` 32 (sheets) · pill |
| Round glass icon buttons | `GlassIconButton`: 48 dp circle, `surface2` fill, hairline, outline icon |
| Story avatars with gradient rings | `RingAvatar`: cast, providers, quick picks and collections get a 2 dp violet gradient ring |
| "Follow": tonal pill, violet text | `TonalButton`: `surface2` fill + `accentBright` text, for secondary actions |
| Centre "+" pill with violet glow | `GlowButton`: gradient pill + radial glow. It's the primary action (Play) and the bottom-bar "Add link" |
| Floating dark bottom bar | Floating glass bar; selected = filled white icon + label, others outline grey |
| Clean sans, semibold names, grey secondary | Instrument Sans 600 / 500 / 400 |

## 2. Tokens (`ui/theme/Tokens.kt`)

**Colour**
- Canvas `#08080C` · surface1 `#111116` (cards) · surface2 `#18181F` (glass, tonal) · surface3 `#23232C` (inputs, pressed)
- Hairline `#FFFFFF` 7 % · hairline strong 12 %
- Text `#F4F4F6` · secondary `#A1A1AA` · tertiary `#6B6B75`
- Accent `#8B5CF6` · accent bright `#A78BFA` · accent deep `#6D28D9` · on accent white
- Brand gradient: accent bright → accent deep (135°). Ring gradient: `#C4B5FD` → `#7C3AED`
- Status: good `#4ADE80` · warn `#FBBF24` · danger `#F87171`

**Type**: Instrument Sans
- hero 40/44 600 (TV hero) · display 32/38 600 · title 22/28 600 · heading 18/24 600
- body 15/22 400 · label 14/18 500 · caption 12/16 500

**Space**: 4-pt grid (4, 8, 12, 16, 20, 24, 32, 48). Phone gutter 20. TV safe area 48 × 27.

**Motion**: 220 ms ease-out for state changes. Focus scale 1.06. Ambient colour cross-fades over 600 ms.

## 3. Components (`ui/common/`)

- **AmbientGlow**: radial light at the top of a screen. The colour is extracted from the title's poster (Palette), animated, at about 45 % alpha, and fades into canvas.
- **Backdrop**: an edge-to-edge image that runs under the status bar, with a scrim to canvas at the bottom (and from the left on TV).
- **GlowButton** (primary): gradient pill with a soft glow behind it. One per screen.
- **TonalButton**: surface2 pill with accent-bright text. **GhostButton**: hairline pill, white text.
- **GlassIconButton**: 48 dp circle.
- **Pill**: small meta chip (year, runtime, rating, quality) on surface2 with a hairline.
- **Card**: surface1, radius 24, hairline. Media inside uses radius 20.
- **RingAvatar**: a circle image with a gradient ring and a label under it.
- **GradientProgress**: 4 dp track (surface3) with a brand-gradient fill.
- **SectionHeader**: heading on the left, optional "See all" in tonal text on the right.
- **Focus (TV and keyboard)**: scale 1.06 + a 2 dp accent-bright ring + a violet glow. Touch shows a ripple only.

## 4. Screen rules

1. **Cinematic first.** Every title surface (home hero, title page, sources, player overlays) shows a big image with light spilling from it. No flat grey headers.
2. **One primary action per screen**, always a GlowButton: Play / Resume / Play best / Add.
3. **Resume beats search.** If you've watched something, its Play button resumes with the same source. You only see the Sources list when there's a choice to make.
4. **Glass over images, solid on canvas.** Controls that sit on imagery use glass (surface2 at 70 % + hairline). Controls on canvas use solid surfaces.
5. **Accent is scarce.** Violet appears only on the primary action, rings, the selected state, progress and the light. Everything else is greyscale.
6. **TV**: 10-foot sizes, focus always obvious (ring + glow + scale), remembered focus per row, no touch-only controls. The hero takes the top about 55 %; rows sit below.
7. **Phone**: one-handed. The floating bottom bar is the main navigation, its centre "+" adds a magnet or `.torrent`, touch targets are 48 dp.

## 5. Screens

| Screen | Afterglow treatment |
|---|---|
| Home | Ambient glow from the hero. Paged hero cards (backdrop, title, pills, Play + Details). Continue watching. "Quick picks" ring row (collections). Poster rows with section headers |
| Search | Glass search field, All / Films / Shows pills, recent searches, trending grid when empty |
| Title | Full-bleed backdrop under the status bar. Glow from the poster. Title, pill row, Play/Resume GlowButton + Sources tonal. Where to watch as ring avatars. Season pills. Episode cards with progress / watched. Cast rings |
| Sources | Backdrop strip. "Best match" hero card with glow. Filter pills (4K / 1080p / 720p / Direct / P2P). Source cards |
| Player | Glass status pill, glass "Start over" chip, Up next card with episode still. Accent-coloured scrubber |
| Downloads | Storage card (gradient bar). Active / Done / Stream cache pills. Download cards with gradient progress, speed and peers, glass actions |
| Add link (centre +) | Glass bottom sheet: paste magnet, open `.torrent` file, Stream / Download |
| Settings | Grouped cards: Playback, Subtitles, Sources (addons, plugins), Storage, About |
