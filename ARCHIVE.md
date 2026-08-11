# Project Archive

This document stores implementation history, branch context, requirements, intent, implementation notes, verification, and superseded approaches.

この文書は履歴専用です。通常の実装作業では読まず、現状を把握したうえで「なぜこの形になったか」「どの順序で変わったか」「どのブランチで何を意図したか」を確認したいときだけ参照します。

For ordinary work, read the current-state documents first. Read this document only when implementation order, historical decisions, or superseded approaches are needed.

現行確認の入口は `SOURCE_FILES.md` です。過去の情報を現行文書へ戻さず、新しい履歴は本書末尾へ追記します。

## Append-only entry template

```markdown
## YYYY-MM-DD Implementation name

- Branch: `branch-name`
- Requirements:
- Intent:
- Implementation:
- Verification:
- Result and current impact:
- Superseded or continued:
```

## 2026-08-11 Documentation flow migration

- Branch: `devenv`
- Requirements: Separate current-state documentation from implementation history without losing information.
- Intent: Keep normal implementation work focused on the current code and use this document only for historical context.
- Implementation: The pre-migration full contents of the source documents are preserved below. Current-state documents were reduced to their current sections and entry points.
- Verification: Compare the Git diff and confirm the original documents are present below as complete snapshots.
- Result and current impact: Current-state reading starts from `SOURCE_FILES.md`; historical reading starts from this document.
- Superseded or continued: Historical sections moved here; current architecture, constraints, and safety procedures continue in their original current-state documents.

### SOURCE_FILES.md before migration
Codexは通常、作業開始時に `CODEX_START.md` からこの文書へ来る。変更対象が不明な場合は「変更目的別の入口」だけを見て、対象docsと実ソースへ進む。個別文書一覧は、対象ファイル名が分からない場合だけ使う。

この文書は、全体構成、現状の実装、変更目的別入口、個別docs一覧だけを担当する。禁止事項、完了報告、検証手順は置かない。

## 2026-08-10 media-grid Stable-idle claim fast path

- stable idle preparationはcapture、source anchor／lightweight signature、direction pair、visible focal-rowごとのselected plan／RequiredRenderSet、required Asset/title union、resource readinessを現在viewport一件の`MediaGridMorphStableIdleReadySnapshot`へまとめる。
- prepared image／text Ready公開はbounded membershipだけを更新し、geometry、pair、plan、RequiredRenderSet、exact target indexを再構築しない。text measure対象はSnapshotのrequired title unionを正とする。
- 通常claimはcandidate/current/Snapshotのframe・data・column・viewport・first index・scroll offset・lightweight signature一致後、pinch Yからcached focal entryをprimitive lookupする。full captureはstale時のrequested-direction live fallbackだけに残す。
- TEST_HARNESSはsnapshot/focal/resource/fast/fallback/full-capture counterを記録し、stable-idle claimと同一identityのprepared Ready公開を区別する。

## 2026-08-10 media-grid Morph claim/draw hot path

- Production claimはstable-idle cacheのidentity一致pairを再利用し、一gestureでlockしたdirection一件だけのplan、RequiredRenderSet、render model、Asset保護を構築する。live captureからのpair再構築はcache不一致時のselected directionだけに限定する。
- `MediaGridMorphViewportPlanTemplate`はvisible row→canonical row、media ordinal→target row、exact row/header位置のbounded indexをidle時に構築し、claim selectionはprimitive loopだけで行う。
- Morph textはcurrent bounded pairのheaderだけを計測する。row render modelはcell/header transition typeを保持し、production draw loopはRect/blend helperを生成せずFloat primitiveで描画する。
- TEST_HARNESSはclaim pair、selected plan、RequiredRenderSet、direction別model、text measure、draw helperのcounterを記録する。

## 2026-08-08 media-grid scroll allocation reduction

- 通常のresident描画は`devenv`相当の直接drawへ戻し、pixel単位のscrollごとに可視セル分のdraw command objectを再生成しない。Morph中だけimmutable row modelを描画する。
- viewport境界走査はcontroller通知、永続preview preload、idle Morph準備で一つの軽量signature flowを共有する。preloadとMorph準備は境界更新後の値を使い、scroll snapshotを重複走査しない。
- release settleは100ms。exact handoffと一枚surfaceは維持する。

## 2026-08-07 media-grid scroll/Morph performance

- 通常viewport通知は可視境界・cellサイズだけを使い、可視セル全体のGeometryはpixel単位のスクロールでは構築しない。Geometryは境界変化時のidentity更新と、スクロール停止後のMorph準備captureに限定する。
- `ClassifiedMediaGridContent`はスクロール位置をCompose親で直接監視せず、Morph identityは軽量viewportの境界変化で更新し、完全なMorph Geometryは停止後のcaptureで確定する。single-surfaceのdraw cache invalidationは`drawWithCache`内の`LazyGridState.layoutInfo`読み取りで処理する。
- release settleは現在100ms。claim中のidentityは実LazyGrid captureから更新し、スクロール中の古いidle identityでMorphを誤判定しない。

## 2026-08-05 RequiredRenderSet and exact target handoff

- `MediaGridMorphRequiredRenderSet.kt` is the single selected-plan contract for claim readiness, resident protection, rendering, and missing-image/header checks. It sweeps each cell/header from progress 0 to 1 against the local viewport; offscreen overscan is optional and is reported separately.
- `MediaGridMorph.kt` captures all Y coordinates in viewport-local space, selects one idle focal center per visible source row, and prepares only adjacent target column indexes.
- `MediaGridMorphRowReflow.kt` validates each visible source row against exactly one canonical row by row key and cell ordinal/asset identity. It does not require global visible/canonical row counts to match.
- `MediaGridMorphExactTargetLayoutIndex.kt` computes the target LazyGrid item sequence, exact row ID/first item index/media ordinals/header positions, content height, and achievable scroll bounds without copying images or frame-wide media payloads.
- `MediaGridMorphProductionHost.kt` captures the target viewport in local coordinates. `MediaGridMorphHandoff.kt` scrolls the exact target row, permits at most one Y correction, and verifies every visible target row/cell/header before reveal.
- `MainActivityComposeTest.kt`, `MediaGridMorphTest.kt`, and `MediaGridMorphExactTargetLayoutIndexTest.kt` cover the three representative 4→5 production locations, RequiredRenderSet boundaries, canonical-row matching, exact target geometry, and one-correction handoff behavior.

## 2026-08-02 exact source viewport Morph handoff

- `MediaGridMorph.kt` captures the actual visible LazyGrid row/cell/header rects, item sequence, resident prepared-image identity, and a canonical source-row key. One-pixel same-row height rounding is tolerated while each cell keeps its own captured rect.
- `MediaGridMorphRowReflow.kt` uses visible source rows by row key for source content and uses canonical current-column rows only for bounded overscan/target/validation. Missing or non-one-to-one mapping is fail-closed.
- `MediaGridMorphRowRenderer.kt` draws captured source rects/header rects at progress 0 and uses the frozen prepared image identity/content order. `MediaGridMorphProductionHost.kt` verifies the claim-time LazyGrid index/offset before the one-shot `RevealCurrent` ACK; current-side handoff scrolling/coordinator correction is not used.
- `MediaGridMorphLazyGridHandoffComposeTest.kt` covers the real production LazyGrid claim at a bounded partial viewport, header/cell geometry, source ordering, and prepared image identity. `MainActivityComposeTest.kt` covers the scroll-starting production claim path.

## 2026-08-02 production claim-path correction

- `ClassifiedMediaGridContent` is the production caller: its real `LazyVerticalGrid` captures the current viewport/frame at claim time, builds one resident-backed `MediaGridMorphClaimBundle`, and uses the same grid modifier and `mediaGridSingleSurface` for Normal, Morph, and reveal drawing.
- Production claim validity is based on frame/column/viewport/visible-item geometry and the selected direction's complete plan/model. A missing bundle or incomplete direction never calls `beginPointers`; it enters the canonical release fallback path.
- `MediaGridMorphInteractionController` publishes Morph only for one atomic generation-matched snapshot containing direction, plan, complete render model, protected assets, and `MediaGridMorphDrawMode.Morph`. Reverse-direction preparation may remain incomplete without replacing the visible source grid.
- `MediaGridResidentCanvas.kt` keeps the draw hot path pre-resolved. Generation/draw observations and claim diagnostics exist only behind `BuildConfig.TEST_HARNESS`; production does not allocate or update those traces.

## 2026-08-02 bounded row alignment and single-direction tracking

- `MediaGridMorphCapture` records 2..12 column start offsets from the bounded start ordinal. Headerless grids use ordinal modulo; header-grouped grids count only the preceding same-bucket run within an eleven-item metadata window.
- `MediaGridMorphRowReflow.kt` uses `buildMediaGridMorphRowsForColumnCount()` for source and target. Real visible rows provide geometry/focal selection, while canonical current-column rows provide source content and row correspondence. A failed mapping makes the selected plan incomplete.
- `MediaGridMorphInteraction.kt` locks the first successful claim direction for the full gesture. Opposite-side travel retains the same plan/model/Morph draw mode at progress 0, and only physical up can start locked-direction settle/release.

## 2026-08-01 Phase 1 row reflow（履歴: 現行経路では不使用）

- `TagHierarchyUiV2.kt` keeps production Morph overlay/handoff disconnected and uses `MediaGridLegacyPinch.kt` for one release-time adjacent-column step.
- `MediaGridMorph.kt` captures visible media rows/header rects once at TEST_HARNESS claim. `buildMediaGridMorphRowPreparedPairs()` is the real TEST_HARNESS builder; `MediaGridMorphRowReflow.kt` plans fixed-screen-column row geometry, focal-row selection, header bands, Placeholder endpoints, target scroll clamping, and distance-ratio progress.
- `MediaGridMorphInteraction.kt` selects the row-only plan at direction changes and claim-time recapture; the legacy slot builder remains isolated for compatibility tests.
- TEST_HARNESS のpair未準備時は同じgesture modifierのrelease-time fallbackで列数変更を完了させる。
- `MediaGridMorphRowRenderer.kt` is a same-surface draw modifier on the real `LazyVerticalGrid`; target handoff corrects the real `LazyGridState` row before completion.

## 2026-07-30 TEST_HARNESS 実LazyGrid handoff基盤（履歴: 現行経路では不使用）

- `MediaGridMorphHandoff.kt`はbounded planから一回だけ選ぶtarget anchorと、列変更、target frame採用、item表示、最大3回のY補正、geometry確認、次frame完了、rollbackを管理する純粋coordinatorを所有する。
- target anchorはinteraction slotのend Asset、final pinch centerを含むend rect、最寄りend rectの順で選ぶ。requestはsource data/frame key、expected target frame key、media ordinal、focal位置、維持Canvas位置を固定する。
- `MediaGridMorphLazyGridHandoffTestHost.kt`はTEST_HARNESSだけで一枚の実`LazyVerticalGrid`、full-span header、既存interactive Morph Canvasを接続する。handoff中はuser scrollとanchor checkpointを抑止でき、target frame遅延中とrollback中もCanvasを維持する。
- productionの`ClassifiedMediaGridContent`、`mediaGridPinchToResize`、resident Canvas、viewport、anchor、queue、publicationは変更しない。

## 2026-07-30 TEST_HARNESS Morph gesture tracking／settle（履歴: 現行経路では不使用）

- `MediaGridMorphInteraction.kt`はTEST_HARNESS限定controller、固定pointer ID入力、二本指初期距離基準progress、2次元focal correction、180ms線形settle、immutable exactly-once handoff requestを所有する。
- gesture開始時のprepared pair snapshotを固定し、dead zoneへ戻ってprogress 0、反対方向へ越えた時だけ別pairのplanへ切り替える。pointer update／settle frameではrender model、画像、crop、text、viewport、queueを再準備しない。
- `MediaGridMorphInteractiveTestLayer`だけが既存単一Canvasへ接続する。productionの`ClassifiedMediaGridContent`、`mediaGridPinchToResize`、LazyGrid列数、resident Canvas、viewport、anchor、scheduler、publicationは変更しない。実handoffはTEST_HARNESS hostだけへ接続する。

## 2026-07-30 TEST_HARNESS単一Morph Canvas（履歴: 現行経路では不使用）

- `MediaGridMorph.kt`のtarget layoutとstart overscanは各列数のcell幅を高さにも使う。start visible cellだけは実測rectを維持する。
- `MediaGridMorphCanvas.kt`はprepared pairと`MediaGridResidentCanvasPreparedIndex`から、slot画像参照とheader文字layoutを解決済みのbounded immutable render modelを作る。
- `MediaGridMorphCanvasLayer`は`Disabled`をdefaultとし、`TestVisible`は`BuildConfig.TEST_HARNESS`でだけ使用できる。progress／correctionは一つのCanvasのdraw時だけ読み、画像はviewport単位の一つのoffscreen layerで加算合成し、header背景は不透明、文字だけをCrossfadeする。
- productionの`ClassifiedMediaGridContent`、pinch、LazyGrid、resident Canvas、viewport、anchor、queue、publicationには接続しない。

## 2026-07-30 bounded Morph prepared pair foundation（履歴: 現行経路では不使用）

- `MediaGridMorph.kt`はvisible media ordinal＋上下2行のbounded capture、隣接列数layout、行・column位置slot、media ordinal境界header band、immutable prepared pair、generation付きstale拒否cacheを所有する。
- `TagHierarchyUiV2.kt`は初期有効layoutとscroll完全停止後だけmain threadで局所primitive／geometryをcaptureし、共有builderを`Dispatchers.Default`で実行する。pixel offset、scroll／fling、二本指eventごとには計画を作らない。
- prepared pairは非Compose cacheへ公開するだけで、今回のproduction描画・pinch release・resident Canvas・viewport・anchor・scheduler・先読み・frame publicationには接続しない。

## 2026-07-22 第11実装: retired image pipeline removal

## 2026-07-24 ordinal background queues

## 2026-07-25 frame-paced image publication

- `MediaGridSteadyLoadController.kt` separates worker `states` from Compose-facing `publishedCells`. Only non-image state changes are reconciled immediately after startup; new visible Ready candidates set `framePublicationDemand` and wait for the Compose runner.
- `TagHierarchyUiV2.kt` owns one `MediaGridFramePublicationRunner` per effective controller. It waits on demand, uses `withFrameNanos`, and calls `publishOneReadyImageForFrame()` once per frame. The runner performs no image IO.
- `MediaGridControllerStateSnapshot` is the test boundary for comparing internal Ready state and published Ready state independently.

- `MediaGridSteadyLoadController.kt`はframeごとの`MediaGridOrdinalIndex`と、metadata／Bitmapを分離したordinal BitSet pendingを使用する。background選択は最新snapshotの`centerMediaOrdinal`からnearest ordinalを取得し、urgent queueはFIFO `ArrayDeque`で保持する。
- task objectはbackground pending全件には保持せず、ordinalをworkerへ渡す時だけ構築する。watermark判定前のBitmap pending保持、memory cache hitのrequest省略、anchor変更時の未開始task保持、frame/invalidationのgeneration・token無効化を行う。

- 現行のメディアグリッド画像経路は`MediaGridDirectPreview.kt`、`MediaGridPersistentPreviewStore.kt`、`MediaGridPreviewWork.kt`、`MediaGridPreviewWorker.kt`、`TagHierarchyUiV2.kt`だけで構成する。
- `AppContainer.kt`は共有ImageLoader、`MediaGridImagePreparer`、`WorkManagerMediaGridPreviewEnqueuer`を構築し、画面は`MediaGridPreviewPreloader`を所有する。
- 廃止済みgenerator/store/state/coordinatorのsource、互換wrapper、fake、fixture、専用testは存在しない。端末に残る廃止済みcacheは参照もcleanupもせず、Androidの通常管理に任せる。
- benchmark snapshotは`filesDir/media_grid_previews/v1`の永続JPEGを読み取り専用で取得し、benchmark targetの同じ相対位置へコピーする。local path由来のcache key変換やJPEG生成は行わない。

## 2026-07-19 第6実装: direct preview pipeline

Current media-grid rendering is owned by `MediaGridSessionCoordinator` under `MainViewModel`. It keeps the session's frame, controller, prepared metadata/load states, anchor, and long-lived `LazyGridState` behavior across tabs, settings, card mode, column changes, and source revisions. New sessions show Progress only during the first warm-up; column/source updates keep the old frame visible and atomically publish the replacement. `MediaGridImagePreparer` prepares file metadata, candidates, source identities, and cache keys off composition. Retired cache data and application data remain untouched. Current acceptance evidence is tracked in `TEST_REQUIREMENTS_COVERAGE.md`.

## 2026-07-20 第8実装: persistent JPEG preview generation

- `MediaGridPersistentPreviewStore.kt` generates only the new local asset's `filesDir/media_grid_previews/v1/<assetId>.jpg`; it uses bounds/sample decode, center crop, JPEG quality 80, and same-directory atomic replacement.
- `MediaGridPreviewWork.kt` and `MediaGridPreviewWorker.kt` enqueue fixed-size batches as non-expedited unique WorkManager work with storage-not-low constraint and serial execution. The worker rechecks the asset and current `localPath` before publishing.
- `ClipRepository.kt` enqueues only successful inserted assets with a local path after source image persistence; sync does not await JPEG generation. Clip deletion removes preview files best effort after DB deletion, while storage migration leaves previews in `filesDir`.
- DB schema, original images, existing caches, candidate order, Coil configuration, preload range, grid UI, and Macrobenchmark are unchanged.

## 2026-07 media grid direct preview pipeline

- `TagHierarchyUiV2.kt` owns the classified grid, headers, sorting, selection, pinch column changes, and cell interactions.
- `ClassifiedMediaGridCell` receives a prepared image model and starts `AsyncImage` only from that model. It keeps `ContentScale.Crop`, static cell-local placeholder rendering, and one-step fallback on `onError`.
- `MediaGridDirectPreview.kt` owns the single `MediaGridImagePreparer`. It performs candidate availability, file stat, source identity, and cache-key calculation off composition, reusing same-key/asset/size results and preparing visible cells plus at most one adjacent row from the prebuilt index column.
- `AppContainer.kt` owns the one shared media-grid `ImageLoader`: crossfade is disabled, disk cache is `cacheDir/media_grid_coil_cache` at 128 MiB, memory cache is `min(totalMem / 8, 64 MiB)`, and decoder parallelism is limited to four; the normal controller still limits active requests to two.
- The retired generator, store, scheduling state, cache restore, and viewport coordinator have no source, wrapper, test, or runtime reference. Existing retired cache files are left to Android's normal cache management.
- `MediaGridMorph.kt` supplies the production claim-time capture, bounded adjacent plans, and pure geometry used by the unified single-surface Morph path; the former overlay source is not part of the product path.

# 2026-08-01 Phase 2 production row Morph

- `TagHierarchyUiV2.kt` uses the production gesture path on the real classified `LazyVerticalGrid`; claim order is stop-scroll, real-layout capture, then bounded row-plan/model preparation. The production path has no legacy pinch modifier, second grid, overlay, z-index, or translation.
- `MediaGridResidentCanvas.kt` owns the single `Normal`/`Morph`/`RevealCurrent`/`RevealTarget` draw surface. Resident commands are prepared in cache phase; the draw phase consumes only prepared commands, the frozen row model, and progress.
- `MediaGridMorphRowRenderer.kt` freezes row cells, headers, image endpoints, text layouts, and full source/end protection IDs before visual activation. Same slots crossfade `1-p/p`; new/disappearing right-edge slots use Placeholder endpoints.
- `MediaGridMorphProductionHost.kt` production effects observe the actual target ordinal/row/header geometry, apply bounded one-pixel corrections, perform one underlying-grid draw, and gate reveal/rollback/checkpoint completion. The old visual host is TEST_HARNESS-guarded compatibility code.

