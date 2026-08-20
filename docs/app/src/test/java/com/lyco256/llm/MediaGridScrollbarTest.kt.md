# `MediaGridScrollbarTest.kt`

メディアグリッド高速スクロールバーの純粋計算とdrag session契約を固定します。

- scroll不能、先頭／中間／末尾、最小thumb高、fractionのclampを確認します。
- 列数2〜12のframeでordinal targetが見出しitemを選ばないことを確認します。
- drag sessionのframe／target基準固定、最新targetのconflation、frame変更無効化、pointer cancel解除を確認します。
- pointer解放後の最終target保持、正常完了と取消終了の識別、完了sequenceを確認します。
