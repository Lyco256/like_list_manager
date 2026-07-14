# MediaGridMorph.kt

`MediaGridMorph.kt` contains the render-independent foundation for continuous media-grid column morphing.

- `MediaGridMorphSession` models `Idle`, `Tracking`, `SettlingToCurrent`, `SettlingToTarget`, and `AwaitingGridHandoff`.
- A gesture chooses one adjacent target column count only. The transaction is created once after a small direction dead zone, while `MediaGridMorphOverlayMotion.progress` is a separate reversible `0f..1f` value.
- `MediaGridMorphPlan` contains bounded viewport-neighborhood `MediaGridMorphSlot` and `MediaGridMorphHeaderBand` records. The wider column count determines the number of slots; missing right-edge slots use a zero-width Rect at the viewport edge.
- Header records retain both titles and use zero height for added/removed bands. The overlay interpolates both Y and height, so header height changes are reflected in the following row's precomputed target Rect. `MediaGridMorphAnchor` identifies the nearest Media Asset to the pinch center and applies a target Y correction.
- Source revision changes cancel the session to the current column count. Target settle produces one handoff result; tracking never mutates the real grid column count.
- `MediaGridMorphUiState` keeps the transaction, anchor, correction, and handoff-completed flag; progress is never copied into this parent state. Target handoff resolves a stable Asset key, performs one `scrollToItem` and one necessary Y correction, and completes without a later anchor restore.
- The file has no Compose rendering, image loading, file access, DB access, or source-list subscription.
