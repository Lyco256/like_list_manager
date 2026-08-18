# 14. ツイート削除の永続Undo

## 目的

ツイート削除はDBへ即反映しつつ、アプリを閉じても直前1件の削除を復元できるようにする。画像を含む実データをUndo可能期間中に失わない。

## 削除前snapshot

対象clipを削除する前に、最低限次を完全に取得する。

- `ClipEntity`
- 全 `AssetEntity`
- 全 `ClipTagEntity`
- 復元に必要なローカル画像ファイル情報

DB外resourceを含むため、削除処理だけは専用のdurable undo stagingを使う。stagingは投稿保存先のimage directoryとは別の、アプリ管理下の永続領域に置き、プロセス終了で消えるcacheだけに依存しない。

既存ローカル画像がある場合は、DB削除前にstagingへ安全にコピーして復元可能性を確保する。コピー完了をサイズ等で確認し、コピーに失敗した画像が1つでもあればDB削除へ進まない。元ファイルを削除してからbackupを作る順序は禁止する。

## DB削除

- 旧Undoを先にinvalidate/finalizeする。
- snapshotとstaging準備が成功した後、Undo row保存と `clips` DELETEを整合するtransactionで確定する。
- `assets` と `clip_tags` は既存CASCADEでDBから即消えてよい。
- UIではDB Flow反映により直ちに一覧・件数から消える。
- Undo slotが残っている間は、復元に必要な画像データを必ず残す。

## 元画像・previewの扱い

- DB削除後、元画像をその場で物理削除しても、slot消滅時まで保留してもよいが、stagingに検証済みの復元copyがあることを前提にする。
- Undo時は現在有効な投稿保存先へ画像を復元する。保存先が削除時から変わっていても復元できるようにし、復元後の `AssetEntity.localPath` は実際の復元先へ合わせる。
- MediaGrid previewは元データではないためbackup必須ではない。削除時に既存previewを消し、Undo後は通常経路で再生成できるようにする。
- Undo slotのdismiss/timeout/overwrite時は、その削除用stagingを削除して物理削除を確定する。削除対象として記録していないファイルは触らない。
- cleanup失敗で別ファイルを消したりDBを戻したりしない。安全に残ったorphanはデータ損失より優先する。

## Undo

1. 同じclip IDで `ClipEntity` を復元する。
2. 必要ならstagingから現在のimage directoryへ画像を復元する。
3. `AssetEntity` を同じIDとmetadataで復元し、localPathだけは実際の復元先と一致させる。
4. `ClipTagEntity` を元のcreatedAtで復元する。
5. 全て成功してからUndo slotとstagingを削除する。

途中失敗時は部分復元を確定せず、Undo情報を保持して再試行可能にする。

## 再起動

削除直後にActivity/プロセスを終了してもDB上は削除済みのままにする。永続slotとstagingは残し、16の通知UIが次回起動時に新しい5秒の復元機会を出せる状態にする。

## テスト

- clip + 複数asset + 複数tagを削除するとDBから即消える。
- staging copy失敗ではDB・元画像が消えない。
- DB close/reopen後も削除済みのまま、Undo情報とstagingが残る。
- reopen後Undoでclip/assets/clip_tags/画像内容が復元される。
- 削除後に投稿保存先を変更した状態でもUndoが現保存先へ復元できる。
- Undo slot上書き/dismissで対象stagingだけがcleanupされる。
- 同名ファイルや外部pathを誤削除しないcanonical path境界をテストする。
- Undo失敗時にrestore dataを破棄しない。
