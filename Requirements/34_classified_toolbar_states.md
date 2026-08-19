# 34. 分類済みtoolbarのボタン状態整理

## 目的

分類済み画面toolbarから不要な「クリア」を除き、filter/sortの適用状態だけを背景highlightで識別できるようにする。

## UI仕様

- toolbarの `クリア` ボタンを削除する。
- filterボタン:
  - 通常状態は背景なし。
  - `filters.hasActiveFilters == true` の時だけhighlight背景。
- sortボタン:
  - `ClassifiedSortState()` のdefaultと同一なら背景なし。
  - defaultから1項目でも変わっていればhighlight背景。
- 表示切替ボタン:
  - Card/MediaGridどちらでも選択highlightを付けない。
  - 常に通常の背景なしbuttonとして表示する。
- disabled時の既存interaction制御、morph中の操作抑止は維持する。
- SearchFilterDialog内部の個別クリア/全クリア機能は今回削除しない。

## 実装

常時 `FilledTonalButton` で背景を出す現状を改め、同じsize/touch targetのままactive時だけcontainerを持つbuttonへする。filter/sort/displayで形状・spacingを揃える。

toolbar専用の `onClear` callback、clear確認dialog等、不要になったstate/引数があれば削除する。ただしfilter dialog内のclear処理まで誤って削除しない。

## テスト

- default filter/sortでは両方背景なし。
- filterを1つ適用するとfilterだけhighlight。
- sort変更でsortだけhighlight。
- defaultへ戻すとhighlightが消える。
- Card↔MediaGrid切替でdisplay buttonにhighlightが付かない。
- toolbarに `クリア` が存在しない。
- filter dialog内clearは引き続き動く。
