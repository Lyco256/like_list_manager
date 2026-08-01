# `MediaGridResidentCanvas.kt`

## 2026-08-01 Phase 2 single surface

- `mediaGridSingleSurface` prepares immutable resident draw commands in `drawWithCache` and uses one draw surface for `Normal`, `Morph`, `RevealCurrent`, and `RevealTarget`.
- The Morph draw branch never draws a second grid or overlay; resident lookup/layout/crop work stays out of `onDrawWithContent`, where only prepared commands, frozen row data, and progress are consumed.

`Disabled`、`Enabled`、`TestVisible`を持つresident画像の単一DrawModifierです。`Enabled`と`TestVisible`は同じ`drawWithCache`エンジンを使い、通常画面は`Enabled`を明示し、既定値は`Disabled`です。`Disabled`ではCanvas、draw-index version collector、ImageBitmap adapter、prepared indexを作りません。

- `MediaGridResidentCanvasImageAdapter`はdraw indexのidentityと`MemoryCache.Value`インスタンスをキーに、既存Bitmapへ`asImageBitmap()`を一度だけ適用します。Bitmapのcopy、decode、pixel read/write、recycleは行いません。
- `MediaGridResidentCanvasPreparedIndex`はdraw index versionが変化した時だけ、eligible entryの既存`MemoryCache.Value`と`ImageBitmap` adapterを使って構築します。正方形ContentScale.Cropのsource offset/sizeもidentityごとに一度だけ計算し、最大300件をimmutable mapで保持します。
- `MediaGridFrameData.assetIdByItemKey`はframe作成時の既存item一回走査でmedia cellだけを登録します。header keyは登録せず、draw中に`itemByKey`のobject取得や型castを行いません。
- `mediaGridResidentCanvas`はLazyGrid自身の一つのDrawModifierで、draw phase内の最新`visibleItemsInfo`を一回だけ順番に走査します。item key→asset ID→prepared画像をO(1) lookupし、既存offset/sizeをdestinationへ適用してGridローカルviewportへclipします。resident missでは何も描かず、clip終了後に`drawContent()`を呼ぶため、見出し・Placeholder・fallback画像・badge・選択overlayは従来どおり描画されます。draw scopeはsnapshot、command List、collection、sort、Rect、crop計算、adapter/store lookup、IO、decode、pack readを行いません。
- productionの`ClassifiedMediaGridContent`はmodeを指定せず、AsyncImage、Placeholder、frame publication、queue、worker、selectionの既存経路を維持します。
