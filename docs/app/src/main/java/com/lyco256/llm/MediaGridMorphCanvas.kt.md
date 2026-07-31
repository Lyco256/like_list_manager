# `MediaGridMorphCanvas.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/MediaGridMorphCanvas.kt`

## 役割

前段の`MediaGridMorphPreparedPair`とresident prepared indexを、任意progressで描画できるTEST_HARNESS・Compose Test専用の単一Canvasへ変換します。productionのグリッドには接続しません。

## modeと構築境界

- `MediaGridMorphCanvasMode.Disabled`がdefaultで、Canvas、`rememberTextMeasurer()`、画像解決、文字計測、render model構築を行わずreturnします。
- `TestVisible`は`BuildConfig.TEST_HARNESS`を必須とし、直接呼び出す隔離Compose Testだけが使用します。
- unique titleは`titleSmall`＋`SemiBold`、`onSurface`、一行、左右12dpを除いたviewport幅で事前計測します。
- render modelはprepared pairまたはprepared index version、事前計測結果・表示環境が変わった時だけ再構築します。progress／correction更新では再構築しません。

## immutable render model

- slotはstart/end rect、Asset ID、row／column、事前解決済み`MediaGridResidentCanvasPreparedImage?`を保持します。
- headerはstart/end rect・titleと、事前計測済み`TextLayoutResult?`を保持します。
- resident Map lookupは構築時にAsset IDごと一回だけ行い、同じAssetは同じprepared参照を共有します。missはnullのままです。
- prepared pairを超えるslot／headerや、resident indexの無関係なentryを保持しません。Bitmap、MemoryCache.Value、store、request、Painterは保持しません。

## draw

- draw開始時にprogressとcorrectionを各一回読み、四辺を線形補間してviewport originを引きます。
- 画像群だけをviewportサイズの一つのsaveLayerへ描き、異なる画像は`BlendMode.Plus`で`1-progress`／`progress`を加算します。同一Assetは一枚alpha 1、片側・missはそのlayerだけ透明です。
- prepared indexのsquare cropをそのまま使い、slot内clip後に独立丸めした四辺から`IntOffset`／`IntSize`を作ります。
- 画像layer復元後、headerの不透明surface背景を補間rect全体へ描き、band内clipでstart/end文字だけをCrossfadeします。
- Canvas全体をclipし、staleなCanvas寸法、幅／高さ0以下のslot／bandは描きません。pointer input、clickable、semantics actionは持ちません。

draw中はMap lookup、collection生成、resident解決、crop、ImageBitmap変換、text measure、IO、model再構築、Compose state更新を行いません。
# MediaGridMorphCanvas.kt

`MediaGridMorphCanvasMode`は`Disabled`、`TestVisible`、`ProductionVisible`の明示modeを持つ。defaultは`Disabled`で、`TestVisible`だけ`BuildConfig.TEST_HARNESS`を要求する。ProductionVisibleはproduction hostがactive plan中だけ指定する。

Canvas、TextMeasurer、render modelはactive planがcomposeされている間だけ生成される。resident prepared indexだけを読み、Grid viewport内をclipしてstart/end画像とheaderを単一Canvasで描画する。
