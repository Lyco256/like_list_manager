# MediaGridMorph.kt

## 2026-07-31 UI・handoff correction

- Morph slots use explicit `Image`/`Placeholder` endpoints and resolve resident misses to Placeholder during render-model construction.
- Production readiness validates bounded geometry and identity without rejecting the entire pair for a missing resident image.
- Focal correction is based on the fixed initial pinch center and is zero at progress 0.

`MediaGridMorph.kt`は、列数Morphの描画前に使用する局所計画基盤、2次元focal anchor、release判定を保持する。

## 事前計画

- `captureMediaGridMorphInput()`は現在frameの`MediaGridOrdinalIndex`を使い、visible media ordinalと上下2行だけをmain threadでcaptureする。局所mediaのAsset ID、ordinal、item index、bucket計算用primitive、visible geometryだけを保持し、全frame走査やresident／Bitmap／IO参照を行わない。
- 局所先頭の一つ前のmediaをbucket比較専用に保持し、範囲がbucket途中から始まる場合に偽headerを生成しない。
- `buildMediaGridMorphPreparedPairs()`はproductionとUnit Testで共有する唯一のbuilderで、隣接する増加・減少方向のimmutable `MediaGridMorphPreparedPair`を作る。2列の減少方向と12列の増加方向は作らない。
- pairはsource revision、frame key、from/to列数、viewport、viewport signature、start/target layout、slot template、header band template、media ordinal範囲を保持する。画像、Painter、TextLayout、store、queue、workerは保持しない。
- target layoutとstart overscanは、それぞれの列数に対する`viewport.width / columnCount`をcellのwidth／heightへ使用する。start visible cellだけはcaptureした実測rectを上書きして維持するため、2〜12列のtargetはすべて正方形になる。
- Default並びのbounded範囲がordinal 0以外から始まる場合、target layoutは先頭ordinalのtarget列剰余を維持する。実際に先頭item indexが0より後ろでdataset末尾がviewport下端へ接する場合だけtarget最終行も下端へ揃え、先頭から全件がちょうど収まる状態とは区別する。

## media slot

- `MediaGridMorphSlot`は文字列identityではなく`Long?`の`startAssetId`／`endAssetId`を持つ。
- 対応は局所行番号と行内column位置だけで行い、同じAssetを別の行・columnまで追跡しない。
- 片側にない右端slotはviewport右端の幅0 rectとする。slot順は上から下、行内は左から右で、各snapshotのAsset IDは一度だけ現れる。
- rectは四辺を線形補間し、異なるAssetは`1-progress`／`progress`でCrossfadeする。同一slotの同一Assetだけ一枚をalpha 1で扱う。

## header band

- `MediaGridMorphHeader`はkey、title、全幅rect、直後のmedia ordinal、直後のAsset IDを保持する。
- headerは最初に同じmedia ordinal境界で対応し、残りだけを局所Y順の未使用headerへ対応する。同じheader入力を複数bandへ使用しない。
- keyが変わってもordinal境界が同じなら同一背景band内でtitleをCrossfadeする。追加・削除は片側を全幅・高さ0 rectとし、背景band自体はalphaで消さない。

## idle cacheとstale拒否

- `MediaGridMorphPreparationCache`はframe key、source revision、列数、offsetを含まないviewport signatureをidentityとして、一つのidentityを一回だけ要求する。
- 新しいidentityを要求するとgeneration tokenが更新され、古いframe・列数・viewport・revisionの計算結果は公開できない。

## production readiness

`isMediaGridMorphProductionReady()`はbounded pairの各slotについてstart/end rectのswept boundsとviewportの交差だけを確認する。viewportへ入り得るslotの正の寸法側Assetだけを必須とし、viewport外overscan、zero-size側、null Assetは要求しない。判定はprepared resident indexのO(1) lookupだけを使い、decode、Bitmap copy、IO、ImageRequestは行わない。
- 公開先は`AtomicReference`であり、prepared pair公開だけではLazyGridをrecomposeしない。

## 2次元anchorとsettle基盤

- `MediaGridMorphPlan.select()`はCanvasローカルpinch centerをviewport座標へ変換し、中心を含む有効start slot、なければ中心が最も近いslotを一回選択する。
- anchorはstart rect内の`focalU`／`focalV`をclampせず保持するため、slot外開始でもprogress 0のcorrectionは0になる。
- 旧`MediaGridMorphSession.advanceSettle()`もrelease時progressを保存し、各elapsed fractionをrelease値へ適用する線形補間へ統一した。前frame値への累積補間は行わない。

## 現在のproduction状態

`TagHierarchyUiV2.kt`は初期有効layoutとscroll完全停止後だけbounded captureを行い、slot／header計算を`Dispatchers.Default`へ渡す。二本指操作中は要求せず、pointer処理は現行の`mediaGridColumnCountAfterPinchRelease()`だけを使用する。

`MediaGridMorphInteraction.kt`のcontroller／pointer入力／settle／handoff要求、`MediaGridMorphCanvas.kt`のCanvas、実LazyGrid handoff hostはTEST_HARNESS限定で、productionへ接続していない。resident Canvas、viewport通知、idle anchor、queue、worker、先読み、1frame1枚公開も変更しない。