# Source Files Guide

## 2026-07-31 production Morph integration

- `TagHierarchyUiV2.kt` connects the normal classified `LazyVerticalGrid` to the shared `MediaGridMorphGestureMode.Production` input and `MediaGridMorphProductionHost`; selection/progress paths remain disabled.
- `MediaGridMorphProductionHost.kt` owns the bounded production request/command handoff, target geometry verification, rollback/cancel paths, checkpoint suppression, lifecycle invalidation, and temporary retained-image protection. It does not render a production Canvas; `MediaGridMorphProductionHandoffEffects` coordinates the existing grid's single-surface draw state and terminal handoff. Morph lock also suppresses toolbar actions and hides grid metadata overlays.
- `MediaGridMorphInteraction.kt` uses direct initial-to-release scale for the one-step fallback when a prepared pair or resident viewport asset is unavailable. Production mode remains enabled on the normal grid even when the Canvas host cannot be created, so this fallback remains reachable. The old `mediaGridPinchToResize` modifier is no longer part of the production source.
- Production acceptance coverage is in `MediaGridMorphLazyGridHandoffComposeTest` and `MediaGridRenderingContractTest`; the existing queue, publication, resident draw, and scroll-anchor paths remain separate.

## 2026-07-29 idle anchor persistence

- `TagHierarchyUiV2.kt` no longer keeps a continuously updated classified-grid anchor state. Anchor persistence observes only `LazyGridState.isScrollInProgress`; the initial `false` and the `false -> true` transition do nothing, and the observed `true -> false` transition captures once.
- Scroll-idle restoration and column handoff are suppressed while their explicit restore work is running. Composition disposal, `ON_STOP`, session replacement, and completed handoff use explicit checkpoint callbacks.
- `captureClassifiedMediaGridScrollAnchor()` scans `visibleItemsInfo` once and uses `MediaGridFrameData.assetIdByItemKey` to exclude headers without intermediate media lists or per-item `Offset` objects. Viewport notification remains in the existing `snapshotFlow` path.
- `MediaGridSessionCoordinator.saveAnchor(sessionKey, anchor)` updates only an existing matching session, skips equal anchors, and does not publish UI state.

## 2026-07-28 resident draw index基盤

## 2026-07-29 resident Canvas draw hot path

- `MediaGridFrameData.assetIdByItemKey` is built during the existing single item scan and contains media keys only; headers remain absent.
- `MediaGridResidentCanvasPreparedIndex` is rebuilt only for frame/store/adapter/draw-index-version changes. It converts eligible retained `MemoryCache.Value` instances to reusable `ImageBitmap` references and computes square `ContentScale.Crop` source offsets once, with the existing 300-entry resident limit.
- The production draw phase scans `visibleItemsInfo` once, resolves key→asset→prepared image with O(1) lookups, applies existing offsets and sizes, clips to the grid viewport, and calls `drawContent()` afterward. It does not build snapshots, commands, collections, sorting, `Rect`s, crops, or access the store/adapter.
- Fine-scroll regression coverage is in `MediaGridRenderingContractTest`, `MediaGridResidentCanvasComposeTest`, and `MediaGridResidentCanvasIntegrationTest`. Scheduler, worker, queue, publication pacing, prefetch range, and pointer input are unchanged.

- `MediaGridRetainedImageStore.kt`は既存のaccess-order LRU、300entry、byte上限、visible／active保護、restore、memory trimを維持したまま、`MediaGridResidentImageIdentity`、`MediaGridResidentDrawHandle`、`MediaGridResidentDrawIndex`を追加する。
- `MediaGridResidentDrawHandle.directDrawEligible`はoffscreen先読み完了またはpublication／初回warm-up確定後だけtrueになる。同一identityのfalse retainではtrueを上書きせず、`lookupEligibleDrawHandle()`／`hasEligibleDrawHandle()`はimmutable draw indexだけを読む。
- `AtomicReference`のimmutable draw indexはasset IDから現在identityを確認してO(1)でhandleを返す。lookupはstore lock、Coil cache書込み、request、pack read、decode、pixel copyを行わない。
- visible assetのLRU touchは`updateProtection()`内のasset ID補助indexで行い、セル描画からstore lockを取得しない。production UI、Placeholder、AsyncImage、frame publication、queue、worker、先読み範囲は変更しない。
- 実機画像・競合の証跡は`MediaGridRetainedImageStoreIntegrationTest.kt`と対応docsに置く。

## 2026-07-28 resident single Canvas layer

- `MediaGridResidentCanvas.kt` defines the explicit `Disabled`/`Enabled`/`TestVisible` mode, identity/value keyed ImageBitmap adapter, current `LazyGridState.layoutInfo` geometry snapshot, centered crop calculation, immutable draw commands, and one `drawWithCache` DrawModifier that draws residents before `drawContent()`.
- `Enabled` and `TestVisible` share the same production draw engine. The normal classified grid passes `Enabled` explicitly; the content default remains `Disabled`. Resident cells omit background, Placeholder, Error, and `AsyncImage` while preserving overlays and input.
- `MediaGridRetainedImageStore.drawIndexVersionFlow` publishes only draw-index content changes; restore, protection, LRU touch, and viewport movement do not publish a version.

## 2026-07-24 cache-hit starvation fix

- `MediaGridSteadyLoadController.kt` owns the unified cache-hit transition, asset-local work reconciliation, bounded metadata retry/failure state, conflated UI publication, and deterministic controller snapshots.
- `MediaGridSteadyLoadControllerIntegrationTest.kt` is the isolated TEST_HARNESS entry for cache-hit regression, mixed cache/miss, 1,000 assets, metadata failure, and fixed-seed state-machine coverage.

## 2026-07-24 第16実装: decoupled load pipeline

- `MediaGridSteadyLoadController.kt`は、metadata準備、Bitmap load、Compose publicationを別Channel consumerで処理する。通常loadに50ms tick、固定Delay、sleep、周期pollingはない。
- metadata/Bitmapは総数4、background最大2、urgent予約最大2。viewportは最新anchorと未開始taskの優先順位だけを更新し、開始済みlocal taskを画面外移動でcancelしない。
- backgroundはframe全体metadataとlocal候補のBitmap preloadを継続し、Coil memory cache 75%で停止、65%未満signalで再開する。visible/前後1行はurgent、backgroundではnetwork候補を開始しない。
- UI publication consumerはevent到着までsuspendし、到着済みeventを1 batchでMainへ公開する。offscreen completionはCompose stateへ追加しない。
- 第16実装のunit契約は`MediaGridSteadyLoadControllerTest.kt`、既存の高速スクロール・画面復帰・列数変更・Progress・RGB565/JPEG回帰は`MainActivityComposeTest.kt`、`UiStateRenderingTest.kt`、`MediaGridRgb565IntegrationTest.kt`で確認する。

## 2026-07-24 viewport hot path改善

- `updateViewport()`は最新anchor、epoch、conflated signalだけを更新し、anchor consumerがlock外でepoch単位の`MediaGridActiveWindowSnapshot`を一度だけ生成する。
- snapshotはvisible順序、active順序、membershipを保持し、urgent判定・visible task登録・UI publicationは同じsnapshotを使う。active window再生成、queue全体のurgent昇格、viewport側のlock取得を行わない。
- `AssetQueueRecord`がmetadata/Bitmapのqueue状態、token、generation、source identity、candidate indexをasset ID単位で保持し、古いentryはtoken不一致でskipする。background queueの選択方式、候補順、worker上限、UI batch、Progressとsession保持は維持する。
- RGB_565候補は2byte/pixel、その他候補は4byte/pixelをLongで見積もる。要件のUnit証跡は`MediaGridSteadyLoadControllerTest.kt`、既存の高速viewport・画面外完了・cache再表示・fallback回帰はCompose/隔離Integration Testで確認する。

## 2026-07 keyed frame and direct preview viewport path

## 2026-07-22 steady-load controller

- `MediaGridSteadyLoadController.kt` owns startup warm-up, current-frame metadata, active-window load state, cancellation, completion batching, and the single 50ms loop.
- `MediaGridSessionCoordinator.kt` owns the session LRU, frame/controller lifetime, pause/resume, atomic frame replacement, and background refresh boundary. The controller is disposed only by ViewModel clear or session eviction.
- `TagHierarchyUiV2.kt` only overwrites the latest conflated viewport anchor and renders controller-published cell states. Pending/Loading cells do not start image work.
- `MediaGridDirectPreview.kt` remains the stateless candidate/identity/cache-key builder and does not retain metadata across render keys.
- The shared Coil capacity and decoder concurrency, persistent JPEG generation, DB, original images, UI interactions, and Macrobenchmark remain unchanged.

- The grid viewport observer reads the frame's stable item indices, visible asset IDs, and cell size; pixel-only movement does not rebuild preparation targets.
- Dragging and flinging do not stop visible `AsyncImage` requests. Compose disposal cancels requests for cells that leave the composition.
- `collectLatest` cancels old preparation on direction turns and keeps at most `columnCount` cells in the adjacent row.

## 2026-07-13 card/grid duplicate-work removal

- `MainUiState`の未分類・投稿者候補・分類済み一覧は遅延評価し、グリッド経路はカード一覧・スクロールキー・item keyを評価しない。
- Repositoryは明示的なrevision付き`MediaGridSourceSnapshot`を発行し、投稿日・local day・投稿者正規化・タグID配列・対象Asset件数をsource更新時に前計算する。
- グリッド結果cacheはsource revisionとタグ構造revisionを使い、全source/階層の`hashCode()`を使わない。`tagIdsByClip`は`LongArray`で保持し、一括タグDialog境界だけ集合化する。

## Current media-grid column change path

- The production path keeps the normal `LazyVerticalGrid` as the only layout surface and uses the shared `MediaGridMorphGestureMode.Production` input with `MediaGridMorphProductionHandoffEffects` for claim, Morph drawing, target verification, reveal, rollback, and completion.
- A prepared direction is claimed only from a complete claim bundle containing the captured source viewport, required render set, prepared endpoints, and frozen row model. An unavailable or incomplete pair uses the captured-anchor release fallback.
- Target rows and headers are derived from `MediaGridMorphExactTargetLayoutIndex`; the handoff verifies the actual target LazyGrid geometry before unlocking scroll/checkpoints. Exact handoff does not use the legacy anchor restore.
- Successful handoff assets remain in bounded carryover until the new-column stable-idle pair is ready, so an immediate reverse gesture can still claim Morph. Cancellation, failure, and disposal release that protection.

## 2026-07 single-step media-grid resize（履歴: 現行経路では不使用）

- Media-grid pinch resizing changes exactly one column at direction recognition, locks until all pointers are released, and animates only composed media cells.
- Stable asset keys and center offsets preserve the central asset across header regrouping. Resize updates the latest viewport without rebuilding the ordered source snapshot or thumbnails.

## 2026-07-14 final media-grid morph adjustment（履歴: 現行経路では不使用）

- `MediaGridMorph.kt` keeps Header geometry, title-layer alpha, and the session/handoff state calculations independent from Compose and source work.
- `MediaGridMorphOverlay.kt` draws only bounded Media Slot/Header ranges; the normal grid remains visible until the plan, stable Asset visuals, Rects, and placeholder brush are ready. Progress/correction are read through graphics layers.
- `TagHierarchyUiV2.kt` keeps the normal grid as the only grid, prepares the final Header/Media sequence off the UI thread, updates only motion during tracking, and owns the single Morph handoff correction path.

この文書は、like list managerの全体構成、現状の実装、変更時に最初に読む個別文書への索引です。

個別ソースの説明は `docs/` 配下に、ソースと同じディレクトリ構造で配置しています。ファイル名は元ソース名に `.md` を追加した形式です。

例:

```text
app/src/main/java/com/lyco256/llm/data/ClipRepository.kt
docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md
```

## 現在のアーキテクチャ

```text
MainActivity / Compose UI
  -> MainViewModel
    -> ClipRepository
      -> Room DAO -> SQLite
      -> XOAuthManager -> AppAuth / X OAuth 2.0
      -> XApiClient -> X API v2
      -> ApiSettingsStore -> EncryptedSharedPreferences
      -> PostStorageManager -> internal storage / SD card app-specific storage
        -> Room DB + images
```

依存関係は `LikeListManagerApp` が所有する `AppContainer` で組み立てます。

## 現状の実装

### UI

- ダークテーマ
- 未分類リスト: グループを展開し、配下のタグを保留選択して分類済みにする
- 分類済みリスト: 一致件数と文章形式の条件サブバー、適用/キャンセル付き全画面絞り込みDialog、タグのみトグル付きの全ツイート検索、投稿日・本文・概要・投稿者・ユーザー・タグ／グループの「含む」「必須」「排除」複合絞り込み、タグ再割り当て
- タグリスト: 無制限階層のグループ／タグ追加、名称変更、移動、長押し並び替え、削除、別タグへの一括追加
- X風の投稿本文、投稿者、画像表示
- 投稿カード内の保存済みPhotoをタップすると、黒背景の全画面画像ビューアで表示し、複数Photoは左右スワイプで切り替え
- 投稿カード上では投稿URLを文字列として表示せず、メディア付き投稿の本文末尾t.coもUI上だけ省略する。本文中URLと「Xで開く」機能は維持
- 未分類、分類済み、タグ管理でスクロールバー、スクロール位置維持、一番上へ移動ボタンを表示
- Xで開く、ローカル削除
- 右上設定アイコンから全画面の設定画面を開き、同期、いいね数更新、API使用量、X API設定、投稿データ保存先をまとめて表示

### X連携

- OAuth 2.0 Authorization Code Flow with PKCE
- AppAuthによるstate、code verifier/challenge管理
- AndroidアプリへClient Secretを保存しないpublic client方式
- scopes: `tweet.read users.read like.read offline.access`
- callback: `likelistmanager://oauth/x/callback`
- `/2/users/me` でユーザーID取得
- `/2/users/{id}/liked_tweets` のpagination取得
- refresh tokenによるaccess token自動更新
- logout時のtoken revoke
- 401、403、429、5xxのユーザー向けエラー表示

### 保存と同期

- Roomで投稿、画像情報、タググループ、タグ、投稿タグ関連、同期状態を保存
- DB version 7でタグ階層、いいね数、同期継続token、月別API使用量履歴、OCR文字列を保持し、version 1→2、2→3、3→4、4→5、5→6、6→7を非破壊移行
- Client IDとOAuth tokenは暗号化SharedPreferencesへ保存
- Room DBと画像は内部ストレージまたはSDカードのアプリ専用領域へまとめて保存
- 保存先変更時はコピー、容量・件数・DB整合性検証、切り替え、旧データ削除を行う
- 選択中のSDカードがない場合は空DBへ切り替えず、閲覧・編集・同期を停止
- 保存先設定と移動復旧状態は内部SharedPreferencesへ保存
- PhotoはWebP lossy quality 85で保存
- 動画/GIF本体は保存せず、previewImageUrlからthumbnailを取得してWebPで保存する。新規同期ではWi-Fi待ち状態を作らない
- 投稿IDのunique制約で重複保存を防止
- 月間取得数、月別API使用量履歴、警告/停止判定値、15分rate limitを記録
- 初回サンプルデータはDBが空の場合だけ投入

## 変更目的別の入口

| 変更したいこと | 最初に読む文書 | 次に確認する文書 |
| --- | --- | --- |
| 画面、操作、検索、タグUI | `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md` | `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`, `ClipRepository.kt.md`, `Entities.kt.md` |
| 同期ロジック、月間制限、画像保存 | `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md` | `XApiClient.kt.md`, `Daos.kt.md`, `Entities.kt.md` |
| 新規local assetの永続JPEG preview生成 | `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPersistentPreviewStore.kt.md` | `MediaGridPreviewWork.kt.md`, `MediaGridPreviewWorker.kt.md`, `ClipRepository.kt.md` |
| 投稿DB・画像の保存先、SDカード移動 | `docs/app/src/main/java/com/lyco256/llm/data/PostStorageManager.kt.md` | `AppContainer.kt.md`, `ClipRepository.kt.md`, `MainActivity.kt.md` |
| X APIのendpointやresponse | `docs/app/src/main/java/com/lyco256/llm/data/XApiClient.kt.md` | `ClipRepository.kt.md`, `Entities.kt.md` |
| Xログイン、scope、callback | `docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md` | `AndroidManifest.xml.md`, `ApiSettingsStore.kt.md`, `MainActivity.kt.md` |
| DB列、table、relation | `docs/app/src/main/java/com/lyco256/llm/data/Entities.kt.md` | `LikeListDatabase.kt.md`, `Daos.kt.md`, `ClipRepository.kt.md` |
| queryやtransaction | `docs/app/src/main/java/com/lyco256/llm/data/Daos.kt.md` | `Entities.kt.md`, `ClipRepository.kt.md` |
| 依存ライブラリ、SDK | `docs/app/build.gradle.kts.md` | `docs/gradle/libs.versions.toml.md` |
| アプリ起動、権限、deep link | `docs/app/src/main/AndroidManifest.xml.md` | `LikeListManagerApp.kt.md`, `XOAuthManager.kt.md` |

## 個別文書一覧

### ルート・Gradle

- `docs/.gitignore.md`
- `docs/build.gradle.kts.md`
- `SAFE_DEBUG_ROUTINE.md`: 安全なビルド、テスト、lint、実機上書き再インストール手順
- `docs/settings.gradle.kts.md`
- `docs/gradle.properties.md`
- `docs/gradlew.md`
- `docs/gradlew.bat.md`
- `docs/gradle/libs.versions.toml.md`
- `docs/gradle/wrapper/gradle-wrapper.properties.md`
- `docs/gradle/wrapper/gradle-wrapper.jar.md`

### Android設定

- `docs/app/build.gradle.kts.md`
- `docs/app/src/main/AndroidManifest.xml.md`
- `docs/app/src/main/res/drawable/ic_x_logo.xml.md`
- `docs/app/src/main/res/values/styles.xml.md`

### Application・UI

- `docs/app/src/main/java/com/lyco256/llm/LikeListManagerApp.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MainActivity.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/SettingsScreen.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/OcrUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagHierarchyUiV2.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/TagColorUi.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridPlaceholderRendering.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridSessionCoordinator.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridMorphCanvas.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridMorphHandoff.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/MediaGridMorphLazyGridHandoffTestHost.kt.md`
- Classified tab card/grid switching is handled in `MainActivity.kt` and `TagHierarchyUiV2.kt`; the grid path is built from `ClassifiedMediaGridState` over the lightweight repository source, while the card path continues to use `uiState.classified`.

### Data・API

- `docs/app/src/main/java/com/lyco256/llm/data/AppContainer.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/Entities.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/Daos.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/LikeListDatabase.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/PostStorageManager.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/ApiSettingsStore.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/XOAuthManager.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/XApiClient.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/ClipRepository.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPersistentPreviewStore.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWork.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWorker.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/OcrTextRecognizer.kt.md`
- `docs/app/src/main/java/com/lyco256/llm/data/TagColorPalette.kt.md`

### Tests

