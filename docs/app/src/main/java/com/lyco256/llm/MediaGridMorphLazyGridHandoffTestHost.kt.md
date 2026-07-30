# `MediaGridMorphLazyGridHandoffTestHost.kt`

`BuildConfig.TEST_HARNESS`限定で、実`LazyVerticalGrid`一枚と既存Morph interactive layerをhandoff coordinatorへ接続する。

- source／targetの実`MediaGridFrameData`を可変列数の同じLazyGridへ表示する。
- headerは実gridのfull-span itemで、planのheader高さを使う。
- target frameを指定frame数遅延でき、その間もprogress 1のCanvasを最前面に維持する。
- commandごとに`scrollToItem`／`scrollBy`後のframeでvisible geometryを確認する。
- geometry一致後にunderlying grid用frameとCanvas除去用frameを分離する。
- handoff中は`userScrollEnabled=false`とし、coordinator snapshotからcheckpoint抑止phaseを公開する。

二枚のLazyGrid、Delay、周期polling、render modelやresident indexの再構築は追加しない。productionの`ClassifiedMediaGridContent`と`mediaGridPinchToResize`からは参照しない。
