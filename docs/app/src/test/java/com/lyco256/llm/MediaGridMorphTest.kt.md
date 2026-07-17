# `MediaGridMorphTest.kt`

## 対応ソース

`app/src/test/java/com/lyco256/llm/MediaGridMorphTest.kt`

## 役割

メディアグリッドの列数変更ヘルパーと、後続のアニメーション実装用に保持しているMorph純粋ロジックをJVM単体テストで検証します。

## 主な確認

- `mediaGridColumnCountAfterPinchRelease` の閾値未満、最終累積比率、方向反転、キャンセル、2〜12列境界、1回の操作で最大1列という契約
- Morphの進行値、release後のcurrent/target settle、source revisionキャンセル、target handoffの一回性
- Asset slotの対応、4→5列の右端ゼロ幅slot、Headerの追加・削除・タイトル変更、Rect/alphaの連続性と有限性
- 10,000件入力でもviewport近傍だけを計画すること

現行プロダクトのピンチ経路が直接使うのは列数変更ヘルパーで、Morph session/overlayのテストは将来のアニメーション経路を守るために残しています。
