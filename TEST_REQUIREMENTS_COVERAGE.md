# 実機レベル統合テスト強化 カバレッジ

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

## 2026-08-01 Phase 1 row reflow

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

## 2026-07-31 Morph UI・handoff correction

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

## 2026-07-31 gesture arbitration fix

| 要件 | 証跡 | 状態 |
| --- | --- | --- |
| scroll状態に依存しない二本指candidate、pointer ID／initial positions／distance一回固定 | `MediaGridMorphCandidate`、`MediaGridMorphGestureArbitrationState`、`MediaGridMorphLazyGridHandoffComposeTest.realLazyGridKeepsScrollAndPanUntilPinchClaimThenStopsOnce` | 完了 |
| candidate中の非consume・既存direction・touchSlop 0.35・centroid 0.5判定 | `mediaGridMorphCandidateDirection`、`MediaGridMorphTest.candidateClaimUsesDeadZoneTouchSlopAndCentroidArbitration` | 完了 |
| claim時のcandidate begin→current update、claim後のみstopScroll／consume、fallback exactly-once | `mediaGridMorphGestureInput`、`productionGestureFallsBackOnceWhenPreparedPairIsUnavailable`、実LazyGridCompose Test | 完了 |
| viewport swept bounds内の正寸法側だけreadiness必須 | `isMediaGridMorphProductionReady`、overscan／zero-size Compose Test | 完了 |
| resident Canvas、handoff、anchor、viewport、queue、先読み、1frame1枚公開の不変更 | 対象差分と既存Rendering／handoff／publication契約、safe integration Success | 完了 |

検証結果: `scripts\run-safe-integration-check.cmd` 成功、続けて `scripts\run-safe-debug-check.cmd -InstallToDevice` も成功。Macrobenchmark、本番DB・画像・設定・認証情報の初期化は行わない。

## 2026-07-30 TEST_HARNESS 実LazyGrid handoff基盤

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

## 2026-07-30 TEST_HARNESS Morph gesture tracking／settle

| 要件 | 証跡 | 状態 |
|---|---|---|
| 初期距離÷現在距離、既存dead zone／progress、方向反転 | `MediaGridMorphInteractionController`、`MediaGridMorphTest.directDistanceScaleAndExistingProgressFunctionsCoverBothDirections`／`controllerReturnsThroughDeadZoneAndSwitchesPreparedDirectionContinuously` | Unit Test完了 |
| 非clamp 2次元focal anchor／correction | `MediaGridMorphAnchor`、`mediaGridMorphFocalCorrection`、固定中心・XY移動・slot外・viewport origin test | Unit Test完了 |
| 固定pointer ID、一本指非consume、三本目無視、release／cancel | `mediaGridMorphGestureInput`、`MediaGridMorphCanvasComposeTest.interactiveLayerTracksFixedPointersReversesAndReusesResolvedRenderWork`／`interactiveLayerCancelProducesNoHandoff` | 隔離Compose Test完了 |
| release基準180ms線形settle、exactly-once handoff、Awaiting維持 | `advanceSettleElapsed`、0／45／90／135／180ms、stale generation、complete test | Unit Test完了 |
| pointer／settle中のrender work再実行防止 | plan Stateをdirection切替時だけ更新、Canvas既存counter test、interactive counter test | Unit・隔離Compose Test完了 |
| production未接続、通常pinch／LazyGrid不変 | `MediaGridRenderingContractTest.morphInteractionIsTestHarnessOnlyAndDoesNotReplaceProductionPinchOrGridHandoff` | 静的契約・隔離integration完了 |

## 2026-07-30 TEST_HARNESS単一Morph Canvas

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

## 2026-07-31 production Morph integration

| Requirement | Evidence | Status |
|---|---|---|
| Explicit Production mode on the normal non-selection, non-progress grid | `MediaGridMorphCanvasMode.ProductionVisible`, `MediaGridMorphGestureMode.Production`, and the `MediaGridMorphProductionHost` gate | covered |
| Missing prepared pair or resident viewport asset falls back once at release | `isMediaGridMorphProductionReady`, direct initial-to-release fallback, and resident-readiness Compose test | covered |
| One underlying LazyGrid with event-driven target geometry handoff | `MediaGridMorphProductionHost` and `productionHostUsesTheSameLazyGridAndRemovesCanvasAfterHandoff` | covered |
| Scroll, cell interaction, checkpoint, retention, stale identity, rollback, and lifecycle safety | Production host suppression/owner protection plus existing handoff, rollback, checkpoint, and identity tests | covered |
| Legacy production pinch modifier removed while common Test/Production input remains | `MediaGridRenderingContractTest` and source-level absence of `mediaGridPinchToResize` in production UI | covered |
| Normal Activity live pinch composes the production Canvas | `MainActivityComposeTest.normalClassifiedGridComposesProductionCanvasDuringLivePinch` uses the real classified screen and two-pointer input; Canvas is observed mid-gesture | covered (automated; no manual screen capture available) |

Final verification is required through the safe integration and safe debug-install entry points before commit.

## 2026-07-30 bounded Morph prepared pair foundation

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

## 2026-07-17 simple column-change stabilization

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
