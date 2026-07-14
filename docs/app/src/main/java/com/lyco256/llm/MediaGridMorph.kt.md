# MediaGridMorph.kt

`MediaGridMorph.kt` contains the render-independent foundation for continuous media-grid column morphing.

- `MediaGridMorphSession` models `Idle`, `Tracking`, `SettlingToCurrent`, `SettlingToTarget`, and `AwaitingGridHandoff`.
- A gesture chooses one adjacent target column count only. The plan is created once after the dead zone, and progress is a reversible `0f..1f` value.
- `MediaGridMorphPlan` contains bounded viewport-neighborhood `MediaGridMorphSlot` and `MediaGridMorphHeaderBand` records. The wider column count determines the number of slots; missing right-edge slots use a zero-width Rect at the viewport edge.
- Header records retain both titles and use zero height for added/removed bands. The overlay interpolates both Y and height, so header height changes are reflected in the following row's precomputed target Rect. `MediaGridMorphAnchor` identifies the nearest Media Asset to the pinch center and applies a target Y correction.
- Source revision changes cancel the session to the current column count. Target settle produces one handoff result; tracking never mutates the real grid column count.
- `MediaGridMorphUiState` keeps the session, anchor, correction, and handoff-completed flag in one state update. Completion clears the correction only in the same update that hides the overlay.
- The file has no Compose rendering, image loading, file access, DB access, or source-list subscription.
