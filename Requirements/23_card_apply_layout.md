# 23. タグ横スクロール右側へ固定「適用」ボタン

## 目的

カード内tag操作を横にコンパクト化し、Applyボタンをtagスクロールに流されない固定位置へ置く。

## UI仕様

- ボタン文言を `タグを付ける` から `適用` へ変更する。
- 未分類・分類済み通常カード・MediaGrid previewの全てで同じ配置。
- 1行のRow内で、
  - 左: tag/groupの横スクロール領域
  - 右: 固定された `適用` ボタン
  とする。
- `適用` をLazyRow/横scroll contentへ入れない。
- tagを横scrollしてもbutton位置は動かない。
- 22のdirty判定をenabledへ使う。
- tagが多い場合もscroll領域を確保しbuttonを画面外へ押し出さない。
- tagがない場合も案内表示とdisabled Applyで崩れない。

## テスト

- 3種類のcardに `適用` があり、旧文言がない。
- dirty=falseでdisabled、dirty=trueでenabled。
- 多数tag fixtureで横scroll後もApplyが表示されたまま。
- button tapとscroll gestureが競合しない。
