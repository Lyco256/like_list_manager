# `MediaGridScrollbarUiTest.kt`

隔離テストアプリ上のComposeで、全header boundaryから作ったスクロールバー横の見出しピルと、visual scrollbarの画面上bounds契約を確認します。

- visual track/thumbがグリッド右端に一致し、32dpタッチ領域がvisual要素の左側へ広がることを、`boundsInRoot`で確認します。
- 通常、drag中、final target pending開始後もtrack/thumbの横位置を維持し、見出しピルがtrack左側へ配置されることを確認します。
- 投稿日順の実pointer DOWN/MOVEで現在frameの全見出しlabelが同時表示され、thumbを移動しても各ピルが開始ordinal位置に固定され、pointer UP後に全て消えることを確認します。
- いいね数順でも全boundaryを表示し、保存順では表示しないことを確認します。
- 旧12dp×4dp横長highlightとthumb追従の単一文字ピルが存在しないことを確認します。
- 上部現在位置ピルを同時に表示せず、pointer cancelで見出しピルが残らないことを確認します。
