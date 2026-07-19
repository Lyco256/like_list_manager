# `MediaGridPlaceholderRenderingTest.kt`

`app/src/test/java/com/lyco256/llm/MediaGridPlaceholderRenderingTest.kt`

`mediaGridCellVisualState`の純粋な表示状態判定を確認するJVM単体テストです。

- `Waiting` / `Generating`はPlaceholderになる。
- `Ready`は現在モデルの`onSuccess`がまだなければPlaceholder、成功済みモデルだけImageになる。
- sourceまたは画像モデルが変わった場合、以前の成功状態を引き継がない。
- `Failed`、画像元なし、`downloadState == "failed"`はErrorになり、Placeholderを描画しない。
