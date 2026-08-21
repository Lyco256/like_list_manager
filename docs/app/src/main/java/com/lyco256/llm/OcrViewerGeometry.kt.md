# `OcrViewerGeometry.kt`

OCR画像ビューアの純粋な座標変換を担当します。

- 元画像サイズとviewportから、CropしないFit表示矩形を算出する。
- 元画像座標の4点polygonをFit矩形へ写像し、zoom/pan変換を同じ中心基準で適用する。
- 画像境界外のpolygonをSutherland–Hodgman方式で画像矩形へclipする。
- NaN、Infinity、面積のないpolygonを表示対象から除外し、他の正常polygonを維持する。
- 1倍かつzoomを伴わない横dragだけをページ切替として扱い、拡大中の横dragをページ切替から除外する。
- Compose Canvasや画像ローダーへ依存しないため、letterbox、viewport変更、zoom/pan、clipの回帰を単体テストできる。
