# `MediaGridMorphHandoff.kt`

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
- visible itemだけから実rectとfocal位置を計算し、Yだけ最大3回補正する。正方形、plan end cell寸法、X／Y各1px以内を確認する。
- geometry確認後、underlying target grid描画を通知し、その次frameでcompleteする。
- target frame不正、item不在、geometry不一致ではfrom列変更を一回だけ発行し、source anchorを位置合わせしてcancelする。
- source revision／data keyがstaleなら旧画面へrollbackせずCanvasを除去する。

実時間timeout、Delay、polling、画像・resident store・Coil・IO参照を持たない。

`MediaGridMorph.kt`のbounded target layoutは、Default並びの非ゼロ開始ordinalではtarget列剰余を維持する。実際に先頭item indexが0より後ろで、dataset末尾cellがviewport下端へ接するcaptureだけはtarget最終行も下端へ揃え、先頭から全件がちょうど収まる状態を末尾scrollと誤認しない。
