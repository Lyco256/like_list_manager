# 対応ソース

`app/src/test/java/com/lyco256/llm/TagTreeGuideContractTest.kt`

## 役割

タグ管理Treeのguide描画が縦segmentだけで構成されることを確認するソースcontractテストです。

## 確認内容

`drawTagTreeGuides` が同じX座標を始点と終点に使う `drawLine` で描画し、横branch用のpathを持たないことを確認します。
