# `MediaGridMorphInteraction.kt`

## 2026-08-01 Phase 2 production interaction

- Production claim order is `stopScroll -> capture real LazyGrid -> build/validate row pair`; a claim-preparation failure reaches only the one-step release fallback.
- The controller exposes `Normal`, `Morph`, `RevealCurrent`, and `RevealTarget` draw modes. Current settle renders one reveal frame before unlock; target settle stays at progress 1 until real-grid handoff verification completes.
- Target requests retain focal ordinal, target row top, row ordinals, cell size, and header title. Source/target identity changes are allowed only in the matching handoff/reveal phase.

## 2026-08-01 Phase 1 row reflow

- Production Morph overlay/handoff is disabled for this phase. Release builds use the legacy one-step pinch resize with a fixed initial pointer distance.
- TEST_HARNESS captures the real grid at claim, derives progress from current distance divided by the initial distance, and hands off to the actual target `LazyVerticalGrid` row.
- The row plan keeps the initial pinch center fixed; current-centroid translation is not applied.
- The TEST_HARNESS claim path uses row-only prepared pairs and `selectRowReflow`; it does not create or select legacy dataset slots.
- Claim-time pairs use the identity captured from that same `LazyGridLayoutInfo` snapshot, so a stale pre-recomposition viewport signature cannot silently downgrade the TEST_HARNESS gesture to a no-op fallback.
- During active Tracking/settle, the captured plan remains valid across LazyGrid offset/signature updates caused by stopping a scroll; source revision, frame key, and current column count remain strict identity guards.
- Target anchor selection retains a row-plan path when the target focal metadata is incomplete, using the bounded target cell content before falling back to the legacy bounded slot selector.

## 2026-07-31 UI・handoff correction

- Pointer release accepts the first normal loss of a tracked pointer, including pointer disappearance, and calls Morph release or fallback exactly once.
- Settle timing starts from the Compose frame clock; Android pointer uptime is not mixed with `withFrameNanos`.
- `Failed`/identity mismatch states retain a diagnostic reason instead of silently treating a target failure as Idle.
- Identity mismatch and target-anchor failure clear the stale Canvas/lock while retaining `Failed` plus its reason, so the current LazyGrid remains usable and a later gesture can start a new generation.

## Current production contract

The TEST_HARNESS and production paths share the pointer arbitration state machine through `MediaGridMorphGestureMode`. A two-pointer candidate starts when the second pointer first appears regardless of current scroll state. It does not consume, stop scroll, set `pointerInProgress`, or lock interaction. The first event satisfying the existing direction dead zone, `touchSlop * 0.35`, and `centroid movement * 0.5` claims either Morph or the one-shot fallback. Production uses bounded pair/readiness checks; when they fail, release performs one direct column-count fallback. The former `mediaGridPinchToResize` modifier is removed.

## 役割

前段のbounded prepared pairと単一Morph Canvasを、TEST_HARNESSとproductionの二本指入力へ接続する。productionは通常の一枚の`LazyVerticalGrid`上でhandoffし、pair/readiness不成立時は従来列数変更fallbackへ戻る。

## controller

- `MediaGridMorphInteractionController`はgesture開始時にsource revision、frame key、列数、viewport signatureが一致するprepared pair snapshotだけを固定する。
- 最初に選んだ二つのpointer ID、初期距離、初期中心をgesture全体で維持する。Bitmap、resident store、Coil、LazyGridStateは保持しない。
- updateごとのscaleは`initialDistance / currentDistance`から直接求め、既存の`mediaGridMorphDirectionForScale()`と`mediaGridMorphProgressForScale()`だけで方向・progressへ変換する。
- dead zoneへ戻るとactive planを維持してprogress 0へ戻す。反対側へ越えた時だけ反対pairからplanを一度選択する。
- progressとcorrectionはCanvas draw用の安定したStateへ公開し、plan Stateは方向切替・終了時だけ変更する。

## focal correction

`MediaGridMorphAnchor`はAsset ID、slot、start rect内の非clamp正規化位置`focalU`／`focalV`、Canvasローカル開始中心を保持する。

`mediaGridMorphFocalCorrection()`はanchor slotのstart/end rectをprogressで補間し、そのrect内の正規化anchor点を現在の二本指中心へ一致させるOffsetを返す。slot外やheader上から開始してもprogress 0はcorrection 0になり、中心移動はX・Yの両方へ同量追従する。

## pointer入力

- `mediaGridMorphGestureInput`と`MediaGridMorphInteractiveTestLayer`は`BuildConfig.TEST_HARNESS`を必須とする。
- candidateは最初に揃った二pointerのID、initial positions、initial distance、centroid、generationをgesture中一回だけ固定する。scroll中でもcandidateを開始し、candidate中はconsume、`pointerInProgress`、stopScroll、anchor checkpointを行わない。
- claim判定は既存`mediaGridMorphDirectionForScale()`、span change `>= touchSlop * 0.35f`、span change `>= centroid movement * 0.5f`の全条件で行う。claim時だけcandidate positionsでcontrollerをbeginし、同じeventの現在positionsをupdateしてからstopScrollを一回起動し、tracked pointerだけをconsumeする。
- stale／画像不足／pairなし／hostなしは同じcandidate基準距離を使うFallbackClaimedへ進み、release時の列数変更callbackを一回だけ呼ぶ。三本目が追加されてもtracked IDは変えない。
- TEST_HARNESS でも準備pairが利用できない場合は、同じ固定初期距離の一段fallback callbackへ接続し、列数を4のまま取り残さない。
- 追跡pointerの一方が離れた時だけreleaseを一回処理する。pointer cancelはcurrent側へ戻し、handoffを生成しない。

## settleとhandoff

- release progressが0.5未満ならcurrent、0.5以上ならtargetへ進む。
- pointerのrelease `uptimeMillis`を開始時刻として一回保存し、composable runnerは`withFrameNanos`の時刻との差を使う。controllerのrelease時progress／correctionを基準に180msのelapsed fractionで線形補間する。
- current完了はprogress／correction 0でIdleへ戻る。
- target補正はrelease時の最後の中心を固定し、各progressで純粋関数から再計算する。完了時はprogress 1のCanvasを維持した`AwaitingGridHandoff`となる。
- immutable `MediaGridMorphHandoffRequest`はinteraction generationごとに一回だけ生成・通知する。完了通知でto列数のIdle、取消通知でfrom列数のIdleへ戻る。
- request生成時にbounded planからtarget Asset、media ordinal、focal位置、維持Canvas位置を一回だけ確定し、source data/frame keyとexpected target frame keyを固定する。target Assetがなければrequestを出さない。
- `AwaitingGridHandoff`中は同じsource identityの再通知とexpected target identityへの変更だけを許可し、plan、progress 1、final correction、request、Canvasを維持する。
- identity変更とcancelはgenerationを照合して古いsettle更新とstale handoffを拒否する。

pointer updateとsettle frameではprepared pair、render model、画像解決、crop、text measure、viewport、anchor、queue、publicationを再構築・更新しない。
# MediaGridMorphInteraction.kt

Testとproductionは同じ`MediaGridMorphInteractionController`、pointer state machine、scale、方向反転、focal correction、180ms settle、consume規則を共有する。

`MediaGridMorphGestureMode`は`Disabled`、`Test`、`Production`。TestだけTEST_HARNESS制限を受ける。productionではprepared pair/readiness不成立時に同じmodifier内のrelease時一段変更fallbackを実行し、Morph accept時はfallbackを発行しない。fallbackのscaleはgesture開始時距離とrelease時距離から直接計算する。
