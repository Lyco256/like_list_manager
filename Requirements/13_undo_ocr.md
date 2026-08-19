# 13. OCR保存をUndo対応

## 目的

OCR文字列の手動保存を、OCR以外の投稿情報へ影響させずUndoできるようにする。

## 実装

- 保存前の `ocrText` と `ocrUpdatedAt` をUndo payloadへ保持する。
- 通常保存では新しいOCR文字列と新しい更新時刻を保存する。
- Undoでは `ocrText` と `ocrUpdatedAt` の2fieldだけを保存前へ戻す。
- OCR認識処理そのものはUndo対象にしない。ユーザーが「保存」してDBへ確定した時点だけをUndo対象とする。
- 操作開始時に旧Undoをinvalidateし、保存成功時だけ新slotを作る。
- OCR保存中またはUndo中にlikeCount等が変わっても巻き戻さない。
- 前回保存値と同じ内容で、DBとして変更が発生しない場合は不要なUndoを作らない。

## テスト

- OCR保存→UndoでtextとupdatedAtが両方戻る。
- 初回保存で元のupdatedAtがnullでも正しく戻る。
- likeCount、summary、tag relationは不変。
- OCR認識だけではUndo slotを作らない。
- 保存失敗時は新slotを残さない。
