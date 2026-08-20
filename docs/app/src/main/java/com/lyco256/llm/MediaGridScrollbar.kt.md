# `MediaGridScrollbar.kt`

分類済みメディアグリッド右端の高速スクロールバーを担当します。

- 表示可否、thumb高、thumb位置、drag fractionからmedia ordinalへの変換は純粋関数で計算します。
- `MediaGridFrameData.ordinalIndex`のmedia ordinalと`itemIndexByMediaOrdinal`だけを使い、drag中にframe全走査・DB・Repository・再ソートを行いません。
- drag開始時にframe key、件数、visible件数、thumb geometry、pointer grab offset、ordinal indexを固定します。frame変更、cancel、無効化時はdragを解除します。
- target requestは一意なrequest ID、drag session ID、target item index、drag/final種別を持つ`StateFlow`で最新値だけを保持し、pointerイベントごとの移動命令をキューに積みません。同一targetでもpointer UP時のfinal requestを失いません。pointer UP直後に物理dragは終了し、final target pendingは独立して扱います。thumb描画はdrag中またはfinal pending中だけpointer由来位置を優先し、完了・取消・frame変更・consumer coroutine終了後はviewport geometryへ戻します。
- pointer入力はthumb周辺の広いhit領域だけで取得し、視覚上のtrack/thumbを32dpタッチ領域の右端へ固定したまま、タッチ領域を左方向へ確保します。thumb外の縦方向領域はグリッドへ渡します。
- visual track/thumbは同じ横位置でグリッド右端に一致し、通常、drag中、final target pending中で横位置を変えません。
- `MediaGridFrameData.headerBoundaryIndex`の各boundaryはframe構築時にbucket key、インライン見出しと同じlabel、開始media ordinalを保持します。drag中は全boundaryを開始ordinalからscrollbar座標へ変換した文字入りSurfaceとして、trackのすぐ左側へ同時表示します。保存順ではboundaryがないため表示しません。
- 全見出しピルの位置一覧はframe key、track geometry、total/visible media数が変わった時だけ`remember`で再構築し、pointer MOVEのtarget ordinal変更では再走査・bucket再計算・I/Oを行いません。ピルは非操作で、描画順はboundaryのordinal順です。
- 見出しピルはpointer UP、cancel、FinalTargetPending、frame変更、無効化では表示しません。旧12dp×4dp bucket highlightとthumb追従の単一文字ピルは存在しません。
- `MediaGridScrollbarDragSnapshot`を唯一のdrag状態源とし、親側の1経路だけがcheckpoint、上部ピル抑制、morph抑制、終了引き継ぎを処理します。正常終了と取消終了は区別され、古いtargetを引き継ぎません。
