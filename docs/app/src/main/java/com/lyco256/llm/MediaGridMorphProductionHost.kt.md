# `MediaGridMorphProductionHost.kt`

## 2026-08-07 immediate reverse handoff

- After a successful handoff, the host keeps the bounded claim asset union protected so an immediate reverse gesture can build a complete Morph model before background preparation republishes the new-column pair.
- The carryover is released as soon as the new-column stable-idle pair is complete. A new claim replaces it, while cancellation and disposal clear it immediately.

## 2026-08-05 exact viewport handoff

- The production host captures target rows, cells, and headers from the real `LazyGridLayoutInfo` with Y converted to viewport-local coordinates.
- It supplies the complete visible viewport geometry to the coordinator after each target scroll/correction. Reveal is entered only after exact row and full-viewport validation succeeds.

## Current production implementation

- `MediaGridMorphProductionHostState` owns the session-scoped interaction controller, handoff coordinator, request/command channel, Morph owner token, and bounded carryover protection.
- `rememberMediaGridMorphProductionHostState` creates the host only for the eligible classified grid and releases controller/protection state when the host is disabled or disposed.
- `MediaGridMorphProductionHandoffEffects` coordinates the existing `LazyGridState`: it observes target frame and visible geometry, applies the bounded target correction, verifies the actual target layout, and acknowledges reveal/rollback/checkpoint completion.
- Production does not add a `ProductionVisible` Canvas or a second grid. Morph is drawn by the existing `mediaGridSingleSurface`; `MediaGridMorphCanvas` remains a TEST_HARNESS-only compatibility surface.
- Active Morph suppresses grid scroll, cell input, toolbar/filter/sort/display actions, and metadata overlays. The terminal state restores them after generation-scoped completion, rollback, cancellation, or lifecycle disposal.
- Source/end assets are protected only for the bounded active plan and immediate-reverse carryover. Scheduler, publication, Coil, and resident-store work remain outside the draw/handoff effect.

## 2026-07-31 compatibility handoff（履歴）

- The TEST_HARNESS compatibility host exercises a real single `LazyVerticalGrid`, delayed target frames, exact row verification, rollback, and scroll/checkpoint suppression.
- It is not part of production rendering; production uses the unified resident/Morph surface and the event-driven handoff effects above.
