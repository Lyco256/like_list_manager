# `MediaGridFramePublicationComposeTest.kt`

## Current frame-paced publication contract

- `fakeFrameClockPublishesOnlyOncePerFrameWhileDemandRemains` composes the real `MediaGridFramePublicationRunner` with a fake controller target.
- With publication demand held high, the test advances the Compose frame clock twice and verifies exactly one `publishOneReadyImageForFrame()` call per frame.
- The test does not use a device, database, image files, or production package state.
