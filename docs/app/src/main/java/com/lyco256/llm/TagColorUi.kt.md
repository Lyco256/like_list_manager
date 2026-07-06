# `TagColorUi.kt`

## 概要

タグやグループの `colorId` を選ぶための Compose UI をまとめたファイルです。

## 主な要素

- `TagColorPalettePicker`: ダイアログ内に収まる固定2行の色パレットを表示する
- 見出し `色` はダイアログのタイトルと同じ階調で表示する
- 選択中の色はチェック付きで見せる
- 作成ダイアログと名称変更ダイアログから共通利用する

## 関連

- `data/TagColorPalette.kt.md`
- `MainActivity.kt.md`
- `TagHierarchyUiV2.kt.md`

## 2026-07-04 Update

- The picker now renders icon-only color swatches in a wrapping `FlowRow` inside the dialog.
- No color names are shown in the visible UI, and the selected swatch overlays a check mark.

## 2026-07-05 Update

- The picker now shows the subtitle `色` and splits the swatches into two rows.
- `standard` is the gray swatch, and the `gray` choice is no longer shown in the picker.
- The palette layout is now fixed to two explicit rows inside the dialog, with smaller swatches so it stays within the dialog width more reliably.
- Selected swatches are the only ones that render an outer border.

## 2026-07-06 Update

- The subtitle `色` now uses the same dialog-title typography scale and color as `名前を変更`.
- Additional spacing was added between the subtitle and the palette grid.
