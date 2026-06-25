# `run-safe-snapshot-check.ps1`

DBを含むsnapshot rootと画像backup directoryを必須指定し、`SnapshotCompatibilityTest` だけを実行します。元データをAndroid端末へ送らず、テスト内の一時コピーだけを開きます。

標準出力は低出力化されており、成功時は `Preflight`、`SnapshotTest`、最後に `Success` だけを表示します。Gradle出力、元DB hash、画像件数、画像byte数、前後確認の詳細は `build/safe-script-logs/run-safe-snapshot-check/` 配下へ保存します。

失敗時は `Failed:`、`Error:`、`Log:` に加え、元データへの影響有無を確認する `Impact:` を表示します。元DB hash、画像件数、画像byte数が前後一致する場合は、元データが変更されていない要約を出します。
