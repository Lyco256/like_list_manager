# 08. タグ・グループの名前/色変更をUndo対応

## 目的

タグ・グループの名前変更および色変更を、変更前の値へ戻せるようにする。

## 実装

- rename/edit実行前のEntityから、少なくとも `name` と `colorId` の変更前値をpayloadへ保存する。
- parent、sortOrder等、その操作で変更していない値をUndo時に上書きしない。
- タグとグループをそれぞれ正しいDAOでfield単位に戻せる更新APIを用意する。
- 操作開始時に旧Undoをinvalidateし、更新成功時のみ新slotを保存する。
- 名前だけ、色だけ、名前+色同時変更の全てを同じ操作単位で扱う。
- 値が実質変わっていない場合は不要なUndoを作らない。

## テスト

- tagの名前変更Undo。
- tagの色変更Undo。
- groupの名前/色変更Undo。
- 名前+色を同時変更して1回で両方戻る。
- parent/sortOrderがUndoで巻き戻らない。
- 更新失敗・無変更で不要なslotを残さない。
