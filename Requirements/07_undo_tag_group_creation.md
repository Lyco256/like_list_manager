# 07. タグ・グループ作成をUndo対応

## 目的

タグまたはグループを新規作成した直後に、その作成だけをUndoできるようにする。

## 実装

- `createTag` / `createGroup` が作成したEntityの確定IDを呼び出し元へ返せるようDAO/Repositoryを調整する。
- 作成開始時に旧Undoをinvalidateする。
- 作成成功時に、作成したnode typeとIDをUndo payloadへ保存する。
- UndoではそのIDの作成物だけを削除する。
- 親group、sibling order、既存nodeには触れない。
- 作成後に名称変更・移動・タグ付与等の新しいユーザー編集が始まれば、その時点でこのUndoは先に破棄されるため、後から編集済みnodeを誤って削除しない。
- 完了通知用メッセージはタグ/グループを区別できる内容にする。

## テスト

- tag作成→Undoでそのtagだけ消える。
- group作成→Undoでそのgroupだけ消える。
- 同名が別階層に存在してもIDで正しいnodeだけを消す。
- 作成失敗時にUndo slotを作らない。
- 作成後の別ユーザー編集開始で作成Undoがinvalidateされる前提を壊さない。
