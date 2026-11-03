# Smooth UI plan (2026-11-04)

Goal (the user): One Stream on the TV should feel as smooth as Netflix / JioHotstar on the same TV, with motion
that feels alive, and the whole app (not the player) should look like a big-company product. Optimise for exactly
this TV (Acer R4: Realtek RTD2851M, Mali-G31 GPU, 32-bit ARMv7, 2 GB, 1920×1080 px window at density 2.0 =
960×540 dp) and the Pixel 8. The player screens are final: don't touch `player/**`.

## 1. Why it lagged (measured on the TV)
Tools: `tool/tv_jank.sh` (D-pad route + `dumpsys gfxinfo`), framestats, perfetto (`atrace view/input/gfx/dalvik`).

| Cause | Evidence | Fix (done in 82e0f8e) |
|---|---|---|
| Three full-screen gradient scrims over the hero | GPU 37 ms/frame with them, 11 ms without | `HeroScrims` bakes them into one small bitmap, stretched |
| Hero picture swaps on every move, inside the scrim stack | GPU 61 ms/frame before | Narrow focus reads; scrims baked |
| Accessibility services on the TV (Button Mapper, One Launcher ×2) make Compose diff semantics every frame | 20–50 ms main thread per frame (`Compose:semantics:*`) | `ComposeA11y` empties Compose's cached service list |
| App mostly interpreted after a sideload | row build (lazy prefetch) 740 ms → 190 ms with `-m speed` | `tool/install_tv.sh` compiles everything ahead of time |
| Whole Home recomposed on each focus move | read of `focused` in the screen's scope | read only in `HomeInfoBlock` / `HomeBackdrop` |

Result: janky frames 100 % → 17 %, median frame 105 ms → 30 ms, UI thread 1.4 ms/frame.
What's left: RenderThread issue ~14 ms + GPU ~10 ms per frame. Every draw op (text, image, clip, border) counts.

How Netflix / Hotstar / YouTube do it (research): one renderer that only animates transform and opacity; a fixed
focus position where rows slide under it; images sized to their slot; a texture budget; nothing animating while
idle; lazily mounted rows; and per-device feature switches. They don't fade full-screen overlays.

## 2. Performance rules (binding for every UI change)
1. **No full-screen gradient `Brush` backgrounds.** Use `HeroScrims` (baked), or bake the same way.
2. **No `blur`, `shadow`, or big `alpha`/`graphicsLayer` layers on TV.** The only full-screen animated layer is the
   hero picture's cross-fade.
3. **Animate only in the draw / layout phase.** Use `graphicsLayer { }`, `drawBehind { }` and `offset { }` lambdas. Never
   read an animating value in composition. Animate transform and opacity only.
4. **Focus-driven state is read in the smallest composable that shows it.** Lambdas pass providers.
5. **Cards are cheap to compose.** On TV: no `LaunchedEffect`, `AnimatedVisibility` or coroutine per card. Lazy
   items get `key` and `contentType`. Cards are about 4 ms each fully compiled; keep them there or below.
6. **Images:** request the TMDB size closest to the slot in px (TV is 2 px/dp). Posters ≤ w342, cards ≤ w500 and
   heroes w1280.
7. **Fewer draw ops on TV:** no text under TV cards. The info block above the rows names the focused title, as
   Netflix and Hotstar do.
8. **Nothing animates while idle** (a shared rule with One Launcher). **Focus never scales** (shared rule): use an
   outline, a fill or a dim/brighten.

## 3. Motion system (cheap, Netflix-like)
Tokens in `Tokens.Motion` (owned by the Home agent):
- Easing "emphasised decelerate" `CubicBezier(0.2, 0, 0, 1)`.
- Focus ring fade 120 ms.
- Row slide (horizontal pivot) 260 ms.
- Column glide (vertical pivot) 300 ms.
- Hero settle 300 ms, then cross-fade 350 ms.
- Page enter: the content fades in over 180 ms (opacity only, on the content layer, not a full-screen overlay).

**Fixed-focus rows (the Netflix feel):** on TV the focused card stays at the row's left start (side padding) and the
row slides under it. The focused row settles at a fixed line under the info block. Both use `BringIntoViewSpec`
with the motion tokens. Key repeat (a held D-pad) must not queue animations; snap or shorten them.

## 4. Work split (agents in their own git worktrees, one owner per file)
- **A, TV Home + shared TV primitives:** `ui/tv/TvHome.kt`, `ui/common/{Focus,Posters,ContinueRow,Services,Ratings,Cinema,Images}.kt`,
  `ui/home/HomeRows.kt`, `ui/theme/Tokens.kt`, `ReelApp.kt` (Coil). It does the pivot rows, the motion tokens, light
  cards, the hero cross-fade and the no-text TV cards.
- **B, TV Title, Search, Collection, Downloads, Settings (TV look):** `ui/tv/{TvTitle,TvSearch,TvApp,TvScreensaver}.kt`,
  `ui/title/**`, `ui/phone/PhoneSearch.kt` (SearchResults is shared), `ui/common/CollectionScreen.kt`,
  `ui/DownloadsScreen.kt`, `ui/settings/**`. It may use A's primitives as they are now and must not edit A's files.
- **C, Phone polish:** `ui/phone/{PhoneHome,PhoneTitle,PhoneApp}.kt`, `ui/sources/**` (phone look only). It must not edit
  A's or B's files.
- **Main session:** merges, runs the design rules, takes TV measurements (before and after), installs, writes the docs
  (`DESIGN_STYLE.md`, `DECISIONS.md`) and commits.
