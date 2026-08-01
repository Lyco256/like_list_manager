# `MediaGridMorphRowRenderer.kt`

Phase 1 TEST_HARNESS same-surface renderer. It is installed as a draw modifier on the real `LazyVerticalGrid`, suppresses normal cell/header visuals while active, and crossfades source/target content in each current row rect with Placeholder endpoints. It does not compose another grid, Box overlay, or z-index surface.
