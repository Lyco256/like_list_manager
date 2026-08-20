# `MediaGridScrollbar.kt`

分類済みメディアグリッド右端の高速スクロールバーを担当します。

- 表示可否、thumb高、thumb位置、drag fractionからmedia ordinalへの変換は純粋関数で計算します。
- `MediaGridFrameData.ordinalIndex`のmedia ordinalと`itemIndexByMediaOrdinal`だけを使い、drag中にframe全走査・DB・Repository・再ソートを行いません。
- drag開始時にframe key、件数、visible件数、thumb geometry、pointer grab offset、ordinal indexを固定します。frame変更、cancel、無効化時はdragを解除します。
- target requestは一意なrequest ID、drag session ID、target item index、drag/final種別を持つ`StateFlow`で最新値だけを保持し、pointerイベントごとの移動命令をキューに積みません。同一targetでもpointer UP時のfinal requestを失いません。pointer UP直後に物理dragは終了し、final target pendingは独立して扱います。thumb描画はdrag中またはfinal pending中だけpointer由来位置を優先し、完了・取消・frame変更・consumer coroutine終了後はviewport geometryへ戻します。
- pointer入力はthumb周辺の広いhit領域だけで取得し、視覚上のtrack/thumbを32dpタッチ領域の右端へ固定したまま、タッチ領域を左方向へ確保します。thumb外の縦方向領域はグリッドへ渡します。
- visual track/thumbは同じ横位置でグリッド右端に一致し、通常、drag中、final target pending中で横位置を変えません。drag中の位置labelはvisual thumbの左側へ配置します。
- drag中は`targetMediaOrdinal`から既存bucketを直接求めた小型labelをthumb左側へoverlay表示し、縦位置をグリッド領域内へclampします。labelは操作を持たず、レイアウト幅を変更しません。
- `MediaGridScrollbarDragSnapshot`を唯一のdrag状態源とし、親側の1経路だけがcheckpoint、上部ピル抑制、morph抑制、終了引き継ぎを処理します。正常終了と取消終了は区別され、古いtargetを引き継ぎません。
