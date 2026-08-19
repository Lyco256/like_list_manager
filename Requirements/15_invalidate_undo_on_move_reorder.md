# 15. タグ移動・並べ替え時のUndo破棄

## 目的

Undo通知を出さないタグ/グループの移動・並べ替えでも、「Undoできるのは直前のユーザー編集1回だけ」という整合性を守る。

## 実装

以下の明示的ユーザー操作を開始する直前に、共通Undo coordinatorのinvalidateを必ず呼ぶ。

- nodeの別parentへの移動
- drag/dropによるparent変更
- 同一parent内の並べ替え
- `moveNodeToParentAtSlot`
- `reorderSiblings`
- それらへ到達するUI経路

これら自身のUndo payloadや完了Undo通知は作らない。

新しい移動/並べ替えが失敗しても、開始時に破棄した古いUndoを復活させない。

X同期、likeCount更新、API使用量等の内部処理にこのinvalidateを流用しない。

## テスト

- Undo可能操作の直後にtag移動を開始すると旧slotが消える。
- reorderでも同じ。
- move/reorder失敗でも旧slotは復活しない。
- move/reorder成功後に新Undo slotは作られない。
- 内部DB更新ではslotが消えない既存テストを維持する。
