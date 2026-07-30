# `MediaGridMorphInteraction.kt`

## 役割

前段のbounded prepared pairと単一Morph Canvasを、TEST_HARNESS内だけで実際の二本指入力へ接続する。productionの`ClassifiedMediaGridContent`、`mediaGridPinchToResize`、LazyGrid列数変更には接続しない。

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
- 一本指はconsumeしない。scroll中、stale pair、valid pairなしの二本指も受理しない。
- accepted後は固定した二pointerのposition changeだけをconsumeし、三本目は追跡対象へ切り替えない。
- 追跡pointerの一方が離れた時だけreleaseを一回処理する。pointer cancelはcurrent側へ戻し、handoffを生成しない。

## settleとhandoff

- release progressが0.5未満ならcurrent、0.5以上ならtargetへ進む。
- pointerのrelease `uptimeMillis`を開始時刻として一回保存し、composable runnerは`withFrameNanos`の時刻との差を使う。controllerのrelease時progress／correctionを基準に180msのelapsed fractionで線形補間する。
- current完了はprogress／correction 0でIdleへ戻る。
- target補正はrelease時の最後の中心を固定し、各progressで純粋関数から再計算する。完了時はprogress 1のCanvasを維持した`AwaitingGridHandoff`となる。
- immutable `MediaGridMorphHandoffRequest`はinteraction generationごとに一回だけ生成・通知する。完了通知でto列数のIdle、取消通知でfrom列数のIdleへ戻る。
- identity変更とcancelはgenerationを照合して古いsettle更新とstale handoffを拒否する。

pointer updateとsettle frameではprepared pair、render model、画像解決、crop、text measure、viewport、anchor、queue、publicationを再構築・更新しない。
