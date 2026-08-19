# `app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt`

隔離された `com.lyco256.llm.test` 上で、`PostStorageManager` の起動時復旧を検証するInstrumentationテストです。本番packageのDB、画像、Preferencesには触れません。

主な検証内容:

- 保存先移動の `copying` フェーズ中にプロセスが終了した状態をSharedPreferencesと `.moving` 一時DB/画像で再現すること
- 次回起動相当の `PostStorageManager` 生成時に、一時DB、WAL/SHM、一時画像ディレクトリが削除されること
- migration状態のPreferencesが消去され、通常の内部保存先DBを利用可能な状態へ復旧すること
- `switched` フェーズ後に切替先を開けない状態を再現し、元の内部保存先へ戻してmigration状態を消去すること
- 起動直後の使用容量は未計算として `usedBytes = null` になり、refresh後にDBと画像だけを管理対象容量へ含めること
- 内部SharedPreferencesなどのユーザーデータを追加しても、投稿データ保存先の使用容量が増えないこと
- 保存先移動時にversion 9 DBの永続Undo slotがDB本体と一緒にコピーされること

変更時は `scripts/run-safe-integration-check.cmd` で、同じ実機上の本番package metadataが前後不変であることも合わせて確認します。
