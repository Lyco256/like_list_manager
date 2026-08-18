# 対応ソース

`app/src/test/java/com/lyco256/llm/TagTreeGuideTest.kt`

## 役割

タグ管理Treeの縦guide metadata計算を確認するJVM単体テストです。

## 確認内容

- depth 0〜3でguide本数がdepthと一致すること。
- siblingとnested siblingが混在する列で、祖先subtreeが続くguideだけが下の行へ接続し、subtree末尾で不要なdepthの接続が終了すること。
- expand/collapse後のvisible row列からguide metadataが再計算されること。
- drag placeholderを挿入した表示列でも、placeholderと隣接行の縦guideが同じdepthでつながること。

## 変更時の確認事項

Tree flattening、expand/collapse、drag placeholderのdepthや挿入位置を変えた場合は、guide metadataの接続期待も同時に確認します。