- `docs/app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridMorphHandoffTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridRenderingContractTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridPreparedRenderTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridPlaceholderRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridMorphCanvasComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridMorphLazyGridHandoffComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MediaGridFramePublicationComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/LikeListDatabaseMigrationTest.kt.md`
- `MainActivityComposeTest.kt`, `UiStateRenderingTest.kt`, `RepositoryIntegrationTest.kt`, and `LargeDatasetIntegrationTest.kt` cover the classified display toggle, lightweight media-grid flow, 2〜12 column resizing, section headers, selection/Dialog boundaries, and large-data rendering.
- `docs/app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/OcrTextRecognizerTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/MediaGridPersistentPreviewStoreTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/MediaGridSessionCoordinatorTest.kt.md`

### Macrobenchmark

- `docs/macrobenchmark/build.gradle.kts.md`
- `docs/macrobenchmark/src/main/AndroidManifest.xml.md`
- `docs/macrobenchmark/src/main/res/xml/macrobenchmark_network_security_config.xml.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/StartupMacrobenchmark.kt.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/PersistentBenchmarkRunner.kt.md`
- `docs/macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/MediaGridPerformanceMacrobenchmark.kt.md`
- `docs/app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotSetupActivity.kt.md`

### Scripts

- `docs/scripts/SafeScriptCommon.ps1.md`
- `docs/scripts/run-safe-debug-check.ps1.md`
- `docs/scripts/run-safe-debug-check.cmd.md`
- `docs/scripts/run-safe-integration-check.ps1.md`
- `docs/scripts/run-safe-integration-check.cmd.md`
- `docs/scripts/run-safe-macrobenchmark-check.ps1.md`
- `docs/scripts/run-safe-macrobenchmark-check.cmd.md`
- `docs/scripts/run-safe-snapshot-check.ps1.md`
- `docs/scripts/run-safe-snapshot-check.cmd.md`

## 現在の未実装・制約

- 自動バックグラウンド同期は未実装
- backup/import/exportは未実装
- 任意フォルダへの保存とアンインストール後の投稿データ保持は未実装
- タグ色変更は12色パレットで実装済み
- 動画/GIF本体は保存しない
- DBはversion 7で、version 1→2・2→3・3→4・4→5・5→6・6→7のmigrationを実装済み
- 階層・複合絞り込み・制約・件数表示の単体テストと、version 1→2・2→3・3→4・4→5・5→6・6→7のmigration testを実装済み
- 実際のXログインとliked posts同期はユーザーのClient IDとXアカウントで実機確認が必要

## 関連文書

- `TEST_REQUIREMENTS_COVERAGE.md`: 実機レベル統合テスト要件の項目別証跡、未確認事項、完了判定基準

- `GOALS.md`: プロダクトの目的、MVP、将来目標
- `SAFE_DEBUG_ROUTINE.md`: 毎回使い回せる安全なビルド/テスト/再インストール手順
- `REAL_API_VERIFICATION.md`: 実Xアカウントでの確認手順
- `docs/CHANGE_SUMMARY_2026-06-12.md`: 今回のOAuth実装と文書整備の要約
- `LikeTagger_requirements.md`: 初期の要件・技術仕様メモ。設計経緯や将来候補の確認に使い、現在の仕様判断は `GOALS.md` と実装を優先します。

## 2026-06-20 いいね数・件数表示

- 新規同期投稿へ `public_metrics.like_count` と取得日時を保存し、既存投稿は明示再取得だけで更新します。
- 設定画面から未取得／期限到来した暫定値を月間残り枠内で一括再取得できます。
- 投稿カードのいいね数・暫定警告、投稿者件数順、タグ投稿数、グループ直下要素数を表示します。
- Room schema versionは7で、version 1→2、2→3、3→4、4→5、5→6、6→7 migrationを登録しています。

## 2026-06-22 高リスク統合テスト基盤

## 2026-07-12 メディアグリッド要件確認

- 一括タグ編集は選択ツイート単位で `NONE` / `ALL` / `MIXED` を集約し、`KEEP` をDB更新へ渡さない。
- `MIXED` は全件削除予定と全件追加予定を交互に切り替え、適用確認後も複数選択状態を維持する。
- いいね数見出しは2〜4列/5〜8列/9〜12列で200/500/1000単位、週見出しは月曜〜日曜の期間表示。

## 廃止済み画像経路の履歴

実装6より前の生成・状態管理・viewportスケジューリング・独自cache復元は削除済みです。詳細はGit履歴だけに残し、この現行構造ガイドには旧APIや旧動作を再掲しません。

## 2026-07 media-grid continuous morph foundation（履歴: 現行経路では不使用）

- `MediaGridMorph.kt` is the pure state/plan boundary for `Idle`, `Tracking`, both settle phases, and `AwaitingGridHandoff`.
- A session creates one from/to plan for exactly one adjacent column count, bounded to the viewport plus two rows on each side. Slots keep both Rects, Asset keys, item indexes, and presence flags; the wider side defines the slot count.
- Header bands retain start/end title, Y, height, and presence, including zero-height add/remove transitions. The nearest Media Asset to the pinch center is kept as the Y anchor.
- The Morph transaction is separate from `MediaGridMorphOverlayMotion.progress`; progress is reversible and bounded to `0f..1f`, while the real `columnCount` remains unchanged during tracking and the callback is dispatched once only after target settle.
- `TagHierarchyUiV2.kt` keeps `pointerInput(Unit)` stable and reads latest values with `rememberUpdatedState`. Progress updates do not rebuild items or request thumbnails.
- Idle-only preparation slices the current LazyGrid window plus two rows on each side and builds both adjacent candidates; target handoff resolves the anchor by stable Asset key and performs one `scrollToItem` plus the necessary `scrollBy`. Normal anchor restoration is disabled for every non-Idle phase.
- Pure coverage is in `app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt`; the source-level contract is documented in `docs/app/src/main/java/com/lyco256/llm/MediaGridMorph.kt.md`.

## 2026-07 seamless media-grid morph overlay（履歴: 現行経路では不使用）

- `MediaGridMorphOverlay.kt` draws a viewport-bounded overlay over the single normal `LazyVerticalGrid` during all non-Idle morph phases; no second grid or `AnimatedContent` is constructed.
- `MediaGridMorphRenderModel` is created once per session plan, resolves Assets by stable key, and retains slot Assets, metadata, selection, header bands, and placeholder data through handoff. Progress updates only change interpolated Rects, layer alpha, and GPU transforms.
- Retained image state was read without starting image work. This overlay is not part of the current product path.
- Target handoff keeps progress 1 visible through callback and new-grid layout, performs one anchor correction, then completes without a second post-handoff correction.
- The old resize scale animation and normal `animateItem()` placement are removed; the retained morph documentation is historical and the current normal grid uses cell-local placeholder rendering from `MediaGridPlaceholderRendering.kt`.

- `integrationTest` build typeは `com.lyco256.llm.test` と本番とは異なるDB、画像、Preferencesを使います。
- テスト用Application containerは本番OAuth/X APIを無効化し、Repositoryテストだけが記録可能なFakeを注入します。
- AndroidJUnitRunner、Compose UI Test、Room統合、MockWebServerで環境分離、同期、データ保持、画像、HTTP異常系、主要画面を検証します。
- `SnapshotCompatibilityTest` は明示指定されたDB・画像をホスト側の一時コピーで検証し、コピー元hash不変を確認します。
- `scripts/run-safe-integration-check.cmd` はGit管理外の許可serialだけを受け入れ、同じ実機上でメインと `.test` のpackage・UID分離、メインmetadata前後不変を検証します。
- `scripts/run-safe-macrobenchmark-check.cmd` は同じ許可serial上で `.test.benchmark` 対象APKとMacrobenchmarkホストだけを扱い、メインmetadata前後不変を検証します。
- 実機系`.cmd`入口はADB serverのmDNS自動接続を無効化します。既存ADB接続を先にhardware serialで照合し、同じ物理端末のUSBを優先して余分なwireless endpointを切断します。接続済みendpointがない場合だけ安全resolverがmDNS候補を1件明示接続します。異なる物理端末が混在する場合は停止し、選択serialを全ADB操作へ明示します。
- 通常確認と隔離統合確認は、debug APK、隔離設定検証、integration target APK、androidTest APKを生成する同じBuild task集合と成功stateを共有します。Build・UnitTest・Lintはphase別の入力fingerprintを使い、BuildはGradle incremental処理、UnitTestはmain入力不変時の変更test class限定を安全条件付きで使います。判断不能時はphase全体へ戻り、Build省略には全APKのSHA-256一致も必須です。`-FullRebuildTest`はユーザー明示指示時だけ使う全体確認境界です。Macrobenchmarkのcleanと実機フェーズは省略しません。
- 2026-06-23以降、SC-56Cで本番と隔離テストを共存させ、AndroidJUnitRunnerによる実機統合テストを継続実行しています。`run-safe-integration-check.cmd`はhardware serialに一致するUSB接続を優先し、USB接続がなければmDNS endpointを解決します。

追加したテスト文書:

- `docs/app/src/androidTest/java/com/lyco256/llm/TestEnvironmentIsolationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/RepositoryIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/XApiClientMockWebServerTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/SettingsStoreIsolationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/LargeDatasetIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/PostStorageManagerRecoveryTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/data/MediaGridPersistentPreviewIntegrationTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt.md`
- `docs/app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/SnapshotCompatibilityTest.kt.md`
- `docs/app/src/test/java/com/lyco256/llm/data/TagColorPaletteTest.kt.md`
# メディアグリッド高速化の入口

`MediaGridMetadata.kt`が軽量スナップショットの絞り込み・正規化済みcache key・実効sortだけの準備・標準安定ソート・Asset展開・最大3件LRUキャッシュを担当する。Repositoryのsourceはactive Clip/Asset/ClipTagの3 Flowだけで、タグIDの`LongArray`と前計算済み投稿者キーを保持する。保存順ではsortを省略し、cache hitでは`Calculating`を表示しない。
- `docs/app/src/test/java/com/lyco256/llm/data/TagColorPaletteTest.kt.md`

## 2026-07-14 実装22

- `app/src/benchmark/java/com/lyco256/llm/data/MediaGridBenchmark.kt` owns benchmark-only settings, frame timing, and result export. It is not part of debug, release, or integrationTest source sets.
- `app/src/benchmark/java/com/lyco256/llm/BenchmarkSnapshotImporter.kt` imports only the benchmark target handoff, rewrites absolute local paths, and never copies Preferences or credentials.
- `app/src/benchmark/java/com/lyco256/llm/BenchmarkMainActivity.kt` selects the classified media-grid startup state only for the benchmark variant; production `MainActivity` has no benchmark state or result handling.
- The production image path is `MediaGridDirectPreview.kt` plus `MediaGridPersistentPreviewStore.kt`, `MediaGridPreviewWork.kt`, and `MediaGridPreviewWorker.kt`. `TagHierarchyUiV2.kt` consumes prepared images without benchmark metrics, counters, or Trace sections.
- `MediaGridPerformanceMacrobenchmark.kt` runs identical five-iteration scroll and real two-pointer pinch scenarios.
- `run-safe-macrobenchmark-check.cmd` is the only snapshot entry and generates `build/reports/media-grid-benchmark/latest-summary.md` after cleanup and production invariance checks.

## 2026-07-19 第3実装 placeholder描画

- グリッド共有Brushを廃止し、`MediaGridPlaceholderRendering.kt`の`MediaGridCellVisualState`でセルごとに`Placeholder` / `Image` / `Error`を判定する。
- prepared candidateが未到着、または現在candidateの`onSuccess`前だけ、セル内の左上から右下までを覆う静的グラデーションを`drawWithCache`で描画する。Brushと色Listはセルサイズまたはテーマ色の変更時だけ作り直す。
- 現在モデルの`onSuccess`後はPlaceholderレイヤーを完全に外し、モデル変更または`onError`ではPlaceholderへ戻す。Failed、画像元なし、download失敗は単色背景と既存エラーアイコンのみ。
- 現行viewport、preload、候補順、依存関係、列数変更、Macrobenchmarkは変更しない。
## 2026-07-23 第14実装: RGB_565 fixed-slot pack

- `MediaGridRgb565PackStore.kt`が128asset固定slot、二重bank＋generation、crash-safe publish、最大4packのread-only mapping LRUを所有する。
- `MediaGridRgb565Coil.kt`がraw専用data/Keyer/Fetcherを提供し、Decoderを経由せず256×256 `RGB_565` Bitmapを返す。
- `MediaGridRgb565RepairWork.kt`がJPEG優先・local WebP fallbackのrepairを最大2並列、同一pack直列で実行する。
- 新規画像は`ClipRepository`でsource Bitmapからpayloadを作り、WebP保存とAsset insert後にraw publish完了を確認する。既存JPEG enqueueは維持する。
- 表示候補はraw→JPEG→local→preview URL→remote URL→display。phase13の初回4、通常2、session/scroll/atomic frame swapを維持する。


### GOALS.md before migration

# like list manager Goals

## プロダクトの目的

自分のXアカウントで「いいね」した投稿を端末へ取り込み、あとからタグと概要で整理・検索できる個人用Androidアプリを作る。

X側の「いいね」を変更するクライアントではなく、取得した投稿を自分用に保存・分類するローカル管理ツールとする。

## MVPの目標

- OAuth 2.0 + PKCEで自分のXアカウントへ安全にログインできる
- X APIから自分のliked postsを取得できる
- 投稿本文、投稿者、画像をXに近い見た目で表示できる
- 新規取得投稿を未分類リストへ入れられる
- 投稿へ複数タグと概要を設定できる
- タグをグループ階層で整理し、タグまたはグループを条件に分類リストを絞り込みできる
- 本文、投稿者、概要を検索できる
- 分類後もタグを再割り当てできる
- タグとグループの追加、名称変更、移動、並び替え、削除ができる
- 月間取得数と15分rate limitをメニューから確認できる
- 画像は回線を問わずWebP形式で保存し、動画/GIFはWi-Fi時だけpreview thumbnailを保存する
- X側へ書き込みや「いいね」解除を行わない
- Client Secretをアプリへ埋め込まない
- 投稿DBと保存画像の保存先を内部ストレージまたはSDカードから選択できる

## 現時点で達成済み

MVPの上記機能は実装済みです。OAuth callback、token暗号化保存、自動refresh、logout/revoke、liked posts pagination、Room保存、階層タグ・概要・複合絞り込み・検索・使用量表示、投稿データ保存先変更までコードで実装済みです。

実アカウントを使ったOAuth許可とliked posts取得の最終確認は、実機上でClient IDを入力して行います。

## 次の目標

1. 実機でOAuthログイン、callback、初回同期、token refreshを確認する
2. OAuth/API/JSON変換/Repositoryの自動テストを追加する
3. WorkManagerによる低頻度バックグラウンド同期を追加する（同期そのものは未実装のため継続）
4. backup/export/importを追加する
5. 保存先別の容量表示に加えて、画像の手動整理機能を追加する
6. DB migration testを実機で継続実行できる検証手順へ組み込む

## 非目標

- 複数ユーザー向けSaaS
- Xへの投稿、いいね、いいね解除
- 他人のいいね一覧収集
- 動画/GIF本体の恒久保存
- Client SecretやConsumer SecretのAPK埋め込み

## 判断基準

- 個人利用で操作が簡単であること
- APIコストとrate limitをユーザーが把握できること
- 端末内データと認証情報を安全に扱うこと
- X API仕様変更時に影響箇所を文書から辿れること
- 低スペックPCと実機でも開発・検証を継続できること

## 2026-06-20 達成済み

- X API取得時点のいいね数保存・表示と、暫定値警告を追加
- 料金確認と月間残り枠を伴う明示的ないいね数再取得を追加
- 投稿者の件数順、タグ投稿数、グループ直下要素数の表示を追加
- liked posts同期が途中終了した場合のnext token保存と次回再開を追加

## 2026-06-22 達成済み

- 本番packageを維持したまま、`com.lyco256.llm.test` の隔離統合テストvariantを追加
- 本番OAuth、token store、X APIをテスト用アプリから利用できないfail-closed構成を追加
- 再同期時の手動概要・タグ保持、重複排除、pagination、401 refresh、429、月間停止のRepository統合テストを追加
- WebP保存、タグ/グループ削除時の投稿保護、MockWebServer異常系、主要Compose画面テストを追加
- DB・実画像バックアップを一時コピーだけで検証する任意snapshotテストを追加

## 2026-06-23 達成済み

- SC-56Cへ本番 `com.lyco256.llm` と隔離テスト `com.lyco256.llm.test` を同時に導入し、異なるUIDで共存することを確認
- 本番packageのpath、UID、version、初回導入日時、更新日時が実機テスト前後で不変であることを安全スクリプトで確認
- AndroidJUnitRunnerによる実機統合テスト（Compose UI、環境分離、Room migration、Repository、MockWebServer）を安全スクリプト経由で継続実行できる状態にした
- SC-56Cで正常終了をクラッシュ扱いするOrchestratorは使わず、各UIテスト前に隔離DBだけを初期化する構成へ変更

## 2026-07-01 達成済み

- メインと隔離テストpackageを共存させた実機で、安全スクリプト経由のAndroidJUnitRunner結果を継続確認できる状態に更新
- 大量データ、Macrobenchmark、property-based testing、検索/分類/タグ管理/設定/エラー復旧/軽微UI状態の主要自動検査を追加
- メインメニュー、結果Dialog、いいね数再取得見積もり、ローカル削除Dialog、スクロール後の検索条件維持、スクロール後の未確定タグ選択維持をtestTagとDB assertで固定
- SC-56CではOrchestratorが正常終了をクラッシュと誤判定するため採用しない方針を維持

実画像backupの提供がないため、実画像backupによるsnapshot最終確認だけは未完了として残します。

## 2026-07-11 達成済み

- 分類済みメディアグリッドの2〜12列、投稿日／いいね数見出し、ピンチ列数変更、投稿単位の複数選択、一括タグ編集、選択中のカードDialog導線を実装
- 複数選択を0件まで維持し、×／戻るで終了、0件時のタグ編集無効化、セルタップとDialogボタンのイベント分離を実装
- 選択表示をチェックボックスだけに限定し、単一画像の水色＋黒チェック、複数画像の青色＋白チェック、選択開始時だけのハプティックを実装
- タグ／グループの色パレットと色設定を実装
- wireless ADBのmDNS endpoint自動解決を安全な統合テストスクリプトへ追加

## 2026-07-20 達成済み

- 新規local asset向け256×256中央crop JPEG previewを`filesDir/media_grid_previews/v1/<assetId>.jpg`へ非同期生成するWorkManager経路を追加
- DB schema、元画像、既存cache、グリッド表示経路を変更せず、削除・localPath変更競合と原子的置換を検証
- wireless隔離統合テストと本番安全上書き検証をSuccessで完了。Macrobenchmarkは対象外として未実行

## 2026-07-22 steady-load controller 達成済み

- 初期Progress中に表示位置周辺のmetadataと永続JPEG memory warm-upを行い、全terminalまたは3秒でグリッドを公開する経路を追加
- viewport通知をlatest anchor上書きに限定し、50ms周期・固定予算・同時2requestの単一controllerへ画像処理を集約
- active bitmap windowを表示中＋前後1行に限定し、範囲外requestとUI load stateを破棄しつつframe内metadataとCoil LRUを再利用
- wireless隔離統合テストと本番安全上書き検証をSuccessで完了。Macrobenchmarkは要件指定により未実行

## 2026-07-23 第14 RGB_565 pack 達成済み

