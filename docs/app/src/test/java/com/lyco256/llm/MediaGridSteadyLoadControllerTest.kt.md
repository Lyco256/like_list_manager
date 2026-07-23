# `MediaGridSteadyLoadControllerTest.kt`

`MediaGridSteadyLoadController`とsession keyの固定契約をローカルUnit Testで検証します。

- startup状態の順序
- 50ms tick、metadata 2件、request 1件、completion 4件、同時request 2件の固定値
- viewport、下方向1画面、下方向2画面のwarm-up順序
- 先頭の上方向除外、128件・32MiB上限
- 列数・source revisionを除外したsession identityと明示条件変更時のidentity分離
- 表示範囲＋前後1行のactive bitmap window
- viewport anchorのcontent equality
- active window外のUI load state破棄

実request、Compose表示、スクロール、pause/resume、候補fallback、preview回復は既存の隔離Integration Test群とともに安全スクリプトから検証します。
