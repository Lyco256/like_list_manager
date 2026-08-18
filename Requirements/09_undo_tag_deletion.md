# 09. タグ削除をUndo対応

## 目的

タグをDBから即削除しつつ、直前1回のUndoで同じID・属性・全clip relationを正確に復元する。

## 実装

削除前に1つのpayloadへ保存する。

- `TagEntity` 全field
- そのtagに紐づく全 `ClipTagEntity`

操作開始時に旧Undoをinvalidateする。snapshot取得、新Undo row保存、tag DELETEを整合したRoom transactionで確定し、tag削除に伴う既存CASCADEでclip_tagが消えてよい。

Undoでは次だけを行う。

1. 同じtag ID、name、color、parent、sortOrder、timestampsを復元。
2. snapshotしたclip_tagだけを元の `createdAt` で復元。

他tag、他relation、clip本体を変更しない。

## 安全性

- 復元対象IDが予期せず別nodeに使われていた場合は上書きせずUndo失敗としてslotを保持する。
- relation復元先clipが存在しない異常時に別clipへ付け替えない。
- 部分復元を確定しない。

## テスト

- 複数clip relationを持つtag削除→Undoでtagと全relationが同じID/createdAtで戻る。
- 別tagのrelationは不変。
- parent/sortOrder/color/timestampsが復元される。
- delete/restore途中失敗で不完全状態を成功扱いしない。
- ID衝突時に既存nodeを上書きしない。
