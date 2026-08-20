# `MediaGridScrollbarUiTest.kt`

隔離テストアプリ上のComposeで、スクロールバーtarget ordinalから作ったthumb横位置labelの表示契約を確認します。

- drag開始直後にlabelが表示され、投稿日順のtarget位置へ即時に追従することを確認します。
- 上部現在位置ピルを同時に表示しないことを確認します。
- pointer cancelでlabelが残らないことを確認します。
