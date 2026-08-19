# 12. 概要保存をUndo対応

## 目的

ツイート概要の保存をUndo可能にし、同時に既存のEntity丸ごと `@Update` による別field巻き戻しリスクをなくす。

## 実装

- `updateSummary` は `ClipEntity` 全体を `updateClip(clip.copy(...))` する方式をやめ、clip IDとsummaryだけを更新するDAO queryへ変更する。
- 保存前のsummary文字列だけをUndo payloadへ保存する。
- Undoではsummaryだけを前値へ戻す。
- `likeCount`、取得日時、OCR、本文、asset、tag等は一切上書きしない。
- 操作開始時に旧Undoをinvalidateし、summary更新と新Undo rowを整合したtransactionで確定する。
- 前値と新値が同じなら不要なUndoを作らない。

## テスト

- summary変更→Undoで前値へ戻る。
- summary変更後に内部のlikeCount更新を行ってからUndoしてもlikeCountは新値のまま。
- OCRやtag relationも不変。
- 無変更・更新失敗で不要なUndoを作らない。
