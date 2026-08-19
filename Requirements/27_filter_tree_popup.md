# 27. 絞り込みタグ選択のTreeポップアップ

## 目的

絞り込み画面内へ階層を直接展開する現行UIをやめ、タグ/グループを独立したスクロール可能Treeポップアップから選択できるようにする。

## Tree仕様

- 絞り込み画面から `タグを選択` 相当の操作でポップアップを開く。
- 1つの縦スクロール内に、rootから階層をインデントしたTreeとして表示する。
- groupは展開/折りたたみできる。
- group自身も絞り込み条件として選択できる。
- groupの展開操作と条件変更操作を別のtap targetにし、展開しただけでfilter状態が変わらない。
- tagもgroupも複数選択でき、1回選ぶたびにポップアップを閉じない。
- 外側tap/Back/閉じる操作でポップアップだけを閉じ、SearchFilterDialog自体のdraftは保持する。
- 深い階層・大量tagでも全項目へ到達できる。
- 同名tag/groupが別階層にあってもTree上の位置で区別できる。

## ポップアップ内の状態巡回

現在の意味を維持し、NONEを含めて巡回する。

- tag: `NONE → INCLUDED → REQUIRED → EXCLUDED → NONE`
- group: `NONE → INCLUDED → EXCLUDED → NONE`

ポップアップを開き直してもSearchFilterDialog内の現在draft状態を反映する。

## 実装

- 現在のpath/currentParent方式で画面内容を入れ替えるタグ絞り込み部分を置換する。
- 既存 `TagHierarchy` のparent/children情報を使い、Tree flatteningを1か所にまとめる。
- 後続31でも使えるよう、Tree row/expand stateの表示部は「group selectableか」「tag click時処理」を外から指定できる再利用可能な単位にする。ただしこの作業ではAddAll dialog自体は変更しない。

## テスト

- 深さ3以上を展開して最下層tagを選べる。
- groupを選択でき、expandだけでは条件変更しない。
- tag/groupの巡回順が指定どおり。
- NONEへ戻すとdraft mapからその条件が消える。
- popupを閉じてもfilter dialog draftを失わない。
- filter dialog Cancelではpopupで行った変更もDB/適用済みfilterへ反映しない。
- 大量tag fixtureで縦スクロールして末尾へ到達できる。
