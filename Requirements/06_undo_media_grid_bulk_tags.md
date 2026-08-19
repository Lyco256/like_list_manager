# 06. メディアグリッド一括タグ変更をUndo対応

## 目的

メディアグリッド複数選択からの一括タグ追加・削除を、1回の操作単位でUndoできるようにする。

## 実装

- `applyClipTagChanges` 実行前に、対象clip群と対象tag群の現在relationを一括取得する。
- 今回実際に追加されるrelationと、実際に削除されるrelationだけを算出する。
- 削除relationは元の `createdAt` もpayloadへ保持する。
- 一括操作全体を1つのUndo slotとして保存する。
- Undoでは今回の追加分だけを削除し、今回の削除分だけを復元する。
- 選択clip以外、操作対象外tag、内部更新fieldには触れない。
- 0件差分の場合は不要なUndo通知を生成しない。
- 既存の複数選択状態、ADD_ALL / REMOVE_ALLの意味、同一clipIdを複数assetから選んだ場合の重複排除を維持する。

## 性能

対象が多い場合でもclipごとのN+1 read/writeを増やさず、既存DAOへ必要な一括query/transaction APIを追加して処理する。

## テスト

- 複数clipへの追加を1回Undoすると全て戻る。
- 一部clipに元からtagがあった場合、そのrelationはUndoで消えない。
- 複数clipから削除したrelationが元のcreatedAtで戻る。
- 同一clipの複数asset選択でUndo payloadが重複しない。
- 0差分ではslotを作らない。
- 大きめの対象集合でもrelation整合性が崩れない。
