# `MediaGridSteadyLoadControllerTest.kt`

`MediaGridSteadyLoadController`の固定契約をローカルUnit Testで検証します。

- startup状態の順序
- 50ms tick、metadata 2件、request 1件、completion 4件、同時request 2件の固定値
- 初期推定範囲＋前後6行と96件・24MiB上限
- 初期位置から外側へ進むwarm-up順序
- 表示範囲＋前後1行のactive bitmap window
- viewport anchorのcontent equality
- active window外のUI load state破棄

実request、Compose表示、スクロール、候補fallback、preview回復は既存の隔離Integration Test群とともに安全スクリプトから検証します。