- 256×256 `RGB_565`を128asset固定slot、二重bank＋generationのpackへ保存する通常経路を追加
- source Bitmapからraw payloadを作り、WebP・Asset・既存JPEG生成を維持
- Decoderを通さないCoil Fetcherとraw→JPEG→local→URL fallbackを追加
- wireless隔離統合テストと本番安全上書き検証をSuccessで完了。Macrobenchmarkは要件指定により未実行


### TEST_REQUIREMENTS_COVERAGE.md before migration

# 実機レベル統合テスト強化 カバレッジ

## 2026-08-10 Morph claim/draw hot-path coverage

| Requirement | Evidence | Status |
|---|---|---|
| identity一致idle pairをclaimで再利用し、fallback rebuildはselected directionだけ | `prepareMediaGridMorphClaim`, `buildMediaGridMorphRowPreparedPairForClaim` | implemented; unit/static passed |
| production bundleはlocked direction一件だけのplan/model/protectionを保持 | `buildMediaGridMorphSelectedClaimBundle`, `MediaGridMorphInteractionController.claimPointers` | implemented; unit/static passed |
| claim selectionのbounded precomputed index | `MediaGridMorphViewportSelectionIndex`, `MediaGridMorphViewportPlanTemplate.select` | implemented; unit/static passed |
| RequiredRenderSet一回共有とbounded header text | `buildMediaGridMorphDirectionClaimBundle`, `rememberMediaGridMorphTextResourceIndex` | implemented; unit/static passed |
| draw loopのtransition事前判定とRect/blend helper除去 | `MediaGridMorphCellTransitionType`, `MediaGridMorphHeaderTransitionType`, `drawMediaGridMorphRow` | implemented; unit/static passed |
| headerなし／ありproduction counterとgeometry/handoff回帰 | `MainActivityComposeTest.productionMorphAllSlotsChangeIsStableIdleAndMorphsBeforePhysicalUp`, `productionMorphHeaderVisibleIsStableIdleAndMorphsBeforePhysicalUp` | safe integration passed (`OK (189 tests)`) |

最終検証は指定順で完了した。`scripts\run-safe-integration-check.cmd` は `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`（`OK (189 tests)`）、続く `scripts\run-safe-debug-check.cmd -InstallToDevice` は `Preflight / Build / UnitTest / Lint / Install / Success`。Macrobenchmarkは要件どおり実行していない。

## 2026-08-02 exact source viewport and current-return gate

| Requirement | Evidence | Status |
|---|---|---|
| Visible source row is the sole progress-0 geometry/content truth; canonical rows are bounded validation/target data | `captureMediaGridMorphInput`, `MediaGridMorphSourceRowKey`, `MediaGridMorphViewportPlanTemplate.select` | implemented; safe integration passed |
| Claim-time item sequence, prepared-image identity, header/cell rects and one-pixel row rounding | `MediaGridMorphLazyGridHandoffComposeTest.productionClaimStartsMorphAtTheExactNormalRowsForAPartialBoundedViewport` | implemented; safe integration passed |
| Current LazyGrid index/offset stays unchanged through claim and `RevealCurrent`; target anchor remains separate | `MediaGridMorphSourceViewportAnchor`, `MediaGridMorphProductionHandoffEffects`, `MediaGridMorphHandoffRequest` | implemented; safe integration passed |
| Bounded offsets, header/no-header, partial/top/middle/end, fallback, and roundtrip coverage | `MediaGridMorphLazyGridHandoffComposeTest`, `MainActivityComposeTest`, `MediaGridMorphTest` | safe integration passed |

## 2026-08-01 current handoff/toolbar update

The current source update restores the pre-devenv toolbar Row bounds inside a full-width background, removes centroid-ratio claim arbitration, distinguishes `NoMedia` from required `Media`/prepared images, adds idle urgent target promotion and claim-time protection, and removes the production `ProductionVisible` Canvas mode. On 2026-08-01, `run-safe-integration-check.cmd` completed through Preflight / Build / UnitTest / Lint / Install / IntegrationTest with `OK (183 tests)` (`build/safe-script-logs/run-safe-integration-check/20260801-233503.log`). The required follow-up `run-safe-debug-check.cmd -InstallToDevice` completed through Preflight / Build / UnitTest / Lint / Install; unchanged successful validation states were safely reused and the production package metadata remained unchanged (`build/safe-script-logs/run-safe-debug-check/20260801-234630.log`).

## 2026-08-01 Phase 2 production row Morph

### Uniform lattice and toolbar correction

| Requirement | Evidence | Status |
|---|---|---|
| Uniform source/target cell lattice for adjacent column changes | `MediaGridMorphViewportPlan` stores source/target cell size, fixed focal Y, `focalV`, relative rows; `MediaGridMorphTest.rowReflowUsesUniformLatticeForRequiredAdjacentColumnPairs` | unit covered; safe debug passed |
| Full-size offscreen right-edge cells and independent header bands | `rowReflowKeepsRemovedRightEdgeCellSquareAndOutsideViewport`, `rowReflowHeaderHeightDoesNotChangeMediaCellSizeOrInsertInteriorZeroRow` | unit covered; safe debug passed |
| Single LazyGrid surface clipped to its viewport | `MediaGridResidentCanvas.mediaGridSingleSurface`, `MediaGridRenderingContractTest.productionRowRendererUsesUniformLatticeInsteadOfEndpointRectLerp` | static/unit covered |
| Opaque foreground filter and selection toolbars | `TagFilterSummaryRow`, `MediaGridSelectionToolbar`, and `MediaGridRenderingContractTest.productionGridOwnsTheOnlyOverscrollOptOutAndToolbarsSharePositiveLayer` | static covered |

The required safe integration and device-install gates completed successfully after this implementation update: `run-safe-integration-check.cmd` reached `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`, and `run-safe-debug-check.cmd -InstallToDevice` reached `Preflight / Build / UnitTest / Lint / Install / Success`.

| Requirement | Evidence | Status |
|---|---|---|
| real LazyGrid claim capture before visual activation | `TagHierarchyUiV2.kt`, `MediaGridMorphInteraction.kt`, `MediaGridMorphRowRenderer.kt` | Implemented; unit/static contracts passed |
| one resident/Morph/reveal draw surface | `MediaGridResidentCanvas.mediaGridSingleSurface`, `MediaGridRenderingContractTest` | Implemented; draw-path contract passed |
| row reflow and right-edge Placeholder/Image behavior | `MediaGridMorphRowReflow.kt`, `MediaGridMorphTest` | Implemented; 2↔3, 4↔5, 5↔4, 8↔9, 11↔12 covered |
| current/target reveal barriers and identity handoff | `MediaGridMorphInteraction.kt`, `MediaGridMorphHandoffTest` | Implemented; exactly-once reveal tests passed |
| full source/end endpoint protection | `MediaGridMorphRowRenderer.kt`, `TagHierarchyUiV2.kt`, rendering contract test | Implemented |
| row/ordinal/header/cell geometry within one pixel with rollback | `MediaGridMorphHandoff.kt`, `MediaGridMorphHandoffTest` | Implemented; event-driven coordinator coverage passed |
| no draw-phase collections/lookups/text/crop/state work | `MediaGridResidentCanvas.kt`, `MediaGridMorphRowRenderer.kt`, rendering contract test | Implemented; static contract passed |
| safe integration/debug verification | `scripts/run-safe-integration-check.cmd`, `scripts/run-safe-debug-check.cmd -InstallToDevice` | Implemented; final safe runs passed |

## 2026-08-01 Phase 1 row reflow（履歴: 現行経路では不使用）

| Requirement | Evidence | Status |
|---|---|---|
| Production Morph is disabled; release path is one-step legacy pinch | `TagHierarchyUiV2.kt`, `MediaGridLegacyPinch.kt`, rendering contract | covered |
| Claim-time real rows/header rects and fixed focal row/Y | `captureMediaGridMorphInput`, `MediaGridMorphRowReflow.kt`, row-reflow unit tests | covered |
| Adjacent N↔N±1 right-edge-only geometry and distance-ratio progress | `MediaGridMorphRowReflow.kt`, 2↔3/4↔5/5↔4/8↔9/11↔12 unit tests | covered |
| Same current rect content crossfade with Placeholder endpoints | `MediaGridMorphRowRenderer.kt`, row-reflow unit tests | covered |
| Real target Grid row selection and correction | `TagHierarchyUiV2.kt`, `itemIndexByMediaOrdinal`, target handoff effect, target viewport clamp unit test | covered |
| Same-surface TEST_HARNESS renderer without overlay/two-grid host | `TagHierarchyUiV2.kt`, `MediaGridRenderingContractTest` | covered |
| TEST_HARNESS path is row-only and does not build legacy dataset slots | `buildMediaGridMorphRowPreparedPairs`, `MediaGridMorphPlan.selectRowReflow`, `MediaGridMorphTest.rowReflowPairDoesNotBuildLegacyDatasetSlots` | covered |
| TEST_HARNESS prepared-pair miss still completes adjacent fallback | `TagHierarchyUiV2.kt` shared gesture modifier fallback callback | covered |

## 2026-07-31 Morph UI・handoff correction（履歴: 現行経路では不使用）

| Requirement | Evidence | Status |
|---|---|---|
| One production Morph Canvas, with normal cell/header/resident visuals suppressed only after claim | `TagHierarchyUiV2.kt`, `MediaGridMorphCanvas.kt`, `MainActivityComposeTest.normalClassifiedGridComposesProductionCanvasDuringLivePinch` | covered |
| Fixed initial pinch focal point; no current-centroid translation | `mediaGridMorphFocalCorrection`, controller settle tests, fixed-pointer Compose coverage | covered |
| Explicit Image/Placeholder endpoints, resident miss rendered as Placeholder, edge reveal clipped in current rect | `MediaGridMorphSlotContent`, Canvas tests, production-readiness Compose tests | covered |
| Normal release, pointer disappearance, fallback, target settle, and exactly-once column callback | `mediaGridMorphGestureInput`, handoff coordinator/controller tests, production host Compose tests | covered |
| One-finger scroll/candidate arbitration remains intact | `realLazyGridKeepsScrollAndPanUntilPinchClaimThenStopsOnce` | covered |
| Live display toggle, repeated pinch round trips, recreation, filter/sort changes, dialogs, and no double display | `MainActivityComposeTest.classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation` | covered |

Failure states are explicit: identity mismatch and target-anchor failure publish `MediaGridMorphPhase.Failed` with a reason, clear the stale Morph Canvas/lock, and leave the current LazyGrid available for recovery. Handoff target-frame/geometry/rollback failures use `MediaGridMorphGridHandoffFailureReason` enum values. They do not silently reset to `Idle`.

The live test waits for the source/target `columnCount`, `requestedColumnCount`, and frame key to agree. It does not infer column changes from a clipped cell `boundsInRoot` width while handoff visual correction is active.

## 2026-07-31 gesture arbitration fix（履歴: 現行経路では不使用）

| 要件 | 証跡 | 状態 |
| --- | --- | --- |
| scroll状態に依存しない二本指candidate、pointer ID／initial positions／distance一回固定 | `MediaGridMorphCandidate`、`MediaGridMorphGestureArbitrationState`、`MediaGridMorphLazyGridHandoffComposeTest.realLazyGridKeepsScrollAndPanUntilPinchClaimThenStopsOnce` | 完了 |
| candidate中の非consume・既存direction・touchSlop 0.35・centroid 0.5判定 | `mediaGridMorphCandidateDirection`、`MediaGridMorphTest.candidateClaimUsesDeadZoneTouchSlopAndCentroidArbitration` | 完了 |
| claim時のcandidate begin→current update、claim後のみstopScroll／consume、fallback exactly-once | `mediaGridMorphGestureInput`、`productionGestureFallsBackOnceWhenPreparedPairIsUnavailable`、実LazyGridCompose Test | 完了 |
| viewport swept bounds内の正寸法側だけreadiness必須 | `isMediaGridMorphProductionReady`、overscan／zero-size Compose Test | 完了 |
| resident Canvas、handoff、anchor、viewport、queue、先読み、1frame1枚公開の不変更 | 対象差分と既存Rendering／handoff／publication契約、safe integration Success | 完了 |

検証結果: `scripts\run-safe-integration-check.cmd` 成功、続けて `scripts\run-safe-debug-check.cmd -InstallToDevice` も成功。Macrobenchmark、本番DB・画像・設定・認証情報の初期化は行わない。

## 2026-07-30 TEST_HARNESS 実LazyGrid handoff基盤（履歴: 現行経路では不使用）

| 要件 | 証跡 | 状態 |
| --- | --- | --- |
| bounded target anchor、request時一回確定 | `selectMediaGridMorphTargetAnchor`、interaction slot／center／nearest／targetなし Unit Test | Unit Test完了 |
| expected target identityでもCanvas維持 | `MediaGridMorphInteractionController.updateIdentity`、source再通知／target許可／stale cancel Unit Test | Unit Test完了 |
| exactly-once列変更、frame待機、最大3回補正、次frame complete | `MediaGridMorphGridHandoffCoordinator`と`MediaGridMorphHandoffTest` | Unit Test完了 |
| Asset消失ordinal fallback、invalid frame／geometry rollback | coordinator Unit Test、実LazyGrid Compose Test | Unit・隔離integration完了 |
| 2↔3、4↔5、8↔9、11↔12とheader再構成 | `MediaGridMorphLazyGridHandoffComposeTest` | 隔離integration完了 |
| Canvas消失前後pixel一致、target frame遅延 | 同Compose Testの実Canvas captureとframe制御 | 隔離integration完了 |
| viewport先頭・末尾・部分表示 | 同Compose Testの実`LazyGridState`初期位置と実layout geometry、非ゼロordinal／末尾alignment Unit Test | Unit・隔離integration完了 |
| handoff中scroll/checkpoint抑止phase、完了後scroll再開 | coordinator snapshotのexactly-once checkpoint Unit Test、hostが実gridへ渡すscroll enabled状態 | Unit・隔離integration完了 |
| render model／画像解決の再実行なし | 同Compose Testのhandoff前後counter | 隔離integration完了 |
| production未接続、二枚grid／Delay／pollingなし | 新規TEST_HARNESS hostのみ。`ClassifiedMediaGridContent`／`mediaGridPinchToResize`差分なし | 静的確認完了 |

検証結果: `scripts\run-safe-integration-check.cmd`成功、続けて`scripts\run-safe-debug-check.cmd -InstallToDevice`成功。Macrobenchmark、本番DB／設定／認証情報の初期化は実施していない。

## 2026-07-30 TEST_HARNESS Morph gesture tracking／settle（履歴: 現行経路では不使用）

| 要件 | 証跡 | 状態 |
|---|---|---|
| 初期距離÷現在距離、既存dead zone／progress、方向反転 | `MediaGridMorphInteractionController`、`MediaGridMorphTest.directDistanceScaleAndExistingProgressFunctionsCoverBothDirections`／`controllerReturnsThroughDeadZoneAndSwitchesPreparedDirectionContinuously` | Unit Test完了 |
| 非clamp 2次元focal anchor／correction | `MediaGridMorphAnchor`、`mediaGridMorphFocalCorrection`、固定中心・XY移動・slot外・viewport origin test | Unit Test完了 |
| 固定pointer ID、一本指非consume、三本目無視、release／cancel | `mediaGridMorphGestureInput`、`MediaGridMorphCanvasComposeTest.interactiveLayerTracksFixedPointersReversesAndReusesResolvedRenderWork`／`interactiveLayerCancelProducesNoHandoff` | 隔離Compose Test完了 |
| release基準180ms線形settle、exactly-once handoff、Awaiting維持 | `advanceSettleElapsed`、0／45／90／135／180ms、stale generation、complete test | Unit Test完了 |
| pointer／settle中のrender work再実行防止 | plan Stateをdirection切替時だけ更新、Canvas既存counter test、interactive counter test | Unit・隔離Compose Test完了 |
| production未接続、通常pinch／LazyGrid不変 | `MediaGridRenderingContractTest.morphInteractionIsTestHarnessOnlyAndDoesNotReplaceProductionPinchOrGridHandoff` | 静的契約・隔離integration完了 |

## 2026-07-30 TEST_HARNESS単一Morph Canvas（履歴: 現行経路では不使用）

| 対象 | 実装・証跡 | 状態 |
|---|---|---|
| target layout正方形化 | `buildMediaGridMorphLayout`は各列数の`viewport.width / columnCount`をwidth／heightへ使用。start visible実測維持、start overscan正方形、2〜12列target test | Unit Test完了 |
| bounded immutable render model | `MediaGridMorphRenderModel`、prepared pair slot/headerだけの解決、resident miss null、同一Asset参照共有、300-entry indexから1-slotだけ保持するtest | 隔離integration完了 |
| 一つのTEST_HARNESS Canvas | `MediaGridMorphCanvasMode.Disabled/TestVisible`、`BuildConfig.TEST_HARNESS` guard、Canvas test tag、一Canvas件数test | 隔離integration完了 |
| 画像Crossfade | viewport単位の一つのsaveLayer＋`BlendMode.Plus`、0／0.25／0.5／0.75／1のRGB_565 pixel、同一Asset、片側、resident miss test | 隔離integration完了 |
| header描画 | 不透明surface背景の高さ／位置補間、事前計測title layout、同一title共有、文字alpha helper、追加band 50%／100% pixel test | Unit Test・隔離integration完了 |
| draw hot path | 事前解決済み画像・crop・text layoutを使用。Map／collection／resident／crop／ImageBitmap変換／measure／IOを持たない静的契約 | Unit Test完了 |
| progress再利用・入力透過 | progress 40回更新でmodel 1回・画像解決2回・text measure 1回を維持。pair/index versionは各1回再構築。下層Buttonへの実touch到達 | 隔離integration完了 |
| production非接続 | `TagHierarchyUiV2.kt`にCanvas／mode／tag参照なし。既存pinch release、resident、viewport、anchor、queue、publication契約継続 | Unit Test・隔離integration完了 |

- `run-safe-integration-check.cmd`: 新規header色assert修正後にSuccess。最終テスト強化後は既存controller timing timeoutが一度だけ発生し、無変更再実行で`Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`。
- 続けて`run-safe-debug-check.cmd -InstallToDevice`: `Preflight / Build / UnitTest / Lint / Install / Success`。本番packageのDB・元WebP・JPEG・RGB_565 pack・設定・認証情報を初期化していない。
- Macrobenchmarkは対象外で実行しない。

## 2026-07-31 production Morph integration（履歴: 現行経路では不使用）

| Requirement | Evidence | Status |
|---|---|---|
| Explicit Production mode on the normal non-selection, non-progress grid | `MediaGridMorphCanvasMode.ProductionVisible`, `MediaGridMorphGestureMode.Production`, and the `MediaGridMorphProductionHost` gate | covered |
| Missing prepared pair or resident viewport asset falls back once at release | `isMediaGridMorphProductionReady`, direct initial-to-release fallback, and resident-readiness Compose test | covered |
| One underlying LazyGrid with event-driven target geometry handoff | `MediaGridMorphProductionHost` and `productionHostUsesTheSameLazyGridAndRemovesCanvasAfterHandoff` | covered |
| Scroll, cell interaction, checkpoint, retention, stale identity, rollback, and lifecycle safety | Production host suppression/owner protection plus existing handoff, rollback, checkpoint, and identity tests | covered |
| Legacy production pinch modifier removed while common Test/Production input remains | `MediaGridRenderingContractTest` and source-level absence of `mediaGridPinchToResize` in production UI | covered |
| Normal Activity live pinch composes the production Canvas | `MainActivityComposeTest.normalClassifiedGridComposesProductionCanvasDuringLivePinch` uses the real classified screen and two-pointer input; Canvas is observed mid-gesture | covered (automated; no manual screen capture available) |

