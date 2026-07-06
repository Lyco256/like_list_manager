# `TagColorPalette.kt`

## 概要

タグとグループの色定義を集約するファイルです。

## 主な要素

- `TagColorId`: `standard` を含む 12 色の ID 定義
- `TagColorSpec`: 表示ラベル、ベース色、濃い色、薄い色、選択時文字色
- `TagColorPalette`: UI で使うパレット一覧
- `tagColorSpec` / `tagColor` / `tagGradient`: `colorId` から配色を引くヘルパー
- `normalizedTagColorId`: 未知の値を `standard` に寄せる

## 関連

- `Entities.kt.md`
- `ClipRepository.kt.md`
- `TagColorUi.kt.md`

## 2026-07-04 Update

- Palette ids are now `standard`, `red`, `orange`, `yellow`, `green`, `cyan`, `blue`, `purple`, `pink`, `white`, `brown`, `skin`, and `gray`.
- `lime`, `teal`, and `indigo` were removed.
- `standard` is a lighter gray and the palette colors were brightened for dark-theme readability.

## 2026-07-05 Update

- `gray` was removed from the visible palette and is normalized to `standard` for legacy data.
- `standard` now carries the gray appearance directly.
