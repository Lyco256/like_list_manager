# 29. 絞り込み条件色を緑・赤・灰色へ統一

## 目的

tag/group filter状態の色を、`含む=緑、必須=赤、排除=灰色` へ統一する。

## 色仕様

- `INCLUDED`: 緑を維持。
- `REQUIRED`: 現在の青から赤へ変更。
- `EXCLUDED`: 現在のオレンジから灰色へ変更。
- `NONE`: 選択container色なし。

27のTree popup、28の選択済み横一覧、filter関連で状態色を使う箇所は1つのcolor mappingを参照し、画面ごとに色定義を複製しない。

dark theme上でlabel/iconが読めるselected content colorも合わせる。赤/灰色という意味を変えない範囲でMaterial向けの濃度を選ぶ。

## テスト

- INCLUDEDがgreen。
- REQUIREDがred。
- EXCLUDEDがgray。
- NONEがtransparent/unselected。
- Treeと選択済み一覧で同じmappingを使う。
- REQUIRED/EXCLUDEDの旧blue/orange定数がfilter状態色として残らない。
