# `MediaGridRenderingContractTest.kt`

## 対応ソース

`app/src/test/java/com/lyco256/llm/MediaGridRenderingContractTest.kt`

## 役割

メディアグリッドの軽量化済みproduction経路について、広範囲処理や廃止済み描画経路が再導入されていないことをJVM上の静的契約として検証します。

## Morph準備基盤の確認

- captureが`itemIndexByMediaOrdinal`とbounded ordinal loopを使用し、frame全件走査と`itemByKey`を使わないこと
- pure builderが`Dispatchers.Default`で実行され、pointer処理では現行release helperだけを使用すること
- `MediaGridMorphOverlay`、追加Morph Canvas、`TextMeasurer`、旧`MediaGridMorphWindow`／builderがproduction UIに存在しないこと

既存のviewport ordinal境界、scheduler／publication定数、resident Canvas draw hot path、prepared index keyの静的契約も継続して確認します。
