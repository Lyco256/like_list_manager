# `ExistingMediaGridPreviewBackfill.kt`

## 第10実装の役割

実装8より前に保存されたlocal assetの永続JPEG不足分を、一時ブランチの設定画面から明示操作でenqueueするcontrollerです。

- active clipのassetをDBから読み取り、localPathがあり元画像が存在するものだけを対象判定する
- asset ID昇順で、storeの有効JPEG判定が`VALID`でないものだけをmissing・stale・invalidとして返す
- DB、元画像、JPEGを走査中に変更せず、進捗列やSharedPreferencesを追加しない
- 実装8のschedulerへ専用tag付きで渡し、同じworker/storeで生成する
- 停止は専用tagだけ、再開は再走査後の不足分だけをenqueueする
- WorkManagerのtag状態をUI用に集計する。`ENQUEUED`は`requiresStorageNotLow`制約待ちとして表示できる

走査とファイル確認は`Dispatchers.IO`で行い、Composeの再描画や毎秒の全件statから分離しています。画面再作成・プロセス再生成時はWorkManagerのtag状態と再走査から復元します。
