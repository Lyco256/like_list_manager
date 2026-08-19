# 28. 絞り込み画面の選択済みタグ条件Row

## 目的

Treeポップアップで選んだtag/groupを、絞り込み画面本体ではコンパクトな横スクロール一覧として編集・削除できるようにする。

## UI仕様

- 選択済みtag/groupだけを横一列のscroll領域へ並べる。
- 各項目に、
  - 名前
  - 現在状態
  - `×` 削除ボタン
  を持たせる。
- 同名nodeを区別できるよう、必要な場合は親階層を含むbreadcrumb/pathをラベルに使う。
- 項目本体tapで属性を巡回する。
- `×` はその条件を即NONE=一覧から削除する。本体tapとclickが競合しない。
- tag数が多くても横scrollし、絞り込み画面の縦領域を無駄に増やさない。

## 選択済み一覧での巡回

NONEは本体tap巡回に含めない。削除は必ず `×` で行う。

- tag: `INCLUDED → REQUIRED → EXCLUDED → INCLUDED`
- group: `INCLUDED → EXCLUDED → INCLUDED`

27のTreeポップアップ内では引き続きNONEを含む巡回を使う。同じ関数を無理に共用して巡回規則を混同しない。

## 状態同期

- Treeで新規選択したnodeは `INCLUDED` で一覧へ現れる。
- Tree内でNONEへ戻したnodeは一覧から消える。
- 一覧の属性変更はTreeを再度開いた時にも反映される。
- `×` 削除もTree側でNONEとして反映される。
- SearchFilterDialogのApply/Cancel semanticsは既存どおり。

## テスト

- 新規tag/groupがINCLUDEDで一覧へ出る。
- tag一覧巡回にNONEが入らない。
- group一覧巡回にREQUIRED/NONEが入らない。
- `×` で削除できる。
- 多数条件を横scrollできる。
- 同名tagをbreadcrumbで区別できる。
- Treeとの双方向同期。
- Dialog Cancelで適用済みfilterへ影響しない。
