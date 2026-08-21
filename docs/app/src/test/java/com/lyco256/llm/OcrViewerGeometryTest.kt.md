# `OcrViewerGeometryTest.kt`

OCR3の画像座標基盤を検証します。

- 同一比率、横長／縦長画像のletterboxを含むFit変換。
- zoom/pan後の画像点とpolygon点の共通変換。
- viewport変更時のFit再計算。
- 境界外polygonのclipと斜め形状の維持。
- NaN polygonの除外と正常polygonの継続。
- asset IDによる構造化結果の対応。
- Fit時だけのページswipe判定と、zoom中のページ切替抑止。
