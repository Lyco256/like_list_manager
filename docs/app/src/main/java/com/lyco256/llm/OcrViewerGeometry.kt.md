# `OcrViewerGeometry.kt`

OCR画像ビューアの純粋な座標変換を担当します。

- 元画像サイズとviewportから、CropしないFit表示矩形を算出する。
- 元画像座標の4点polygonをFit矩形へ写像し、zoom/pan変換を同じ中心基準で適用する。
- 画像境界外のpolygonをSutherland–Hodgman方式で画像矩形へclipする。
- NaN、Infinity、面積のないpolygonを表示対象から除外し、他の正常polygonを維持する。
- 1倍かつzoomを伴わない横dragだけをページ切替として扱い、拡大中の横dragをページ切替から除外する。
- 前回の構造化結果に存在した現在assetが新しい結果から消えた場合だけ、表示可能なpreview assetへ安全にページ補正する。
- 表示済みFit・letterbox・zoom・pan後のpolygonを同じ座標系でhit testし、内部候補は画面面積の小さいpolygon、外部候補は12dp以内の最近傍polygonを選択する。clip済み形状と不正polygonを表示と同じ条件で扱う。
- Compose Canvasや画像ローダーへ依存しないため、letterbox、viewport変更、zoom/pan、clipの回帰を単体テストできる。
