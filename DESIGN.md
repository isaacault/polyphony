# Polyphony design language

Polyphony's look is dense but calm, artwork-forward, instant, and animated just enough to
feel physical. Everything below is a rule unless marked "prefer".

## Foundations

- **Material 3 Expressive** via `PolyphonyTheme` (`core:ui`). Dynamic colour (Material You) by
  default; a user-chosen seed colour otherwise; optional pure-black dark mode.
- **Edge-to-edge always.** Content draws behind the status and navigation bars. Use
  `WindowInsets` padding, never hard-coded top/bottom spacing.
- **System font, tight headings** (`PolyphonyTypography`). Titles are SemiBold; body is default.
- **Rounded everything** (`PolyphonyShapes`, `ArtworkShapes`): list thumbs 10 dp, grid tiles
  16 dp, hero art 24 dp, player art 28 dp. Cards use `MaterialTheme.shapes.large`.
- **Spacing grid** from `Dimens`: 16 dp screen gutters, 24 dp between sections, 12 dp between
  items, 8 dp tight.

## Artwork-forward

- Artwork is the hero on Album, Artist and Playlist screens: a large image at the top with a
  vertical gradient from the artwork's dominant colour into `surface`, and a title that collapses
  into the top app bar as you scroll.
- The **Now Playing** screen re-themes itself from the artwork (`ArtworkTheme`) when
  `artworkColorsInPlayer` is on: background is a soft vertical gradient of the extracted
  colours over `surface`, optionally with a heavily blurred, dimmed copy of the artwork behind it.
- Placeholders: a tonal `surfaceContainerHigh` rounded rect with a music-note glyph, never a
  spinner. Images fade in (Coil crossfade).
- Always request artwork at the size you render (`artworkUrl(id, sizePx)`), so lists stay light.

## Lists and grids

- Lists are `LazyColumn`/`LazyVerticalGrid` with stable `key`s and `contentType`s.
- Track rows: 56 dp artwork (or track number when inside an album), title (1 line), subtitle
  "artist · album" (1 line), trailing duration. Long-press opens the options sheet; there is no
  overflow button. Explicit/lossless badges are small tonal chips. The playing track shows an
  animated equaliser glyph instead of its number.
- Song rows outside the queue are wrapped in `TrackSwipeRow`: each physical direction runs the
  action from Appearance → Gestures (default left-to-right adds to the queue). The row follows
  the finger with a rubber band, commits at ~30 % of the width or on a fling, then springs home;
  a short snackbar with Undo confirms. A direction set to Nothing leaves the drag unconsumed so
  pagers underneath still page.
- Grids auto-fit `Dimens.minGridTile`; cards have no elevation, just shape + tonal container.
- Library tabs: Artists / Albums / Songs / Playlists / Genres with a sticky sort/filter row
  (grid-list toggle, sort menu, "shuffle all"). Alphabet fast-scroller on the right for A–Z sorts.
- Pull-to-refresh triggers a library sync; sync progress shows as a thin linear indicator under
  the app bar, never a blocking dialog.

## Player

- **Mini player** docks above the bottom bar: artwork, title/artist marquee, play/pause, next, and
  a thin progress line along its top edge. Swipe up or tap to expand; swipe left/right to skip.
- **Now Playing** is a full-screen sheet driven by `AnchoredDraggable` with a spring; it follows
  the finger, scrims the content behind, and the mini player crossfades into the header.
  Artwork is a `HorizontalPager` (swipe to skip). Below: title (bold), artist (tappable), a wavy
  or slim slider with elapsed/remaining, then controls: shuffle · previous · play/pause (large
  tonal FAB that morphs square⇄round) · next · repeat. A secondary row: favourite, lyrics,
  queue, cast, output quality chip, overflow.
- A **quality chip** shows the active transcode decision ("Opus 128k · Mobile data rule") and is
  tappable to open Streaming settings. When latency capping is active it turns into a warning
  tonal chip with "Restore quality".
- Queue is a bottom sheet with drag handles (reorderable) and swipe-to-remove.
- Lyrics: synced lines scroll and highlight; tap a line to seek.

## Motion

- Screens switch instantly, forward and back; there is no screen-to-screen transition and no
  setting for one. Motion belongs to surfaces the finger drives, not to navigation.
- The Now Playing sheet always moves with the finger (`AnchoredDraggable` + spring) and is the only
  animated navigation surface.
- Inside screens prefer `animateContentSize`, `AnimatedVisibility` and `Crossfade` over abrupt
  changes; springs for anything touch-driven. Durations 120 to 300 ms.
- Haptics (`LocalHapticFeedback`) on: like/unlike, sheet snap, reorder pickup/drop, long-press,
  swipe-action threshold.
- Every navigation destination must paint an opaque background; a transparent root ghosts the
  previous screen through any transition.

## Performance rules

- Collect flows with `collectAsStateWithLifecycle`; hoist state to ViewModels; expose
  `StateFlow<UiState>` built with `stateIn(WhileSubscribed(5_000))`.
- UI state lists are `ImmutableList`s; models from `core:domain` are stable via the compiler
  config. Avoid lambdas capturing unstable objects in list items — pass ids.
- Heavy work (palette extraction, sorting) off the main thread; palette results cached per URL.
- No blocking network in composables. Local Room data first; remote refresh in the background.
