# `MediaGridScrollbarUiTest.kt`

隔離テストアプリ上のComposeで、スクロールバーtarget ordinalから作ったthumb横位置labelと、visual scrollbarの画面上bounds契約を確認します。

- visual track/thumbがグリッド右端に一致し、32dpタッチ領域がvisual要素の左側へ広がることを、`boundsInRoot`で確認します。
- 通常、drag中、final target pending開始後もtrack/thumbの横位置を維持し、labelがthumb左側へ配置されることを確認します。
- drag開始直後にlabelが表示され、投稿日順のtarget位置へ即時に追従することを確認します。
- 上部現在位置ピルを同時に表示しないことを確認します。
- pointer cancelでlabelが残らないことを確認します。
