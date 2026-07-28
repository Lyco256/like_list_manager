# `MediaGridResidentCanvas.kt`

TEST_HARNESSまたは明示的なCompose Testからだけ有効化できるresident画像の単一Canvas検証レイヤーです。既定の`MediaGridResidentCanvasMode.Disabled`ではCanvas、layout collector、draw-index version collector、ImageBitmap adapterを作りません。

- `MediaGridResidentCanvasImageAdapter`はdraw indexのidentityと`MemoryCache.Value`インスタンスをキーに、既存Bitmapへ`asImageBitmap()`を一度だけ適用します。Bitmapのcopy、decode、pixel read/write、recycleは行いません。
- `buildMediaGridVisibleCanvasSnapshot`は`LazyGridLayoutInfo.visibleItemsInfo`だけを読み、frameに存在するmedia cell、viewportと交差する矩形、visible順を保持します。header、viewport外、重複assetを除外します。
- `buildMediaGridResidentCanvasCommands`はgeometry snapshotまたはdraw index versionの変更時だけ呼び出され、resident hitだけを中央crop矩形つきcommandへ変換します。
- `MediaGridResidentCanvasLayer`のCanvasは一つだけで、draw scopeは事前構築済みcommandを順番に描画します。store lookup、state更新、collection生成、IO、ImageRequest、decode、pack readはdraw中に行いません。
- productionの`ClassifiedMediaGridContent`はmodeを指定せず、AsyncImage、Placeholder、frame publication、queue、worker、selectionの既存経路を維持します。
