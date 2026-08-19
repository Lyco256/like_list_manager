# 10. 空グループ削除をUndo対応

## 目的

現行ルールで削除可能な空グループだけを、削除後に同じ階層位置へUndo復元できるようにする。

## 実装

- 現行どおり、子group/tagを持つgroupは削除不可を維持する。
- 空group削除開始時に旧Undoをinvalidateする。
- 削除前の `TagGroupEntity` 全fieldをpayloadへ保存する。
- Undo row保存とgroup DELETEを整合したtransactionで確定する。
- Undoでは同じID、name、color、parentGroupId、sortOrder、timestampsでgroupを復元する。
- siblingや親groupの他fieldは書き換えない。
- ID衝突やparent消失等で安全に復元できない場合はslotを保持して失敗を返す。

## テスト

- 空group削除→Undoで同じID/parent/sortOrderへ戻る。
- 非空groupは従来どおり削除不可。
- sibling orderを不必要に書き直さない。
- delete/restore失敗時にslotを失わない。
