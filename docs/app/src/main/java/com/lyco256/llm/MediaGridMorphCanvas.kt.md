# `MediaGridMorphCanvas.kt`

## Current production contract

- `MediaGridMorphCanvasMode` has only `Disabled` and TEST_HARNESS-only `TestVisible`. There is no `ProductionVisible` mode.
- Production Morph is drawn by the LazyGrid single-surface modifier. This Canvas is a compatibility visual surface for isolated TEST_HARNESS Compose tests and is not connected to the production host.
- Missing required `Media` images are incomplete claim inputs and are not silently converted to Placeholder by the production row renderer.

## Role

The file converts a prepared Morph pair and resident prepared index into a bounded immutable Canvas render model for TEST_HARNESS tests. It resolves slot images, header text layouts, interpolated rectangles, and crossfade endpoints before drawing; the draw path does not perform image lookup, text measurement, collection creation, or Compose state updates.

## Mode and rendering contract

- `Disabled` is the default and returns without creating a Canvas model or measuring text.
- `TestVisible` requires `BuildConfig.TEST_HARNESS` and is used only by the isolated Canvas Compose tests.
- The model uses explicit Image/Placeholder endpoints, keeps image crop rectangles fixed, interpolates the slot/header geometry, and clips the complete surface to the viewport.
- Production source and the production handoff effects reference the unified `MediaGridResidentCanvas` path instead of this Canvas host.