Final verification is required through the safe integration and safe debug-install entry points before commit.

## 2026-07-30 bounded Morph prepared pair foundation（履歴: 現行経路では不使用）

| 対象 | 実装・証跡 | 状態 |
|---|---|---|
| 同一Asset追跡廃止、行・column slot、右端幅0 | `MediaGridMorphSlot`、`MediaGridMorphTest.fourToFiveUsesRowAndColumnSlotsWithoutTrackingAssetAcrossRows`、5→4・代表列境界test | Unit Test完了 |
| Asset ID、rect補間、画像Crossfade、snapshot内重複禁止 | `buildMediaGridMorphSlots`、slot interpolation／layer／uniqueness test | Unit Test完了 |
| ordinal境界優先header、追加・削除、title Crossfade、全幅geometry | `buildMediaGridMorphHeaderBands`、日→週・週→月・いいねbucket test | Unit Test完了 |
| visible＋上下2行、1万件bounded範囲、偽header防止、2／12境界 | `captureMediaGridMorphInput`、`mediaGridMorphOrdinalRange`、bounded／mid-bucket／boundary test | Unit Test完了 |
| 初期・idle一回、pixel offset除外、scroll／pointer抑止 | `MediaGridMorphPreparationCache`、idle lifecycle test、`TagHierarchyUiV2.kt`のidle `snapshotFlow` | Unit Test・静的契約完了 |
| frame／column／viewport／sort／revision stale拒否 | generation token、identity照合、stale publish test | Unit Test完了 |
| production非描画、既存pinch／resident／viewport／queue／publication維持 | `MediaGridRenderingContractTest.morphPreparationIsBoundedIdleOnlyAndDoesNotEnableRendering`、既存回帰suite | 隔離integration・本番安全上書きSuccess |

- `run-safe-integration-check.cmd`: `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`。
- `run-safe-debug-check.cmd -InstallToDevice`: `Preflight / Build / UnitTest / Lint / Install / Success`。本番packageのDB・元画像・JPEG・RGB_565 pack・設定・認証情報は初期化していない。
- Macrobenchmarkは今回の対象外で実行しない。

## 2026-07-29 viewport boundary and active window optimization

- frame ordinal index、header除外、重複assetの代表Map、viewport signatureの境界・geometry比較、active ordinal範囲、warm-up上限をunit testで固定した。
- production sourceの静的契約として、viewportの旧List/Set/IntArray・item lookup・cast、controllerの二重ordinal index、active membership Set、active snapshotのitem参照を検出するテストを追加した。
- scheduler／worker数／urgent予約／先読み上下3行／publication pacingを変更していないことを契約テストで確認する。
- 指定実機検証は隔離統合テスト成功後に本番安全上書きチェックを実行する。Macrobenchmarkは実行しない。

## 2026-07-29 idle anchor persistence optimization

| 対象 | 実装・証跡 | 状態 |
|---|---|---|
| 初期falseを除外したtrue→falseのみの通常checkpoint | `MediaGridScrollCheckpointState`、`mediaGridScrollCheckpointTransition`、`MediaGridSessionCoordinatorTest.scrollCheckpointOnlyFiresOnObservedTrueToFalse` | 完了 |
| 毎scroll anchor state/save廃止 | `TagHierarchyUiV2.kt`、静的確認 | 完了 |
| session key指定・同値skip・非publish保存 | `MediaGridSessionCoordinator.saveAnchor(sessionKey, anchor)` | 完了 |
| session A/B分離・inactive target保存・同値保存非publish | `MediaGridAnchorPersistenceIntegrationTest.explicitAnchorSaveStaysWithTargetSessionAndEqualSaveDoesNotPublish` | 完了 |
| header除外・一回走査・collection/Offset allocation除去 | `captureClassifiedMediaGridScrollAnchor`、`MediaGridFrameData.assetIdByItemKey` | 完了 |
| restoration/handoff/lifecycle/disposal checkpoint | `EnhancedClassifiedScreen` の明示checkpoint経路 | 要Compose・実機確認 |
| 既存viewport、scheduler、resident Canvas、pointer input | 対象ソース差分の静的確認 | 要最終確認 |

## 2026-07-28 resident Canvas通常表示切替

- `MediaGridResidentDrawHandle.directDrawEligible`を追加し、offscreen先読み完了・通常publication後・初回warm-up公開後だけeligibleになる経路を実装した。visible中の新規loadはpublication前falseを維持し、publication後にmarkする。
- eligible判定とframe publication demand除外はimmutable draw indexのlock-free lookupを使用する。restore、LRU touch、列数変更、source invalidationはeligible規則を壊さない。
- `MediaGridResidentCanvasMode.Enabled`と`TestVisible`は同じLazyGrid自身の`drawWithCache` DrawModifierを使用し、resident画像を先に描画して`drawContent()`でheader・overlay・操作UIを上に残す。通常画面は`Enabled`を明示し、defaultは`Disabled`。
- residentセルでは背景を透明にし、Placeholder・Error・AsyncImageを構成せず、nonresidentセルは従来経路を維持する。
- 300個の異なる256x256 RGB_565画像をeligible retainし、jump後visible画像のcommand数、300entry、eligible 300件、48MiB以下をCompose/Integration Testで確認した。301件目eviction、source invalidation、既存のlike数・動画badge・選択・header・操作・2〜12列テストは既存suiteで継続確認した。
- 指定入口の実績: `run-safe-integration-check.cmd` は `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`、`run-safe-debug-check.cmd -InstallToDevice` は `Preflight / Build / UnitTest / Lint / Install / Success`。
- Macrobenchmarkは実行していない。300entry、48MiB、先読み3行、worker、queue、候補順、Progress、scroll保持は変更していない。

## 2026-07-28 resident draw index基盤

- `MediaGridResidentImageIdentity`、immutable `MediaGridResidentDrawHandle`、`AtomicReference`公開の`MediaGridResidentDrawIndex`を追加した。lookupはasset ID補助mapとcandidate identityを使い、store lockと画像生成・Coil restoreを行わない。
- retain、candidate置換、eviction、asset invalidation、memory trim、clearはmutable storeとdraw indexを一回の整合更新として公開し、restoreとvisible touchではindex versionを増やさない。
- `MediaGridRetainedImageStoreIntegrationTest`に、別インスタンスの256×256 RGB_565 Bitmap 300件、301件目のLRU eviction、identity lookup、lock-free seam、固定barrier順序のretain／lookup／invalidate／protection／trim／restore競合を追加した。
- ローカル安全検証は`run-safe-debug-check.cmd`のBuild / UnitTest / Lint / Successを確認済み。`run-safe-integration-check.cmd`でPreflight / Build / UnitTest / Lint / Install / IntegrationTest / Successを確認済み。

## 2026-07-28 resident single Canvas layer

| Requirement | Evidence | Status |
|---|---|---|
| explicit Disabled/TestVisible mode and production default | `MediaGridResidentCanvas.kt`, `TagHierarchyUiV2.kt` | Implemented |
| conflated draw-index version notification | `MediaGridRetainedImageStore.drawIndexVersionFlow`, `MediaGridRetainedImageStoreIntegrationTest.drawIndexVersionFlowOnlyPublishesContentChanges` | Implemented |
| non-copying identity/value keyed ImageBitmap adapter | `MediaGridResidentCanvasImageAdapter`, `MediaGridResidentCanvasIntegrationTest` | Implemented; wireless integration passed |
| visible-only geometry, centered crop, immutable commands, one Canvas | `MediaGridResidentCanvas.kt`, `MediaGridResidentCanvasTest`, `MediaGridResidentCanvasComposeTest` | Implemented; wireless integration passed |
| existing production AsyncImage/frame/queue/worker/prefetch path unchanged | `git diff`, `TagHierarchyUiV2.kt`, `MediaGridSteadyLoadController.kt` | Static audit passed |
| integration and production safety verification | `run-safe-integration-check.cmd`, `run-safe-debug-check.cmd -InstallToDevice` | Success; production package metadata PostCheck passed |

## 2026-07-25 frame-paced image publication

| 要件 | 証跡 |
|---|---|
| internal/published state分離、公開待ちdemand、中央優先・下方向tie-break・同candidate/画面外skip | `MediaGridSteadyLoadController.kt`、`MediaGridSteadyLoadControllerTest.readyAttachmentOrderUsesVisibleCenterThenDownwardTieBreakAndSkipsPublished` |
| fake frame clock、1frame最大1件、12件を12frame以内に公開 | `MediaGridSteadyLoadControllerTest.fakeFrameClockPublishesAtMostOneNewAttachmentPerFrameAndFinishesInTwelveFrames`、`MediaGridFramePublicationComposeTest`、`MediaGridSteadyLoadControllerIntegrationTest.postStartupTwelveVisibleReadyAssetsUseAtMostOneAttachmentPerFrame` |
| frame callback内のIO・Bitmap loadなし、画像test tag | `MediaGridFramePublicationRunner`、`MediaGridSteadyLoadController.publishOneReadyImageForFrame`、`media_grid_image_<assetId>` |

- Wireless隔離検証は `scripts\run-safe-integration-check.cmd` のPreflight / Build / UnitTest / Lint / Install / IntegrationTestをSuccessで完了した。Macrobenchmarkは要件どおり未実行。

## 2026-07-24 第16実装: decoupled load and UI publication pipeline

## 2026-07-24 viewport hot path改善

## 2026-07-24 cache-hit starvation fix coverage

- Isolated controller integration tests use fake metadata and bitmap gateways, state snapshots, and `assertConsistentState()`; they cover cache-hit request suppression, mixed cache/miss, metadata terminal failure, 1,000 cache-hit assets, and a fixed-seed 1,000-operation state machine.
- The harness also covers metadata exceptions, all-candidate failure, 300-signal publication while paused, cache-hit runs of exactly 100 assets, full-range viewport revisits, cache eviction, and frame-key/invalidation races across column counts.
- The pre-fix controller worktree reproduction used the same viewport cell size as the real request path and failed at `MediaGridSteadyLoadControllerPreFixReproductionIntegrationTest.cacheHitPreparedAssetMustBecomeReady` with `expected:<Ready> but was:<Pending>`; the fixed controller integration test now passes the corresponding Ready/no-request assertion.
- The controller keeps distance priority, BitSet pending representation, worker limits, urgent reservation, watermark, candidate order, RGB_565 pack, progress, column changes, and scroll/session retention unchanged.

## 2026-07-24 ordinal background queues

| 対象 | 実装・証跡 |
| --- | --- |
| frame media cell ordinalとasset／item index双方向O(1)参照 | `MediaGridOrdinalIndex`、`buildMediaGridOrdinalIndex`、`MediaGridSteadyLoadControllerTest.mediaOrdinalIndexMatchesMediaCellsAndProvidesBothDirections` |
| metadata／Bitmapの分離BitSet pending、nearest順、同距離の下方向優先 | `MediaGridOrdinalPendingSet`、`MediaGridSteadyLoadControllerTest.ordinalPendingSetUsesNearestOrdinalAndPrefersLowerScreenDirectionOnTie` |
| watermark前peek、cache hit Ready、urgent FIFO、invalidation対象bit | `MediaGridSteadyLoadController`、UnitTest、静的確認 |
| 大量データ、高速viewport、画面外完了、fallback、Progress、列数変更、画面復帰、scroll保持 | 既存隔離Compose／Repository／LargeDataset／RGB565 Integration Test群 |

検証結果:

- `scripts\run-safe-integration-check.cmd`: `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`
- `scripts\run-safe-debug-check.cmd -InstallToDevice`: `Preflight / Build / UnitTest / Lint / Install / Success`
- 本番DB、元WebP、JPEG、RGB_565 pack、設定、認証情報の初期化・変更は行っていない。

| 対象 | 実装・証跡 |
| --- | --- |
| updateViewportのlatest anchor/epoch/conflated signal限定 | `MediaGridSteadyLoadController.updateViewport`、静的確認、UnitTest |
| epoch単位のactive snapshotとO(1) membership | `MediaGridActiveWindowSnapshot`、`buildMediaGridActiveWindowSnapshot`、`MediaGridSteadyLoadControllerTest.activeSnapshotKeepsVisibleAndActiveOrderAndMembership` |
| asset record/tokenによる重複抑止・stale skip・urgent昇格 | `AssetQueueRecord`、controller worker/anchor consumer、既存controller UnitTest契約 |
| RGB_565/4byte容量見積もり | `mediaGridEstimatedBitmapBytes`、`bitmapMemoryEstimateUsesOutputConfigAndLongArithmetic` |
| 高速viewport、画面外完了、cache再表示、fallback、Progress、列数変更、画面復帰、scroll保持 | `MainActivityComposeTest`、`UiStateRenderingTest`、`MediaGridRgb565IntegrationTest`、隔離Integration Test（指定検証で確認） |

検証結果:

- `scripts\run-safe-integration-check.cmd`: `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`
- `scripts\run-safe-debug-check.cmd -InstallToDevice`: `Preflight / Build / UnitTest / Lint / Install / Success`
- 本番DB、元WebP、JPEG、RGB_565 pack、設定、認証情報の初期化・変更は行っていない。

| 対象 | 実装・証跡 |
| --- | --- |
| metadata/Bitmap/UI publicationのqueue・consumer分離 | `MediaGridSteadyLoadController`、controller unit test、既存Compose grid tests |
| tick・固定Delay・sleep・polling除去、空きworker即時開始 | Channel wake、worker pool、`run-safe-debug-check.cmd` Build/UnitTest/Lint Success |
| 総数4、background2、urgent予約2、hidden Bitmap1 | controller constants、`MediaGridSteadyLoadControllerTest` |
| viewport priority、開始済みlocal task継続、frame全体metadata | latest anchor、active-window/scroll integration regression、controller source contract |
| 75%停止・65%signal再開 | `mediaGridBackgroundBitmapAllowed`、`mediaGridMemoryWatermarkAllowsResume` unit test |
| visible/前後1行urgent、background network禁止、offscreen completion非公開 | controller lane selection、existing grid placeholder/error and scroll integration tests |
| session/frame/Progress/列数/scroll/RGB565/JPEG/DB維持 | existing session, Compose, RGB565, repository integration tests; no changes to those paths |

検証結果:

- `scripts\\run-safe-debug-check.cmd`: `Preflight / Build / UnitTest / Lint / Success`
- 隔離実機統合テストと本番安全上書きは、コード・文書更新後に実行する。

## 2026-07-23 第14実装: RGB_565 fixed-slot pack

| 対象 | 実装・証跡 |
| --- | --- |
| asset IDのみで128slot pack addressを決定 | `MediaGridRgb565PackStore`、`MediaGridRgb565PackStoreTest` |
| 二重bank、generation、payload→metadata順の永続化 | pack store fault-injection Unit Test、片bank破損/旧bank保持テスト |
| source Bitmap→raw、WebP・Asset・JPEG維持 | `ClipRepository`、`RepositoryIntegrationTest` |
| DecoderなしRGB_565 Coil表示、raw→JPEG fallback | `MediaGridRgb565Coil`、`MediaGridRgb565IntegrationTest`、candidate order Unit Test |
| 最大4pack mapping、同pack直列、固定Delayなし | pack store Unit/Integration Test、source contract test |
| 初回raw 4、通常2、repair 2、phase13維持 | controller既存定数・session tests、Fetcher/repair実装 |
| TEST_HARNESS分離 | `MediaGridRgb565IntegrationTest`、`RepositoryIntegrationTest` |

検証結果:

- `scripts\run-safe-integration-check.cmd`: `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`
- `scripts\run-safe-debug-check.cmd -InstallToDevice`: `Preflight / Build / UnitTest / Lint / Install / Success`
- production DB、元WebP、既存JPEG、設定、認証情報の初期化は行っていない
- Macrobenchmarkは要件どおり未実行

## 2026-07-23 第13実装: session persistence（検証済み）

- `MainViewModel`配下の`MediaGridSessionCoordinator`がfilter/sortだけのsession keyで最大2件をLRU保持し、frame・controller・anchorをComposable離脱後も保持する。
- `MainScreen`上位のsaveable `LazyGridState`を分類済みメディアグリッドへ渡し、タブ・設定・カード表示から戻る際にsession anchorを復元する。
- 初回warm-upはviewport→下方向1画面→下方向2画面、最大128asset/32MiB、metadata・startup request最大4、2.5秒で通常controllerへ引き継ぐ。通常loopは50ms・request最大2を維持する。
- 列数変更とsource revision更新はframeをnullにせず、既存frame表示中に新frameを構築してcontrollerを更新する。persistent previewのmemory-cache Ready stateを保持する。
- Unit Test: `MediaGridSessionCoordinatorTest`、`MediaGridSteadyLoadControllerTest`。安全Integration TestはBuild・UnitTest・Lint・Install・IntegrationTestの全フェーズSuccess。

## 2026-07-22 第12実装: steady-load controller

| 対象 | 実装・証跡 |
| --- | --- |
| Progress中のviewport＋下方向2画面、128件・32MiB上限、永続JPEG/local warm-up | `MediaGridSteadyLoadController`、`MediaGridSteadyLoadControllerTest` |
| metadata・startup request最大4件、全terminalまたは2.5秒でReady、未完了引き継ぎ | controller startup state machine、共有`ImageLoader` |
| viewportはlatest anchor上書きのみ | `TagHierarchyUiV2.snapshotFlow`→`updateViewport`、anchor equality Unit Test |
| 50ms単一loop、metadata 2・request 1・completion 4・同時2 | controller constants/tick、Unit Test |
| 表示中＋前後1行、距離cursor、範囲外cancel | `selectMediaGridActiveWindow`、controller cursor/tick、Unit Test |
| Pending/Loading Placeholder、controller候補fallback、memory cache確認後Ready | `MediaGridCellLoadState`、`ClassifiedMediaGridCell`、既存Compose/Integration Test |
| frame専用metadata、dispose/stale破棄、asset単位preview無効化 | controller generation/frame lifecycle、`MediaGridPreviewNotifier` |
| DB・元画像・JPEG生成・Coil容量/並列・UI操作・Macrobenchmark | 変更なし。Macrobenchmarkは要件により未実行 |

2026-07-22の最終実装に対し、`run-safe-integration-check.cmd`はBuild・UnitTest・Lint・Install・IntegrationTestの全フェーズSuccess。続けて`run-safe-debug-check.cmd -InstallToDevice`はBuild・UnitTest・Lint・Installの全フェーズSuccess。本番packageのDB・元画像・生成済みJPEG・設定・認証情報は初期化していない。Macrobenchmarkは要件どおり未実行。

## 2026-07-22 第11実装: retired image pipeline removal

| 要件 | 証跡 |
| --- | --- |
| 廃止済みgenerator/store/state/coordinatorと専用test/fake/fixtureが全source setに存在しない | repository-wide static search、削除済みファイル履歴 |
| 永続JPEG → local → preview → remote → displayを唯一の候補順として維持 | `MediaGridDirectPreview.kt`、`MediaGridDirectPreviewTest.kt` |
| 初期範囲と次1行preload、重複抑止、取消、完成通知、破損回復を維持 | `MediaGridPreviewPreloader`、`MediaGridPreviewNotifier`、unit/integration tests |
| AppContainerが現行preparerとWorkManager enqueuerだけを構築 | `AppContainer.kt`、`TestEnvironmentIsolationTest.kt` |
| benchmark snapshotが永続JPEGを読み取り専用コピーし、本番DB・元画像・永続JPEGのhashを前後比較 | `BenchmarkSnapshotImporter.kt`、`run-safe-macrobenchmark-check.ps1` |
| 廃止済みcacheへアクセス・cleanupせず、Coil cacheとWorkManager設定を変更しない | repository-wide static search、現行設定差分なし |

