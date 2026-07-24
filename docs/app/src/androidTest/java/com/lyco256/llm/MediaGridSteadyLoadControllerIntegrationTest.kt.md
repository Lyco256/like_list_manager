# `MediaGridSteadyLoadControllerIntegrationTest.kt`

隔離TEST_HARNESSでcontroller入力から状態snapshotまでを検証する。

- fake metadata preparer and bitmap gateway
- cache-hit request suppression and mixed cache/miss completion
- exactly 100 and 1,000 cache-hit assets without bitmap requests
- visible cache eviction requeue, next-candidate fallback, and frame/invalidation token races
- metadata null/exception retry limits, all-candidate failure, and paused 300-signal final publication
- 300-asset full-range viewport revisits, resume, and column/frame-key changes
- 1,000 cache-hit assets without persistent `Pending`
- fixed-seed 1,000-operation state-machine checks with `assertConsistentState()`

本番DB、元画像、JPEG、RGB_565 pack、OAuth設定は使用しない。
