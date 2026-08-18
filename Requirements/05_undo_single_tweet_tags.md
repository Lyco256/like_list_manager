# 05. 単一ツイートのタグ変更をUndo対応

## 目的

1件のツイートへ「適用」したタグ変更を、共通Undo基盤で正確に元へ戻せるようにする。

## 実装

- `setClipTags` の更新前に、現在の `clip_tags` をDBから取得する。
- 新しいtag集合との差分を計算し、Undo payloadには次だけを保存する。
  - 今回新規に追加されたrelation
  - 今回削除されたrelationと元の `createdAt`
- 本操作では既存の差分更新を維持し、全relationを無条件に作り直さない。
- Undoでは今回追加したrelationだけを削除し、今回削除したrelationだけを元の値で再挿入する。
- `likeCount`、summary、OCR、asset、他clipのtag relationには触れない。
- 新しい単一ツイートタグ適用を開始した時点で旧Undoをinvalidateし、成功時にこの操作のslotへ置き換える。
- 完了メッセージを共通Undo slotへ保存する。
- UI側がDB成功/失敗を判断できるresult callbackまたは同等の結果伝播を用意する。後続のdraft UIは成功時だけ「適用済み」にできるようにする。

## 競合条件

- Undo待ち中に内部のいいね数更新が走っても、その値を巻き戻さない。
- 同じclipに別の明示的ユーザー編集を行えば旧Undoは先に破棄されるため、古いtag状態を新編集へ重ねて戻さない。

## テスト

- tag追加、tag削除、追加と削除の同時変更をそれぞれUndoできる。
- 元から存在したrelationをUndoで削除しない。
- 削除したrelationの `createdAt` が復元される。
- `likeCount` を変更後にUndoしてもtagだけが戻り、likeCountは維持される。
- DB更新失敗時は新Undo slotを作らず、旧Undoも復活しない。
