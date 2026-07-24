# `MediaGridSteadyLoadControllerIntegrationTest.kt`

隔離TEST_HARNESSでcontroller入力から状態snapshotまでを検証する。

- fake metadata preparer and bitmap gateway
- cache-hit request suppression and mixed cache/miss completion
- visible cache eviction requeue, next-candidate fallback, and frame/invalidation token races
- metadata null retry and terminal failure
- 1,000 cache-hit assets without persistent `Pending`
- fixed-seed 1,000-operation state-machine checks with `assertConsistentState()`

本番DB、元画像、JPEG、RGB_565 pack、OAuth設定は使用しない。
