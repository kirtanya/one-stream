# TV clean redesign (2026-11-06)

Goal (the user): the TV app is too busy, "everything is highlighted and nothing is in focus". Make it clean and
to the point, with fewer steps from a title to playback. The player is out of scope. The phone UI stays as it is except where noted.
Binding performance rules stay: `docs/SMOOTH_UI_PLAN.md` §2 (no full-screen gradients except the baked
`HeroScrims`, no blur/shadow, animate in draw/layout only, narrow focus reads, cheap cards, focus never scales).
Target TV: Sony BRAVIA VU3 (Android 14, armeabi-v7a, 960×540 dp), and the Acer R4 with the same specs.

## 1. App shell: side nav instead of the top bar
- A slim **icon-only rail on the left edge** (Search, Home, Downloads, Settings), vertically centred.
  Selected tab: white icon plus a small indicator; others grey. Focus shows a ring/fill (rule 5: focus always visible).
  It never expands into labels; it is quiet so the content leads.
- D-pad Left from a screen's left-most item enters the rail; Right leaves it back to the content.
- Content starts right of the rail (no `TvTopBarHeight` top padding any more). The hero/info block can run under it.
- Hidden on full-screen pages (Title, Collection, Person), as the top bar was.

## 2. Home
- **Remove "On your services" everywhere** (TV and phone): the row, `RowIds.SERVICES`, `ServicesRow`, the settings
  entry. Saved layouts that contain it simply drop it.
- **Card style setting** (TV): shape **Landscape | Portrait** × size **Small | Medium | Large**. Default
  **Landscape, Small** (smaller than today's cards, so more titles show per row). Landscape uses backdrops
  (≤ w500), portrait uses posters (≤ w342). Saved in `HomeLayout`; shown in Settings → Home screen and applied live.
- Offline / error empty state: **Retry** plus **View downloads** (opens the Downloads tab).

## 3. Settings as an overlay
- Settings opens as a **right-side panel over the current screen** (like One Launcher's settings sheet), about 40 %
  of the width, with a solid surface (no blur). The screen behind stays visible so changes such as card style show
  live. Back closes the panel's sub-page, then the panel. The rail's Settings icon opens it; it is not a page any more.

## 4. Title page: cinematic 50/50
- **Right half: the artwork**, full height, edge to edge (backdrop, cropped). It is not a full-screen
  hero: one baked scrim only on its left edge to blend into the canvas.
- **Left half: the content**: title logo (text fallback), one meta line (year · rating · runtime/seasons · genres),
  a 3-line overview, then the **sources**.
  - **Movie:** the sources list is right there on the left (best first, the same rows and ranking as the player's
    Change source panel, `core/player/PlayerSources`). Picking one plays. No separate Sources page, no Play/Download
    buttons. A row's long-press/menu offers Download. "All sources…" at the end opens the full list in the same panel.
  - **Series:** season tabs, then the episode list (number, name, runtime, watched/progress). Picking an episode
    opens the **source panel** (§5) for it. Continue watching / next-up episode is pre-focused.
- Remove the large "recommended"/More like this card block from the top. Cast and More like this go below the
  fold (scroll down), compact and quiet.
- Resume: if there is a saved position, the top source row (or the focused episode) says "Resume 42:10".

## 5. Source panel (overlay, replaces the Sources page on TV)
- A right-side panel over the title page (same look as the Settings overlay and the player's Change source panel):
  header (episode or title name), the sources best first (quality · size · tags / provider · seeders · languages),
  searching state, nothing found + retry. OK plays; menu/long-press → Download. Back closes it.
- Phone keeps its Sources page.
- `Dest.Sources` stays for deep links and the phone; TV flows from the title page no longer navigate to it.

## 6. Work split (worktrees, one owner per file)
- **Shell agent:** `ui/tv/TvApp.kt` (rail, settings overlay host), `ui/tv/TvHome.kt`, `ui/home/**`, `ui/common/{Services,Posters,States}.kt`,
  `ui/phone/PhoneHome.kt` (services removal only), `ui/settings/**` (overlay presentation, card style controls).
- **Title agent:** `ui/tv/TvTitle.kt`, new `ui/tv/TvSourcePanel.kt`, `ui/title/**` (TV parts), `ui/sources/**` (shared row
  bits only; the phone look stays). It must not edit the shell agent's files: TvTitle opens the source panel itself.
- Main session: merge, design rules, renders, release, TV test, docs.
