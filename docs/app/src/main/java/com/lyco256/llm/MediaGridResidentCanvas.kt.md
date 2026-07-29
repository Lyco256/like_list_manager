# `MediaGridResidentCanvas.kt`

`Disabled`、`Enabled`、`TestVisible`を持つresident画像の単一DrawModifierです。`Enabled`と`TestVisible`は同じ`drawWithCache`エンジンを使い、通常画面は`Enabled`を明示し、既定値は`Disabled`です。`Disabled`ではCanvas、layout collector、draw-index version collector、ImageBitmap adapterを作りません。

- `MediaGridResidentCanvasImageAdapter`はdraw indexのidentityと`MemoryCache.Value`インスタンスをキーに、既存Bitmapへ`asImageBitmap()`を一度だけ適用します。Bitmapのcopy、decode、pixel read/write、recycleは行いません。
- `buildMediaGridVisibleCanvasSnapshot`は`LazyGridLayoutInfo.visibleItemsInfo`だけを読み、frameに存在するmedia cell、viewportと交差する矩形、visible順を保持します。header、viewport外、重複assetを除外します。
- `buildMediaGridResidentCanvasCommands`はlayoutInfoまたはdraw index versionの変更時だけ呼び出され、eligible resident hitだけを中央crop矩形つきcommandへ変換します。
- `mediaGridResidentCanvas`はLazyGrid自身の一つのDrawModifierで、layout後の最新layoutInfoからcommandを構築します。resident画像はGridローカルの`0..size.width` / `0..size.height`へ`clipRect`して先に描き、clip終了後に`drawContent()`を呼ぶため、見出し・Placeholder・fallback画像・badge・選択overlayは従来どおり描画されます。draw scopeは事前構築済みcommandを順番に描画し、store lookup、state更新、collection生成、IO、ImageRequest、decode、pack readは行いません。
- productionの`ClassifiedMediaGridContent`はmodeを指定せず、AsyncImage、Placeholder、frame publication、queue、worker、selectionの既存経路を維持します。
