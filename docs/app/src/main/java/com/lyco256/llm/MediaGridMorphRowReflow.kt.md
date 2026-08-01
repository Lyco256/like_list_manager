# `MediaGridMorphRowReflow.kt`

Phase 1 TEST_HARNESS planning code. It selects the source row and focal media from the claim-time capture, builds the bounded target row/header plan, interpolates only screen-column row geometry, clamps the target focal row to the scroll positions a real grid can realize, and selects the target focal ordinal for real-grid handoff. Image identity is used only as content input; it is not used to pair start/end slots.
