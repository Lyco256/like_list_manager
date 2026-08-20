# `MediaGridScrollbar.kt`

分類済みメディアグリッド右端の高速スクロールバーを担当します。

- 表示可否、thumb高、thumb位置、drag fractionからmedia ordinalへの変換は純粋関数で計算します。
- `MediaGridFrameData.ordinalIndex`のmedia ordinalと`itemIndexByMediaOrdinal`だけを使い、drag中にframe全走査・DB・Repository・再ソートを行いません。
- drag開始時にframe key、件数、visible件数、thumb geometry、pointer grab offset、ordinal indexを固定します。frame変更、cancel、無効化時はdragを解除します。
- target item indexは`StateFlow`で最新値だけを保持し、pointerイベントごとの移動命令をキューに積みません。thumb描画はdrag中のpointer由来位置を優先します。
- pointer入力はthumb周辺の広いhit領域だけで取得し、thumb外の右端領域はグリッドへ渡します。
- `TagHierarchyUiV2.kt`側ではviewport anchor、checkpoint抑制、morphのstable-idle抑制、後続位置ラベル向けdrag snapshotの受け渡しだけを行います。