指定順で `scripts\run-safe-integration-check.cmd` の Build・UnitTest・Lint・Install・IntegrationTest と、続けて `scripts\run-safe-debug-check.cmd -InstallToDevice` の Build・UnitTest・Lint・Install がすべて成功しました。Macrobenchmarkは要件どおり実行していません。

## 第9実装: persistent preview display and preload

| 要件 | 証跡 |
| --- | --- |
| 永続JPEGをlocalより前へ追加、古い／空／不正形式を除外、256×256専用key・disk cache無効 | `MediaGridDirectPreview.kt`、`MediaGridDirectPreviewTest.kt` |
| 初期表示は実表示範囲、layout前は最大`columnCount * 6`、次行は最大列数 | `selectMediaGridInitialPreloadIndices`、`selectMediaGridAdjacentPreloadIndices`、`MediaGridDirectPreviewTest.kt` |
| preload重複排除、memory cache hit、viewport／方向変更時の取消 | `MediaGridPreviewPreloader`、`MediaGridDirectPreviewTest.kt`、`TagHierarchyUiV2.kt` |
| worker完成通知と対象assetだけの再準備 | `MediaGridPreviewNotifier`、`MediaGridPersistentPreviewIntegrationTest.kt`、`TagHierarchyUiV2.kt` |
| 破損JPEGのfallbackと同一identityの一回限り回復予約 | `MediaGridPreviewRecoveryGate`、`MediaGridDirectPreviewTest.kt`、`ClipRepository.recoverMediaGridPreview`、`MainActivityComposeTest.kt` |
| 初期グリッド表示と共有memory cache | `MainActivityComposeTest.classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation` |

生成仕様、DB、元画像、既存cache、共有ImageLoader容量／decoder数、Macrobenchmarkは変更していません。検証順は隔離統合チェック後に本番安全上書きチェックとします。Macrobenchmarkは今回実行しません。

## 2026-07-20 第8実装: persistent JPEG preview

| 要件 | 実装・証跡 |
| --- | --- |
| 新規local assetだけをasset ID由来の256×256 JPEGへ変換 | `MediaGridPersistentPreviewStore`、`ClipDao.insertAssets`戻り値、`MediaGridPersistentPreviewIntegrationTest` |
| bounds/sample decode、中央crop、quality 80、UIスレッド非使用 | `MediaGridPersistentPreviewStoreTest`、`MediaGridPersistentPreviewIntegrationTest`、WorkManager CoroutineWorker |
| 一時ファイル・原子的置換・既存JPEG保護 | `MediaGridPersistentPreviewStore`、stale/invalid output integration test |
| unique non-expedited WorkManager、batch直列、storage-not-low | `MediaGridPreviewWork`、`MediaGridPersistentPreviewIntegrationTest` |
| runtime/publication直前のasset再確認、削除・localPath変更競合 | Store共有公開ロック、worker integration test |
| DB schema、元画像、既存cache、グリッド経路、Coil、Macrobenchmark非変更 | `LikeListDatabase.kt`、既存メディアグリッド差分、指定順検証 |

指定順検証結果:

- `scripts\run-safe-integration-check.cmd`: `Preflight / Build / UnitTest / Lint / Install / IntegrationTest / Success`
- `scripts\run-safe-debug-check.cmd -InstallToDevice`: `Preflight / Build / UnitTest / Lint / Install / Success`
- Macrobenchmarkは実行していない。

`実機レベル統合テスト強化 要件定義.md` に対する、現在の自動テスト・安全実行スクリプト・実機確認の対応状況です。

## 2026-07-19 第6実装: direct preview pipeline

| Requirement | Status | Evidence |
|---|---|---|
| local → preview → remote → unique display fallback | Implemented | `MediaGridDirectPreview.kt`, `MediaGridDirectPreviewTest.kt` |
| missing local fallback, source reset, final Error only after all candidates fail | Implemented | `MediaGridDirectPreviewTest.kt`, `MediaGridPlaceholderRenderingTest.kt` |
| constraint-sized AsyncImage, direct visible requests during drag/fling, composition cancellation | Implemented | `TagHierarchyUiV2.kt`, `MainActivityComposeTest.kt` |
| shared Coil loader, 128 MiB disk, min(totalMem/8, 64 MiB) memory, two decoder slots, no crossfade | Implemented | `AppContainer.kt` |
| stable source/size cache key and local path/length/mtime identity | Implemented | `MediaGridDirectPreview.kt`, `MediaGridDirectPreviewTest.kt` |
| one adjacent row, direction/viewport cancellation, no visible/prefetch duplicate | Implemented | `MediaGridDirectPreview.kt`, `MediaGridDirectPreviewTest.kt` |
| retired generator, cache restore, operation gate, and broad scheduler removed from every source set | Implemented | current source tree static search; `TagHierarchyUiV2.kt` |
| initial multi-cell load, drag/fling, recreation/filter/sort, mixed source/error regression | Implemented | `MainActivityComposeTest.kt`, `UiStateRenderingTest.kt`; wireless safe integration check succeeded |
| required verification order; Macrobenchmark not run | Implemented | `run-safe-integration-check.cmd` and `run-safe-debug-check.cmd -InstallToDevice` succeeded; Macrobenchmark was not run |

## 安全境界

| 要件 | 状態 | 証跡 |
|---|---|---|
| 本番/テスト applicationId、UID、DB、画像、preferences 分離 | 完了 | `verifyTestEnvironmentIsolation`、`TestEnvironmentIsolationTest`、`run-safe-integration-check.ps1` |
| 本番 OAuth/X API をテストから利用不可 | 完了 | `DisabledOAuthGateway`、`DisabledXApiGateway`、loopback-only network config、設定画面の login 無効化 |
| 同一 SC-56C で本番・テスト共存、本番 metadata 不変 | 完了 | 安全スクリプトの pre/post check。本番 UID 10413、テスト UID 分離 |
| 本番 snapshot を片方向・一時コピーだけで検証 | 一部完了 | 実DB snapshotのhash不変は確認済み。実画像backupは未提供のためfixtureで機構のみ確認 |

## 大規模テスト構成

| # | 項目 | 状態 | 主な証跡 / 残件 |
|---:|---|---|---|
| 1 | テスト用アプリ分離 | 完了 | `TestEnvironmentIsolationTest` |
| 2 | バックアップ/コピー | 一部完了 | `SnapshotCompatibilityTest`。実画像backupでの最終確認が残る |
| 3 | DB/Repository 整合性 | 主要項目完了 | Room migration、同期、タグ削除、保存先失敗、Repository Flow経由の複合検索DB一気通貫、Repository経由のタグ/グループ全移動操作 |
| 4 | データ破壊防止 | 主要項目完了 | 手動概要、タグ・分類保持、検索非変更、削除時投稿保護、画像失敗、保存先失敗 |
| 5 | Fake X API 同期 | 主要項目完了 | pagination、401/403/429/500、refresh失敗、中断再開、欠落/不正JSON、mixed media |
| 6 | API料金/rate limit | 主要項目完了 | production URL遮断、呼出履歴、月間停止、429無限retry防止 |
| 7 | 検索/絞り込み | ロジック完了・主要UI完了 | 全検索target、regex、期間、投稿者、タグ複合、件数summary、非変更。filter dialogのquery適用、日付条件適用/DatePicker内解除/クリア、投稿者Dialogクリア、タグ条件のみクリア、投稿者＋タグ条件の複合E2E、Cancel/Back破棄、Dialog内全クリア確認キャンセル、全クリアE2Eを確認 |
| 8 | 分類操作 E2E | 主要項目完了 | 付与・解除、DB・件数、popup非伝播、別グループ同名タグの複数同時付与を確認 |
| 9 | タグ/グループ管理 E2E | 主要項目完了 | 作成、タグ/グループ作成Dialogキャンセル、同名子タグ、tag名称変更/削除、group名称変更/削除、名称変更Dialogキャンセル、タグ/グループ削除Dialogキャンセル、タグの別グループ移動UI、タグ/グループ移動Dialogキャンセル、別タグへの一括追加、別タグへの一括追加Dialogキャンセル、循環/順序ロジックを確認 |
| 10 | スクロール/大量表示 | 主要項目完了 | SC-56C実機で1,000件末尾/先頭UI、10,000件Room、50件リスクseedを確認 |
| 11 | Macrobenchmark | 構成更新・実機再確認待ち | `app/src/benchmark` の専用Activity/importer/metricsと `scripts/run-safe-macrobenchmark-check.cmd`。本命 `app/src/main` から計測依存を除去し、実機測定は再確認が必要 |
| 12 | 画像保存/圧縮 | 主要項目完了 | WebP、重複download防止、失敗時投稿保持、複数画像の別WebP保存、破損画像のfailed asset記録、snapshot |
| 13 | 設定/安全装置 | 主要項目完了 | 設定画面の使用量表示、test login無効、X API設定の保存/trim/消去UI、暗号化設定roundtrip、無効保存先rollback、設定画面の開閉とDB不変、保存先移動確認cancel UIを確認 |
| 14 | エラー表示/復旧 | 主要項目完了 | 空、未login、HTTP/timeout/JSON、権限不足UI、画像/保存先、continuation再開、Activity再作成後のタブ/フィルター復元、保存先移動copying中断とswitched切替先不可の起動時復旧を確認 |
| 15 | UI状態・軽微バグ | 主要項目完了 | 主要tab、設定画面、dialog、件数、loading、empty、popup、LazyColumn、画面再作成、filter Cancel/BackHandler破棄、filter Dialog内全クリア確認キャンセル、filter DatePicker内解除、filter 投稿者Dialogクリア、画像viewerの表示/閉じる操作/戻る閉じ/複数画像swipe、ローカル削除Dialogのcancel/soft delete、タグ/グループ作成Dialogキャンセル、名称変更Dialogキャンセル、タグ/グループ削除Dialogキャンセル、タグ/グループ移動Dialogキャンセル、別タグへの一括追加Dialogの実行/キャンセル、概要編集の保存と再作成後復元、投稿者クリックから分類済み投稿者フィルターへの遷移とDB不変、タグ条件のみクリア、いいね数ポップアップの詳細/暫定警告表示とDB不変、設定画面のX API設定保存/消去導線、設定画面/結果Dialogの安定操作、いいね数更新確認cancel、ローカル削除DialogのtestTag操作、スクロール後の検索条件維持、スクロール後の未確定タグ選択維持を確認 |
| 16 | 回帰テスト枠 | 運用開始 | popup外tap、二重同期、continuation、保存先失敗を回帰化。発見ごとに追加 |
| 17 | Property-based 候補 | 主要項目完了 | JUnit固定seedの250パターンでslot移動の一意性・集合保存・位置を検証。同期不変条件としてローカル削除済み投稿の非復活、固定seed複数ページ同期の投稿ID一意性・使用量加算・continuation消去を確認 |
| 18 | メディアグリッド複数選択 | 主要項目完了 | 0件維持、×／戻る終了、0件時タグ編集無効、2〜6列Dialogボタン、7〜12列非表示、同一clipId選択同期、選択画像本体非変更、単一／複数画像チェック色、開始時のみハプティックを実装・単体／Compose／隔離実機ゲートで確認 |

## 合格ゲート

- 通常必須: debug／integration共通Build task集合、`testDebugUnitTest`、`lintDebug`。phase別入力と成功stateにより自動省略し、main入力不変時の変更unit test classだけは限定実行できる。判断不能時はphase全体へ戻る
- 実機必須: `verifyTestEnvironmentIsolation`、`connectedIntegrationTestAndroidTest`
- 安全実行入口: `scripts/run-safe-integration-check.cmd`
- USB／wireless共通の安全実行入口: `scripts/run-safe-integration-check.cmd`
- Macrobenchmark安全実行入口: `scripts/run-safe-macrobenchmark-check.cmd`
- snapshot任意入口: `scripts/run-safe-snapshot-check.cmd`
- 2026-07-30: 同一SC-56Cの重複wireless endpointをhardware serial一致確認後に整理した。ADB serverをmDNS自動接続無効で再起動し、同じ設定を持つ`run-safe-debug-check.cmd -InstallToDevice`がSuccess。終了後はSDK platform-toolsのADB server 1process、USB device entry 1件だけ
- 2026-07-30: debug／integrationを共通Build task集合と`app-build`・`app-unit-test`・`app-lint` stateへ統一した。debug成功後の3 stateをintegrationが更新せず再利用し、Install・IntegrationTestは通常実行してSuccess
- 2026-07-30: unit testファイルだけの一時的な非機能変更ではBuild・Lint stateを維持し、`com.lyco256.llm.data.TagColorPaletteTest`だけを実行してSuccess。変更を完全に戻した際も同classの部分UnitTestがSuccessし、検証用ソース差分は残していない。`-FullRebuildTest`は使用していない

## 2026-07-01 追記

## 2026-07-12 メディアグリッド要件確認

| 対象 | 状態 | 証跡 |
|---|---|---|
| MIXEDタグ集約・pending遷移 | 完了 | `aggregateBulkTagStates`、`bulkTagPendingAfterToggle`、`TagHierarchyTest` |
| 一括タグ適用確認・選択維持 | 完了 | `UiStateRenderingTest`、`TagHierarchyUiV2.kt` |
| 見出し粒度・週期間表示 | 完了 | `TagHierarchyTest`、`buildClassifiedMediaGridItems` |
| 実データ確認 | 未実施 | ユーザー指定により対象外 |

- 設定/安全装置とUI状態系の追加E2Eとして、`top_settings_button` から `settings_screen` を開き、設定画面内の `settings_*` test tagでX API設定、同期、使用量、データ管理の導線を確認する。
- 未ログイン同期エラーの表示/閉じる操作前後でDB fingerprintが変わらないことを確認し、隔離テスト環境で同期エラー導線が実データへ影響しないことを補強する。
- いいね数更新の確認Dialogを `settings_like_refresh` から開き、`settings_like_refresh_cancel` で閉じる前後のDB fingerprint不変を確認する。
- 投稿カードのローカル削除Dialogを `clip_local_delete_*_<clipId>` で操作するよう固定し、キャンセル保持と確定時soft deleteのE2Eを文言依存から外す。
- `scroll_to_top` を使い、分類済み検索条件がスクロール後も維持されること、未分類の未確定タグ選択がスクロール後も分類確定まで維持されることを確認する。

実画像backupの最終確認が残っている間は、要件定義全体を「完了」と判定しません。
# メディアグリッド高速化の検証

## 2026-07-13 メタデータ最終整理

| 対象 | 状態 | 証跡 |
|---|---|---|
| sourceのTagEntity除去、3 Flow、ClipTag一括LongArray、revision境界 | 完了 | `ClipRepository.kt`、`RepositoryIntegrationTest`、`TagHierarchyTest` |
| 実効sort判定とsort準備の分離 | 完了 | `MediaGridMetadata.kt`、`TagHierarchyTest` |
| 投稿者キー前計算、正規化cache key、cache hit即Ready | 完了 | `MediaGridMetadata.kt`、`MainActivity.kt` |
| Asset展開とmatchingMediaCount/メディア有無の一括確定、hasLocalFile除去 | 完了 | `TagHierarchyUiV2.kt`、`UiStateRenderingTest` |
| 通常安全検証 | 完了 | `run-safe-debug-check.cmd` Success |

## 2026-07-13 廃止済み広範囲生成（履歴）

## 2026-07-13 card/grid duplicate-work removal

- ローカル安全確認: `run-safe-debug-check.cmd` のBuild / UnitTest / Lint / Successを確認。
- 実装確認対象: MainUiState遅延評価、カード経路限定scroll key、明示的source revision、タグ構造revision、source更新時前計算、LongArrayタグ保持、グリッドmatchingClipCount表示。
- 隔離実機: `run-safe-integration-check.cmd` を本命上書き前に実行する。

- These generator/scheduler details are retired and kept only as historical context. The current path is covered by the direct-preview and persistent-preview sections at the top of this file.
- `run-safe-debug-check.cmd` passed Build, UnitTest, and Lint after the change. Isolation-device verification remains required before production overwrite.

隔離実機チェックは`run-safe-integration-check.cmd`で実施し、Build・UnitTest・Lint・IntegrationTestのSuccessを確認する。本命上書きは隔離チェック成功後に`run-safe-debug-check.cmd -InstallToDevice`で実施する。Paging、低解像度サムネイル、画像処理キューは対象外。
## 2026-07 single-step media-grid resize（履歴: 現行経路では不使用）

- Pinch direction recognition changes the column count by exactly one within 2–12 and locks further changes until all fingers are released, including reverse movement.
- The animation starts after a small direction dead zone and applies only to currently composed media cells without a second grid; the central Asset remains anchored across header changes.
- Column changes do not regenerate or refetch completed previews or rebuild the ordered source snapshot.
- The historical scheduler described here is deleted. Current missing-file and decoded-JPEG failure behavior is covered by `MediaGridDirectPreviewTest` and `MediaGridPersistentPreviewIntegrationTest`.

## 2026-07 廃止済み画像cache（履歴）

## 2026-07-13 廃止済み画像状態遷移（履歴）

- Revision is `Long` from repository snapshot through UI; no `Int` conversion remains.
- 当時のAsset単位StateFlowとgeneration tokenは削除済みです。現行はrender keyとprepared candidate identityでstale結果を拒否します。
- The retired broad preparation path is deleted; current missing local files remain eligible for URL fallback when visible.
- Pinch detection uses a stable pointer-input key, reads the latest column count/callback, commits at most one column, and remains locked through recomposition and reverse motion until all pointers are released. Boundary gestures at 2 and 12 columns also lock.
- The grid creates the static skeleton gradient once and passes the shared Brush to cells.

## 2026-07 continuous media-grid morph foundation（履歴: 現行経路では不使用）

- `MediaGridMorphTest` covers the 2..12 adjacent-column bound, extreme-scale clamping, reversible progress, the 0.5 release threshold, one-shot target handoff, 4→5 zero-width right-edge slot, Asset correspondence, Header add/remove/title change, and bounded planning for 10,000 items.
- `MediaGridMorphPlan` keeps only viewport-neighborhood rows and related headers; progress updates reuse the immutable plan and do not touch the item list, image requests, files, or DB.
- The Compose path now slices the current viewport plus two rows before creating the target window, and applies the planned Asset anchor during target handoff.
- `TagHierarchyUiV2` uses a stable `pointerInput(Unit)`, keeps the actual grid column count unchanged during tracking, suppresses two-finger cell actions after morph start, and cancels stale plans when source revision or items change.
- Required device order was completed: isolated integration check succeeded, then production-package debug overwrite succeeded with package metadata invariance.

## 2026-07 seamless media-grid morph overlay（履歴: 現行経路では不使用）

