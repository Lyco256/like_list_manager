# `MediaGridPlaceholderRendering.kt`

`app/src/main/java/com/lyco256/llm/MediaGridPlaceholderRendering.kt`

メディアグリッドのセル単位Placeholder描画と、画像表示状態の純粋な判定を担当します。

## 表示状態

- `Placeholder`: `Waiting` / `Generating`、`Ready`だが現在の画像モデルで`onSuccess`前、または再生成・再読込中。
- `Image`: 現在の`MediaGridThumbnailSource`と`Ready`ファイルモデルに対する`AsyncImage.onSuccess`済み。
- `Error`: `Failed`、利用可能な画像元なし、`downloadState == "failed"`。

画像モデルの成功状態はセル内のCompose stateに保持し、`remember(thumbnailSource)`でsource変更時に破棄します。状態判定は`mediaGridCellVisualState`としてJVM単体テスト可能です。

## Placeholder描画

`mediaGridPlaceholder`は`Placeholder`時だけ`drawWithCache`を構成します。Brushはセルのサイズとテーマ由来の不透明な開始色・終了色から作り、左上から右下までセル内で完結します。フレームごとのBrush、色List、Offset生成、グリッド全体座標の利用はありません。

`Image`ではPlaceholder描画自体を行わず、`Error`では単色のセル背景と既存のエラーアイコンだけを表示します。アニメーション、罫線、余白、`SubcomposeAsyncImage`は追加しません。

## 関連テスト

- `app/src/test/java/com/lyco256/llm/MediaGridPlaceholderRenderingTest.kt`
- `app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt`
- `app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt`
