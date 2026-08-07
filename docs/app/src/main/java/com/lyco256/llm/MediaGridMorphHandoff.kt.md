# `MediaGridMorphHandoff.kt`

## 2026-08-05 exact target verification

- The handoff request carries the exact target row ID, first item index, focal item index, row ordinals, exact layout index, local desired row top, and target scroll bounds.
- Target Y correction is limited to one `ScrollBy`. Before reveal, the coordinator validates the complete visible target viewport: row IDs/ordinals, every cell item index and rect, and every visible header key/title/rect.
- Visible geometry is captured in LazyGrid viewport-local coordinates; stale frame/viewport/identity paths rollback or cancel without fallback being counted as Morph success.
- When an exact target index is present, an invalid or missing exact focal item index rolls back as `TargetMediaUnavailable`; it never ordinal-clamps into a Morph success.

## 2026-08-01 Phase 2 production handoff

- The real `LazyGridState` is verified by target media ordinal plus every visible row/cell and header in viewport-local coordinates; each geometric correction remains within the one-pixel tolerance and the handoff permits at most one `ScrollBy` correction.
- `VerifyingTarget` is followed by one underlying-grid draw and only then `RevealTarget`; mismatches and stale frames issue rollback/cancel without applying the target row contract to the source rollback path.

## 2026-07-31 UI・handoff correction

- Target handoff is event-driven: target frame, visible geometry, optional bounded correction, underlying draw, and next-frame completion are distinct phases.
- Production target scroll uses `ScrollToItem` for visibility and re-observes the current layout after each command; no polling or timeout was added.
- Target-frame, visibility, viewport, geometry, stale-data, and rollback failures are retained as `MediaGridMorphGridHandoffFailureReason` enum values.

## 役割

TEST_HARNESSの実LazyGrid handoffで使うtarget anchor選択と、Composeから分離したevent-driven coordinatorを保持する。production UIには接続しない。

## target anchor

- handoff request生成時にboundedな`MediaGridMorphPlan.slots`だけを一回走査する。
- interaction anchor slotの有効なend Assetを優先し、なければfinal correction後のpinch centerを含むend rect、さらに最寄りend rectの順で選ぶ。
- Asset ID、media ordinal、row／column、end rect、rect内focal U／V、維持するCanvasローカル位置、target item index hintをimmutableに保持する。
- end Assetがなければrequestを生成せずcurrent列へ戻る。

## coordinator

phaseは`Idle`、`RequestingColumnChange`、`WaitingForTargetFrame`、`PositioningTarget`、`VerifyingTarget`、`ReadyToComplete`、`RollingBack`、`Cancelled`。

- target列変更は同じrequestで一回だけ発行する。
- expected frameだけを採用し、Asset消失時はmedia ordinalをclampしてfallbackする。
- exact target indexのtarget row先頭itemへ`ScrollToItem`を発行し、viewport-localなYだけを最大1回`ScrollBy`で補正する。Reveal前に全visible row/cell/headerを1px以内で検証する。
- geometry確認後、underlying target grid描画を通知し、その次frameでcompleteする。
- target frame不正、item不在、geometry不一致ではfrom列変更を一回だけ発行し、source anchorを位置合わせしてcancelする。
- source revision／data keyがstaleなら旧画面へrollbackせずCanvasを除去する。

実時間timeout、Delay、polling、画像・resident store・Coil・IO参照を持たない。

Production row-reflow handoff geometryはfull exact target layout indexから取得する。target row ID、first item index、row top、header sequence、content height、achievable scroll boundsを使い、旧bounded/global target approximationはproduction pathで使わない。