- `MediaGridMorphTest` now covers linear Rect interpolation, bounded crossfade alpha, same-Asset single-layer rendering, and finite zero-width slots.
- `MediaGridMorphOverlay` remains absent in Idle and is composed only for the four active morph/handoff phases above the same normal grid. The overlay is viewport-plan bounded and does not create a second `LazyVerticalGrid`.
- The historical RenderModel used immutable plans and stable Asset keys. The overlay and its image observation are deleted from the product path.
- Target handoff dispatches the column callback once, keeps Overlay at progress 1, performs one anchor `scrollToItem` and one necessary `scrollBy`, then completes without a post-handoff anchor restore.
- `animateItem()` and the former cell `Animatable` resize scale are suppressed/removed for morph and handoff. Header background, height, and Y are interpolated in the overlay; video, like-count, selection, and error visuals are crossfaded with their Asset.

- `run-safe-debug-check.cmd`: Build、UnitTest、Lint 成功。

## 2026-07-14 final media-grid morph adjustment（履歴: 現行経路では不使用）

- `MediaGridMorphTest` covers continuous Header Y/height interpolation, added/deleted Header height endpoints, changed-title crossfade at progress 0.5, and the one-layer identical-title rule.
- The overlay uses only bounded Slot/Header backgrounds, maximum-height clipped Header nodes, stable Slot/Header keys, slot-width-derived badge metrics, and one existing Thumbnail StateFlow observation per Asset ID. The normal grid remains visible until preparation is complete.
- `MediaGridMorphUiState` applies handoff completion and overlay removal in one state update after the target grid has laid out; normal anchor restoration is blocked in all non-Idle phases.
- Safety verification completed: wireless isolated integration check, wireless production-package debug overwrite, and wireless Macrobenchmark all succeeded; the macrobenchmark PostCheck preserved production package metadata and kept benchmark UID separate.
- 実装: 256×256 JPEG quality 60、cacheDir再生成、inSampleSize縮小デコード、直列最新viewport優先、セル単位StateFlow、専用ImageLoader設定。
- `run-safe-integration-check.cmd`: Success。隔離packageでIntegrationTestまで完了。
- `run-safe-debug-check.cmd -InstallToDevice`: Success。本命packageへ安全に上書きし、スクリプトのpackage情報不変チェックを通過。

## 2026-07-14 実装22 coverage

| Requirement | Implementation / evidence | Status |
|---|---|---|
| Five modes and startup-only resolution | `app/src/benchmark/java/com/lyco256/llm/data/MediaGridBenchmark.kt`, `BenchmarkMainActivity` | Implemented only in benchmark source set; normal builds do not resolve modes |
| run-as-only read-only snapshot | `run-safe-macrobenchmark-check.ps1` | Implemented; unavailable run-as fails without fallback |
| No credentials/preferences | whitelist of DB/WAL/SHM, JPEG cache, and `files/images` | Implemented |
| target-only import and localPath rewrite | `BenchmarkSnapshotImporter` | Implemented |
| no network | benchmark network config and disabled gateways | Implemented |
| Trace/counters and separated paths | `app/src/benchmark/.../MediaGridBenchmark.kt`; no references from main manager/store/UI/Morph | App-side high-frequency Trace/counter hooks removed; benchmark output fields are empty/N/A |
| identical 5-iteration scroll/pinch scenarios | `MediaGridPerformanceMacrobenchmark` | Implemented in test source; runtime measurement not reached because snapshot precondition failed |
| report and deltas | safe macrobenchmark summary writer | Report generated; values are N/A because the target did not receive a snapshot/metric export |
| production invariance | safe script PostCheck metadata and data hashes | Verification path implemented; runtime DB/media hash comparison was not reached because production `run-as` is unavailable |

実装22では本番最適化（worker数、Coil/cache容量、生成間隔、viewport間引き、画像反映延期）を変更していない。

## 2026-07-17 simple column-change stabilization（履歴: 現行経路では不使用）

- Unit: `MediaGridMorphTest` covers threshold miss, pinch-in `+1`, pinch-out `-1`, final cumulative ratio after direction reversal, cancellation, 2..12 bounds, and the one-step limit through `mediaGridColumnCountAfterPinchRelease`.
- Integration: `MainActivityComposeTest.classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation` uses real two-pointer input to cover 4→5→4, threshold-miss no-op, repeated round trips, anchor position, immediate cell dialog interaction, post-change scroll, and absence of `media_grid_morph_overlay`.
- The product path no longer creates morph sessions or overlays during pinch. `MediaGridMorph.kt` and `MediaGridMorphOverlay.kt` remain retained components for later animation work.

## 2026-07-18 メディアグリッド スクロール改善 第2実装（廃止済み履歴）

| 対象 | 状態 | 証跡 |
|---|---|---|
| 当時のviewport/coordinator/scheduler | 削除済み | 実装6でsource、fake、fixture、専用テストを削除。現行証跡は冒頭のdirect preview節 |
| 高速fling、画像追従、filter revision、即時セル操作、既存列数変更 | 完了 | `MainActivityComposeTest.kt`、wireless `run-safe-integration-check.cmd` |
| Macrobenchmark・計測処理 | 今回未実行・未変更 | 要件指定により対象外 |

検証順はwireless実機で隔離統合チェックを先に実行し、続いて本番package安全上書きチェックを実行する。Macrobenchmarkは実行しない。

## 2026-07-19 メディアグリッド スクロール改善 第3実装

| 対象 | 状態 | 証跡 |
|---|---|---|
| prepared前・現在candidate未成功のPlaceholder判定 | 実装済み | `mediaGridCellVisualState`、`MediaGridPlaceholderRenderingTest` |
| 現在画像モデルのonSuccess後のPlaceholder消去・モデル変更・onError復帰 | 実装済み | `MediaGridImageModelKey`、`MediaGridPlaceholderRenderingTest`、`media_grid_placeholder_<assetId>` |
| Failed / 画像元なし / download失敗のError判定 | 実装済み | `MediaGridPlaceholderRenderingTest`、既存 `media_grid_error_<assetId>` |
| セル単位・静的・テーマ対応グラデーション | 実装済み | `MediaGridPlaceholderRendering.kt`、`drawWithCache`、ライト/ダークCompose確認 |
| 高速fling、触れ直し、セル操作、選択、列数・filter・sort・詳細表示 | 既存回帰確認対象 | `MainActivityComposeTest`、`UiStateRenderingTest` |
| Macrobenchmark | 未実行 | 今回の要件で対象外 |

## 2026-07-19 第5実装: 廃止済みcache復元（履歴）

| Requirement | Status | Evidence |
|---|---|---|
| 当時の独自cache検索・復元・miss memoization | 削除済み | 実装6でsource、fake、fixture、専用テストを削除 |
| 現行の永続JPEG、候補順、key、破損回復 | 維持 | `MediaGridDirectPreviewTest`、`MediaGridPersistentPreviewIntegrationTest` |

## 2026-07-19 メディアグリッド スクロール改善 第4実装（廃止済み履歴）

| 対象 | 状態 | 証跡 |
|---|---|---|
| 当時の操作状態、待機再開、生成停止、holder経路 | 削除済み | 実装6でsource、fake、fixture、専用テストを削除 |
| 無操作初回表示、カード→グリッド、drag/fling、停止後追従 | 既存Compose/実機回帰対象 | `MainActivityComposeTest.classifiedDisplayToggleSwitchesBetweenCardAndMediaGridAndSurvivesActivityRecreation`、`classifiedMediaGridSingleFlingKeepsLatestImageAndImmediateCellActionAfterFilter` |
| 現行placeholder、候補順、preload、列数変更 | 維持 | `MediaGridPlaceholderRenderingTest`、`MediaGridDirectPreviewTest`、既存列数テスト |

検証順は第4実装要件に従い、隔離統合テストを先に実行し、その成功後に本番packageの安全上書きチェックを実行する。Macrobenchmarkは変更・実行しない。
## 2026-07-20 第7実装: keyed frame and background image preparation

| Requirement | Implementation / evidence | Status |
|---|---|---|
| render-key mismatch hides old frame and shows Progress | `MediaGridRenderKey`, `mediaGridFrameMatches`, `MediaGridPreparedRenderTest` | Implemented |
| frame-first publication before image metadata | `buildMediaGridFrameData`, `MediaGridPreparedRenderTest`, `ClassifiedMediaGridContent` | Implemented |
| file stat, candidate, source identity, and cache key off composition | `MediaGridImagePreparer`, `MediaGridDirectPreviewTest` | Implemented |
| visible cells plus one adjacent row from prebuilt index column | `selectMediaGridPreparationIndices`, `MediaGridDirectPreviewTest` | Implemented |
| stale frame/viewport results are cancelled or rejected | `LaunchedEffect(frame.key)`, `collectLatest`, `mediaGridPreparedImageMatches`, `MediaGridPreparedRenderTest` | Implemented |
| unprepared cells remain Placeholder | `mediaGridCellVisualState`, `MediaGridPlaceholderRenderingTest` | Implemented |
| Progress, frame-first display, delayed scroll, and direction reversal on device | `MainActivityComposeTest.kt` scenarios; `run-safe-integration-check.cmd` | verified on connected `SC-56C` |

## 2026-08-02 production claim-path correction

| Requirement | Evidence | Status |
|---|---|---|
| Real production caller claims after a scroll-starting pointer sequence and draws Morph before physical up | `MainActivityComposeTest.productionMorphClaimDrawsBeforePhysicalUpWhenFirstPointerStartsScroll`, `ClassifiedMediaGridContent`, `mediaGridSingleSurface` | implemented; safe integration passed |
| No scroll-state null gate; current frame/column/viewport/visible geometry is the claim identity | `TagHierarchyUiV2.buildMediaGridViewportSignature`, production `prepareClaimBundle` | implemented; unit/static covered |
| Selected direction may claim independently; incomplete direction falls back without a null-model Morph snapshot | `MediaGridMorphClaimBundle.isCompleteFor`, `MediaGridMorphInteractionController`, canonical fallback | implemented; unit/Compose covered |
| One atomic generation/protection/model/draw-mode snapshot and TEST_HARNESS draw observer | `MediaGridMorphInteractionController.publish`, `MediaGridMorphTestTrace`, `MediaGridResidentCanvas` | implemented; safe integration passed |
| One canonical width-ratio release rule with 0.5 threshold | `mediaGridMorphCanonicalReleaseDecision`, `mediaGridColumnCountAfterPinchRelease`, gesture fallback | implemented; unit covered |

## 2026-08-02 atomic claim, crossfade, and cancellation coverage

| Requirement | Evidence | Status |
|---|---|---|
| One capture/prepared/text bundle covers both directions and publishes protection plus Morph state atomically | `MediaGridMorphClaimBundle`, `MediaGridMorphInteractionController.claimPointers`, `MediaGridMorphTest.claimBundlePublishesCompleteMorphSnapshotAndKeepsProtectionAcrossDirectionReversal` | unit covered |
| Direction reversal, dead zone, stale identity, and incomplete selected-plan resources do not rebuild or protect a new direction | `MediaGridMorphTest` reversal/dead-zone/identity/completeness tests; `MediaGridMorphInteraction.kt` frozen bundle path | unit/static covered |
| Missing tracked pointer, explicit up, and Compose cancellation have distinct outcomes | `MediaGridMorphCanvasComposeTest.interactiveLayerCancelProducesNoHandoff`, `mediaGridMorphGestureInput` tracked-pointer and `changedToUp()` branches | Compose/integration covered |
| Opaque Image-to-Image crossfade and one-sided Placeholder endpoints | `mediaGridMorphCellBlend`, `MediaGridMorphCanvasComposeTest.rowRendererRgb565FourColumn0011And1100RoundTripHasNoPlaceholderGaps` | unit/Compose pixel covered |
| Final device validation | `scripts/run-safe-debug-check.cmd -InstallToDevice`, `scripts/run-safe-integration-check.cmd` | passed on the connected SC-56C |

## 2026-08-07 Morph visible-set and handoff regression

| Requirement | Evidence | Status |
|---|---|---|
| All-change and header-visible gestures cannot replace the source viewport with an incomplete/dark Morph surface | Three production 4-to-5 cases compare current source item/header identities and rectangles with the first Morph model; fixture-wide forced residency was removed | safe integration passed |
| Wider target viewport contains every required endpoint cell | Viewport-capacity-aware `mediaGridMorphOrdinalRange`; `localRangeIncludesTheCompleteWiderTargetViewport` | unit and device covered |
| Progress 1 and first Idle Normal frame have the same visible identity/geometry | TEST_HARNESS visual-item trace and `assertVisualItemsMatch` in the same three representative cases | safe integration passed |
| Exact handoff is not overwritten by old-anchor restore | `shouldRestoreLegacyMediaGridPinchAnchor`; legacy restore remains enabled for explicit fallback anchors | unit and device covered |
| Header layout does not change at Morph/reveal boundary | `ClassifiedMediaGridHeader` retains one measured layout; no fixed-height hidden substitute | header-visible device case passed |
| A date/like-count section header that appears or disappears scales its height with Morph progress and fades its text | Shared `mediaGridMorphHeaderBlend`; per-frame `headerTransitions`; strengthened existing header-visible 4-to-5 case and row-reflow unit test | unit and safe integration passed |
| Terminal state remains scrollable and does not roll back | Existing Idle/unlocked/no-rollback assertions remain in each representative case | safe integration passed |
| A headerless grid can reverse 5-to-4 at the same unchanged location after 4-to-5 without a dark claim or lost column change | One reverse gesture appended to the existing all-slot-change representative case; first-Morph source comparison and exact terminal assertions | safe integration passed |
| Header appearance/disappearance uses the actual post-change item order | Target rows come from `MediaGridMorphExactTargetLayoutIndex`; headers attach only to their immediately following exact row | unit and safe integration passed |
| Scroll-bound correction cannot jump at the final Morph frame | Row/header target adjustment is linear in progress; unit continuity check at `0.999`/`1`; Morph endpoint versus first Normal frame | unit and safe integration passed |
| Returning to the original columns immediately after a successful handoff still uses Morph | Successful handoff assets move to bounded carryover until new-column stable-idle readiness; `successfulHandoffCarriesProtectedAssetsUntilImmediateReverseIsReady` | unit and wait-free safe integration passed |
| An unavailable immediate Morph fallback does not shift the focal scroll position | Production fallback forwards its captured anchor to the existing legacy anchor restore path | unit/static contract and safe integration covered |


### REAL_API_VERIFICATION.md before migration

# Real API Verification

実際のXアカウントでOAuth 2.0 + PKCE連携を確認する手順です。Client Secretやアクセストークンをソースコードへ書く必要はありません。

## X Developer Console

- App type: Native App
- App permission: Read
- Callback URI: `likelistmanager://oauth/x/callback`
- Scopes: `tweet.read users.read like.read offline.access`

Androidアプリに入力するのはOAuth 2.0 Client IDだけです。Client Secret、Consumer Key、Consumer Secret、Bearer Tokenは入力しません。

## Login

1. アプリの `...` から `X API設定` を開きます。
2. `OAuth 2.0 Client ID` を入力します。
3. `保存してXにログイン` を押します。
4. ブラウザでXへログインし、アプリへのアクセスを許可します。
5. アプリへ戻り、`@username でXにログインしました` と表示されることを確認します。
6. `X API設定` または `同期/使用量` にログイン中のユーザー名が表示されることを確認します。

## Sync

1. `...` から `同期する` を押します。
2. 同期結果に取得件数と新規保存件数が表示されることを確認します。
3. 取得した「いいね」投稿が未分類リストに表示されることを確認します。
4. 本文、投稿者名、画像が表示されることを確認します。
5. 投稿へタグと概要を設定し、分類リストのタグ絞り込みと検索で見つかることを確認します。
6. `同期/使用量` で月間取得数、15分制限、最終同期時刻が更新されることを確認します。

## Token And Logout

1. 時間を置いた後も再ログインなしで同期できることを確認します。期限切れに近いアクセストークンは更新トークンで自動更新されます。
2. `X API設定` の `Xからログアウト` を押します。
3. ログイン表示が消え、同期時にログインを求められることを確認します。

## Post Storage

- 保存先画面を開いた直後、容量未取得なら0 Bではなく「計算中」と進捗表示が出て、完了後に実容量へ更新されることを確認します。
- 「ここへ移動」を押した直後に見積もり中の進捗表示が出て、確定後は開始準備または移動中の進捗表示へ切り替わることを確認します。

1. SDカードを装着し、`...` から `投稿データの保存先` を開きます。
2. 内部ストレージとSDカードについて、現在地、使用容量、空き容量が表示されることを確認します。
3. SDカードへの移動を選び、確認画面の投稿件数、ファイル件数、容量を確認して移動します。
   - 画像数が多い場合は数分かかることがあります。移動中画面が表示され、ANRにならないことを確認します。
4. 移動後も投稿、タグ、概要、同期使用量、保存画像が維持され、同期と編集ができることを確認します。
5. アプリを終了してSDカードを外し、再起動時に空の投稿一覧を作らず、再装着または保存先確認が案内されることを確認します。
6. 同じSDカードを再装着してデータが再表示されることを確認します。
7. SDカードから内部ストレージへ戻し、同じデータが維持されることを確認します。

保存先はAndroidのアプリ専用領域です。ストレージ権限は不要ですが、アプリをアンインストールすると内部・SDカードとも投稿データは削除されます。

## Failure Signals

- `401`: 認証期限切れとして再ログインを案内します。
- `403`: Developer Consoleの権限またはスコープ不足を案内します。
- `429`: 15分制限の回復待ちを案内します。
- `5xx`: X APIの一時障害として再試行を案内します。
- callback後にアプリへ戻らない場合は、Developer ConsoleとManifestのURIが完全一致しているか確認します。
- Android `logcat` に `FATAL EXCEPTION`、`AndroidRuntime`、アプリのANRがないことを確認します。

## Local Verification

通常のbuild、unit test、lintは、`SAFE_DEBUG_ROUTINE.md`の安全入口を使用します。

```powershell
.\scripts\run-safe-debug-check.cmd
```

隔離実機確認はUSB接続とwireless endpointを自動判定する次の入口を使用します。

```powershell
.\scripts\run-safe-integration-check.cmd
```

## いいね数再取得の確認（2026-06-20）

1. 新規同期後、投稿カードにいいね数と取得日時が表示されることを確認する。
2. 右上メニューの「いいね数を再取得」で対象件数、月間枠内の実行件数、推定料金を確認する。
3. 実行後、成功件数・恒久失敗件数・月間取得数の増分が要求ID数と一致することを確認する。
4. 401/429/5xxや通信中断では未処理投稿が次回対象に残り、削除・非公開等の明確な投稿単位エラーだけ対象外になることを確認する。

## liked posts同期の継続確認（2026-06-22）

1. 月間残り枠などで既存投稿へ到達する前に同期が止まった場合、`sync_state.likedPostsNextToken` が保存されることをテスト環境で確認する。
2. 次回同期が保存tokenから再開し、既存地点へ到達するとtokenがNULLへ戻ることを確認する。
3. 続きの完了後に先頭も確認し、継続中に追加した新しいいいねが取り込まれることを確認する。


### docs/CHANGE_SUMMARY_2026-06-12.md before migration

# Change Summary 2026-06-12

## OAuth 2.0 + PKCE

- AppAuthを追加
- `likelistmanager://oauth/x/callback` をAppAuth receiverへ接続
- Client IDからXログインを開始する設定UIを追加
- Authorization Codeをtokenへ交換
- `/2/users/me` でログインユーザーIDと表示情報を取得
- access token、refresh token、有効期限、scope、ユーザー情報を暗号化保存
- 期限切れ前のaccess token自動更新
- logout時のtoken revoke
- Client Secretを使用しないpublic client方式
- 旧OAuth 1.0a入力欄と通信署名処理を削除
- 旧バージョンで保存されたOAuth 1.0a秘密情報をClient ID保存時に削除

