# `TagHierarchyTest.kt`

## 対応ソース

`app/src/test/java/com/lyco256/llm/TagHierarchyTest.kt`

## 役割

子孫タグ展開、グループ件数の投稿重複除外、必須AND＋含まれるOR、同一親の重複名禁止、子孫グループへの循環移動禁止をローカルJVMで検証します。

## 実行

`testDebugUnitTest` で実行します。日本語を含む作業パスでGradleのテストclasspathが文字化けする環境では、ASCIIの仮想ドライブから同じリポジトリを実行します。
