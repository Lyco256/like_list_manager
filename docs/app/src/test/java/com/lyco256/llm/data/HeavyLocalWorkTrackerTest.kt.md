# `HeavyLocalWorkTrackerTest.kt`

`HeavyLocalWorkTracker` の単一処理の開始/終了、2処理の重複中に1件だけ終了した場合のactive維持、例外時の必定解除をJVM単体テストします。

また、network待機とUI表示準備はtrackerに登録せず、重いローカルpersist区間だけをactiveにする境界を確認します。