## X API同期

- Bearer user access tokenでliked postsを取得
- pagination、投稿者、画像情報、rate-limit headerを処理
- 401、403、429、5xxをユーザー向けエラーへ変換
- 未ログイン時はダミー同期せずログインを案内
- 初期サンプル投入が既存の同期状態を上書きしないよう修正

## UI

- X API設定をClient ID、ログイン、ログアウト中心へ変更
- 設定画面と使用量画面へログインユーザー名を表示
- 認証結果をActivity Result APIで受け取る構成へ変更

## Documentation

- `docs/` に全ソースファイル対応の説明Markdownを追加
- 各説明へ関連ファイルと変更時の確認項目を追加
- `SOURCE_FILES.md` を全体構成、現状実装、変更目的別索引へ再構成
- `GOALS.md` にMVP、達成状況、次の目標、非目標を整理
- `REAL_API_VERIFICATION.md` をOAuth 2.0実機確認手順へ更新

## Verification

- `assembleDebug`: 成功
- `lintDebug`: 成功
- `testDebugUnitTest`: 成功、ただしテストコード未作成のため `NO-SOURCE`
- SC-56Cへの上書きインストールと起動: 成功
- 起動直後のFATAL EXCEPTION/ANR: 検出なし


### LikeTagger_requirements.md before migration

# LikeTagger 要件・技術仕様メモ

作成日: 2026-05-31
対象: Android向け個人用アプリ
目的: 自分のXアカウントで「いいね」した投稿を取得し、画像つきで端末内に保存し、あとからタグ付け・検索できるようにする。

---

## 1. 背景

Xで見つけた投稿をあとから分類・検索したい。
共有ボタンで毎回保存するのは面倒なので、操作感としては「X公式アプリで普通にいいねするだけ」にしたい。

そのため、LikeTaggerは次の流れを基本とする。

```text
X公式アプリで普通にいいね
  ↓
LikeTaggerが自分のliked_tweetsを同期
  ↓
新規いいね投稿をローカルDBに保存
  ↓
画像だけ端末内にダウンロード
  ↓
未分類リストに入れる
  ↓
あとからタグ付け・検索
```

---

## 2. 基本方針

### 2.1 アプリの位置づけ

LikeTaggerは「Xクライアント」ではなく、**自分専用のXいいね保存・分類アプリ** とする。

### 2.2 対象ユーザー

- 自分のみ
- 自分のXアカウントのみ
- 他人に提供するSaaSや公開アプリとしては考えない

### 2.3 X側操作

- X公式アプリでいいねする
- LikeTaggerはX側に書き込み操作をしない
- LikeTaggerからX本体に「いいね」「いいね解除」「投稿」などは行わない
- X APIは読み取り用途のみ

### 2.4 保存対象

保存する:

- 投稿ID
- 投稿URL
- 投稿本文
- 投稿者ID
- 投稿者名
- 投稿者ユーザー名
- 投稿日時
- 同期日時
- 保存日時
- 画像
- タグ
- メモ

保存しない:

- 動画本体
- GIF本体
- 他人のいいね一覧
- X側への書き込み操作履歴

動画・GIFについては、必要なら将来的にサムネイルだけ保存する余地を残す。

---

## 3. コスト方針

### 3.1 月額予算

目標予算:

```text
月300円程度
```

### 3.2 X API課金の考え方

X APIの公式Pricingでは、Owned Readsは「自分の開発者アプリが自分のデータを読む」ケースであり、posts、bookmarks、followers、likesなどが対象とされている。価格は `$0.001 / resource`、つまり1000リソースで$1とされている。

LikeTaggerでは自分のアカウントの `GET /2/users/{id}/liked_tweets` のみを使う想定なので、Owned Read対象として扱える前提で設計する。

ただし、X APIの価格・条件は変わる可能性があるため、実装前と運用開始前に必ず公式Pricingを再確認する。

### 3.3 取得件数の目安

概算:

```text
1000件取得 = 約$1
2000件取得 = 約$2
月300円 ≒ 約$2前後として運用
```

安全側の運用値:

```text
月間取得上限: 1800件
警告ライン: 1500件
強制停止ライン: 2000件
```

1日あたりの目安:

```text
1800件 / 30日 = 60件/day
```

したがって、1日あたり50〜60件程度の新規いいね取得を安全圏とする。

---

## 4. 採用技術

### 4.1 アプリ

```text
Android Native App
```

候補技術:

- Kotlin
- Jetpack Compose
- Room
- WorkManager
- OkHttp または Ktor Client
- Coil
- Android Keystore
- Jetpack Security / DataStore + 暗号化

### 4.2 DB

```text
Room + SQLite
```

理由:

- 投稿・タグ・画像メタデータのような構造化データを扱いやすい
- オフライン閲覧に向いている
- Android公式がRoomをSQLite上の抽象化レイヤーとして提供している

### 4.3 画像保存

```text
Android内部ストレージ
```

方針:

- DBには画像本体を入れない
- DBにはローカルパス・リモートURL・サイズなどのメタデータだけ入れる
- 画像本体はアプリ専用内部ストレージに保存する

保存先イメージ:

```text
/files/images/{x_post_id}_{media_key}.jpg
```

### 4.4 バックグラウンド同期

```text
WorkManager
```

理由:

- 1回限り・繰り返しのバックグラウンド処理に使える
- Androidの制約下で比較的安定して動作する
- 同期処理の再試行・制約指定に向く

### 4.5 認証

```text
OAuth 2.0 Authorization Code Flow with PKCE
```

方針:

- 外部ブラウザまたはChrome Custom TabsでXログイン
- アプリにクライアントシークレットを埋め込まない
- 必要最小限のscopeだけ要求する
- access token / refresh tokenは暗号化して保存する

想定scope:

```text
tweet.read
users.read
like.read
offline.access
```

`offline.access` はrefresh tokenが必要な場合に使用する。

---

## 5. X API仕様

### 5.1 使用エンドポイント

```http
GET https://api.x.com/2/users/{id}/liked_tweets
```

用途:

- 自分がいいねした投稿一覧を取得する
- 新規いいね投稿を差分同期する

### 5.2 取得パラメータ案

```text
max_results=50
tweet.fields=id,text,created_at,author_id,attachments
expansions=attachments.media_keys,author_id
media.fields=media_key,type,url,preview_image_url,width,height
user.fields=id,name,username
```

### 5.3 保存対象メディア

```text
media.type == "photo"
  → 保存する

media.type == "video"
  → 保存しない

media.type == "animated_gif"
  → 保存しない
```

将来オプション:

```text
video / animated_gif は preview_image_url のみ保存
```

### 5.4 ページング

`liked_tweets` はページング可能なので、初回同期や追加取得ではpagination tokenを使う。

ただし、費用を抑えるため、無制限にページングしない。

---

## 6. 同期仕様

### 6.1 同期モード

実装する同期:

1. 手動同期
2. 起動時同期
3. 低頻度バックグラウンド同期

### 6.2 手動同期

ユーザーが「同期」ボタンを押したときに実行する。

```text
同期ボタン
  ↓
予算上限チェック
  ↓
liked_tweets取得
  ↓
DB重複確認
  ↓
新規投稿のみ保存
  ↓
photoのみダウンロード
```

### 6.3 起動時同期

アプリ起動時、前回同期から一定時間経っていれば実行する。

推奨値:

```text
前回同期から30分以上経過していれば同期
```

### 6.4 バックグラウンド同期

WorkManagerで低頻度に実行する。

推奨値:

```text
1日2〜4回程度
Wi-Fi接続時優先
バッテリー低下時は実行しない
```

### 6.5 初回同期

初回に過去のいいねを全取得しない。

推奨値:

```text
初回同期: 最新300件
```

理由:

- 初回コストを抑える
- 使い始めに必要十分な件数を確保する
- 過去分はあとから手動追加取得できるようにする

### 6.6 差分同期

基本的には保存済み `x_post_id` との重複チェックで差分判定する。

```text
APIから取得したpost
  ↓
clips.x_post_id に存在するか確認
  ↓
存在する: スキップ
存在しない: 新規保存
```

`newest_seen_post_id` も保持するが、liked_tweetsの順序仕様や取り消しの扱いに依存しすぎないよう、最終的にはDB重複確認を正とする。

### 6.7 X側でいいね解除された場合

方針:

```text
ローカルには残す
```

理由:

- LikeTaggerは同期ミラーではなく保存・分類アプリ
- 一度保存したクリップはユーザーが明示的に消すまで残す
- X側の状態と完全一致させる必要はない

### 6.8 削除

削除はローカルのみ。

```text
LikeTagger内で削除
  ↓
ローカルDBとローカル画像を削除またはゴミ箱へ移動
  ↓
X側のいいね状態は変更しない
```

MVPではゴミ箱ありを推奨する。

---

## 7. 画面仕様

### 7.1 ホーム

表示項目:

```text
未分類件数
最近保存
タグ一覧
検索
同期ボタン
今月の取得数
推定API費用
```

例:

```text
未分類: 23
最近保存: 120
今月の取得数: 742 / 1800
推定API費用: 約$0.742
```

### 7.2 未分類画面

目的:

- 新規保存された投稿を高速にタグ付けする

表示:

- 画像
- 投稿本文
- 投稿者名
- ユーザー名
- 投稿日時
- 保存日時
- タグチップ
- メモ入力
- Xで開くボタン
- ローカル削除ボタン

操作:

```text
タグチップをタップ: タグ追加/解除
+タグ: 新規タグ追加
左右スワイプ: 前/次の投稿
下スワイプ: 後回し
Xで開く: X公式アプリまたはブラウザで開く
```

### 7.3 タグ一覧

表示:

- タグ名
- 色
- 件数
- 並び順

操作:

- タグ作成
- タグ名変更
- 色変更
- 並び替え
- タグ削除

### 7.4 検索画面

検索対象:

- 投稿本文
- 投稿者名
- ユーザー名
- メモ

絞り込み:

- タグ
- 未分類
- 画像あり
- メモあり
- 保存日
- 投稿日時

### 7.5 設定画面

設定項目:

- Xログイン/ログアウト
- 同期頻度
- 初回同期件数
- 月間取得上限
- 画像保存容量表示
- バックアップエクスポート
- バックアップインポート
- API使用量リセット日
- テーマ設定

---

## 8. タグ仕様

### 8.1 基本仕様

- 1投稿に複数タグを付けられる
- タグには色を設定できる
- タグには並び順を持たせる
- 階層タグはMVPでは作らない

例:

```text
ROS2
CAN
数学
あとで読む
UI参考
ロボコン
GitHub
論文
ネタ
```

### 8.2 未分類判定

```text
タグが0個のclip = 未分類
タグが1個以上のclip = 分類済み
```

ただし、将来的に `is_later` のような「後回し」状態を追加する余地を残す。

---

## 9. DB設計案

### 9.1 clips

```sql
CREATE TABLE clips (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    x_post_id TEXT NOT NULL UNIQUE,
    author_id TEXT,
    author_name TEXT,
    author_username TEXT,
    text TEXT,
    post_url TEXT NOT NULL,
    x_created_at TEXT,
    saved_at TEXT NOT NULL,
    synced_at TEXT NOT NULL,
    note TEXT,
    is_deleted INTEGER NOT NULL DEFAULT 0,
    is_archived INTEGER NOT NULL DEFAULT 0,
    is_deleted_on_x INTEGER NOT NULL DEFAULT 0
);
```

### 9.2 assets

```sql
CREATE TABLE assets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    clip_id INTEGER NOT NULL,
    media_key TEXT,
    type TEXT NOT NULL,
    local_path TEXT,
    remote_url TEXT,
    preview_url TEXT,
    width INTEGER,
    height INTEGER,
    size_bytes INTEGER,
    created_at TEXT NOT NULL,
    FOREIGN KEY (clip_id) REFERENCES clips(id) ON DELETE CASCADE
);
```

### 9.3 tags

```sql
CREATE TABLE tags (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    color TEXT,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### 9.4 clip_tags

```sql
CREATE TABLE clip_tags (
    clip_id INTEGER NOT NULL,
    tag_id INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (clip_id, tag_id),
    FOREIGN KEY (clip_id) REFERENCES clips(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE
);
```

### 9.5 sync_state

```sql
CREATE TABLE sync_state (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    x_user_id TEXT,
    newest_seen_post_id TEXT,
    last_sync_at TEXT,
    monthly_fetched_count INTEGER NOT NULL DEFAULT 0,
    monthly_budget_limit INTEGER NOT NULL DEFAULT 1800,
    monthly_warning_limit INTEGER NOT NULL DEFAULT 1500,
    monthly_stop_limit INTEGER NOT NULL DEFAULT 2000,
    usage_month TEXT
);
```

### 9.6 auth_state

実際には暗号化ストレージ側に置くのが望ましいが、設計上の保持データは以下。

```text
x_user_id
access_token
refresh_token
expires_at
scope
```

DBに平文tokenを保存しない。

---

## 10. ストレージ仕様

### 10.1 画像保存

保存形式:

```text
オリジナル画像をそのまま保存
```

MVPでは再圧縮しない。

理由:

- 画質劣化を避ける
- 実装を単純化する
- あとから圧縮オプションを追加可能

### 10.2 容量制御

方針:

```text
自動削除しない
1GB超えたら警告
容量表示を設定画面に出す
```

勝手に画像を消すと保存アプリとして信用できないため、自動削除は行わない。

### 10.3 バックアップ

必須機能。

エクスポート形式:

```text
LikeTagger_backup_YYYYMMDD_HHMMSS.zip
```

中身:

```text
clips.json
tags.json
clip_tags.json
assets.json
images/
  {filename}.jpg
metadata.json
```

インポート:

- ZIPを選択
- JSONを検証
- 既存DBと重複マージ
- 画像を内部ストレージへ復元

---

## 11. セキュリティ

### 11.1 token保存

- access token / refresh tokenは平文保存しない
- Android Keystoreを使う
- Jetpack Securityまたは暗号化DataStoreを検討する

### 11.2 API認証

- OAuth 2.0 PKCEを使う
- native appにclient secretを埋め込まない
- stateとcode_verifierを適切に生成する

### 11.3 API使用制限

アプリ側で必ず予算制限を持つ。

```text
月間取得数をDBに保存
警告ラインを超えたら明示表示
停止ラインを超えたら自動同期停止
手動同期時も確認ダイアログ表示
```

---

## 12. エラー処理

### 12.1 APIエラー

想定:

- 401 Unauthorized
- 403 Forbidden
- 429 Rate Limit
- 5xx Server Error
- Network Error

対応:

```text
401:
  token更新
  失敗したら再ログイン要求

403:
  scope不足 or API権限不足として表示

429:
  次回同期まで待機
  バックグラウンド同期を一時停止

5xx:
  リトライ

Network Error:
  オフライン表示
  次回起動時/次回WorkManagerで再試行
```

### 12.2 画像ダウンロード失敗

方針:

- 投稿データは保存する
- assetに `download_failed` 状態を持たせる余地を残す
- あとから再ダウンロードできるようにする

MVPでは `local_path` がnullなら未保存画像として扱う。

---

## 13. MVPスコープ

最初に作る範囲:

```text
1. X OAuthログイン
2. 自分のliked_tweetsを手動同期
3. 最新300件の初回同期
4. 新規投稿の差分保存
5. photoのみローカル保存
6. 未分類一覧
7. タグ作成
8. 複数タグ付け
9. タグ別一覧
10. 本文検索
11. Xで開く
12. ZIPエクスポート
```

MVPではやらない:

```text
1. X側へのいいね/いいね解除
2. 動画保存
3. GIF保存
4. 複数アカウント対応
5. クラウド同期
6. 公開アプリ化
7. AI自動分類
8. PWA対応
9. Windows対応
```

---

## 14. v2以降の拡張候補

### 14.1 バックグラウンド同期

MVP後に追加。

```text
起動時同期
1日2〜4回のWorkManager同期
Wi-Fi時のみ同期オプション
```

### 14.2 自動タグ候補

ルールベースで十分。

例:

```text
本文に ros2/nav2/slam → ROS2
本文に can/socketcan/fdcan → CAN
URLに github.com → GitHub
URLに arxiv.org → 論文
```

### 14.3 FTS検索

Room/SQLite FTSを使う。

対象:

- 本文
- 投稿者名
- ユーザー名
- メモ

### 14.4 Google Driveバックアップ

手動ZIPエクスポートで運用後、必要なら追加。

### 14.5 画像圧縮

設定で選択可能にする。

```text
オリジナル保存
長辺1920pxに縮小
長辺1280pxに縮小
```

---

## 15. 主要な未決事項

現時点で未決のもの:

```text
アプリ名をLikeTaggerで確定するか
初回同期件数を300で確定するか
月間取得上限を1800で確定するか
バックグラウンド同期をMVPに入れるかv2に回すか
ZIPインポートをMVPに入れるか、エクスポートだけ先にするか
動画/GIFのサムネ保存をするか完全無視するか
```

推奨確定値:

```text
アプリ名: LikeTagger
初回同期: 最新300件
月間上限: 1800件
警告: 1500件
停止: 2000件
バックグラウンド同期: v2
MVPバックアップ: ZIPエクスポートのみ
動画/GIF: MVPでは完全無視
```

---

## 16. 実装順序案

### Phase 1: ローカル機能

```text
Roomスキーマ作成
タグCRUD
クリップ一覧UI
未分類UI
タグ付けUI
検索UI
```

この段階ではダミーデータで動かす。

### Phase 2: Xログイン

```text
OAuth 2.0 PKCE
token保存
自分のuser_id取得
ログアウト
```

### Phase 3: liked_tweets同期

```text
手動同期
max_results=50
初回300件
重複排除
月間取得数カウント
```

### Phase 4: 画像保存

```text
media.type=photoのみ抽出
画像ダウンロード
内部ストレージ保存
asset DB保存
失敗時の再試行
```

### Phase 5: バックアップ

```text
ZIPエクスポート
ZIPインポート
重複マージ
```

### Phase 6: 自動同期・改善

```text
起動時同期
WorkManager同期
FTS検索
自動タグ候補
容量警告
```

---

## 17. 参考情報

確認日: 2026-05-31

- X API Pricing: Owned Readsは自分のデータを読むケースで `$0.001 / resource` とされている。
  - https://docs.x.com/x-api/getting-started/pricing
- X API Get liked Posts: `GET /2/users/{id}/liked_tweets` は指定ユーザーがいいねしたPosts一覧を取得するエンドポイント。
  - https://docs.x.com/x-api/users/get-liked-posts
- X API OAuth 2.0 Authorization Code Flow with PKCE: scope指定・code_challenge・state等を使う認証フロー。
  - https://docs.x.com/fundamentals/authentication/oauth-2-0/authorization-code
- Android Room: SQLite上の抽象化レイヤーとしてローカルDB保存に使える。
  - https://developer.android.com/training/data-storage/room
- Android WorkManager: 1回限り・繰り返しのバックグラウンド処理に使える。
  - https://developer.android.com/develop/background-work/background-tasks/persistent
- Android Jetpack Security: キー、暗号化ファイル、暗号化SharedPreferences等の管理に使える。
  - https://developer.android.com/jetpack/androidx/releases/security

---

## 18. 一行まとめ

LikeTaggerは、X公式アプリの「いいね」を入力UIとして使い、自分のliked_tweetsだけをX APIで差分取得し、画像つきでAndroid端末内に保存して、あとからタグ付け・検索する個人用ローカル分類アプリである。
