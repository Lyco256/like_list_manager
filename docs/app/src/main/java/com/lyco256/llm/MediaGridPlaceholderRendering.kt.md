# `MediaGridPlaceholderRendering.kt`

`app/src/main/java/com/lyco256/llm/MediaGridPlaceholderRendering.kt`

## 2026-07 direct preview pipeline

- `Placeholder` is shown while the current direct Coil candidate is loading or while fallback advances.
- `Image` is shown only after the current candidate identity reports `AsyncImage.onSuccess`.
- `Error` is shown only when there are no candidates or every candidate has failed. A failed stored download does not force Error when preview or remote candidates exist.
- The existing static cell-local `drawWithCache` placeholder is retained. No shimmer, crossfade, `SubcomposeAsyncImage`, format conversion, or RGB_565 path is used.

メディアグリッドのセル単位Placeholder描画と、画像表示状態の純粋な判定を担当します。

## 表示状態 (旧サムネイル経路の履歴)

- `Placeholder`: `Waiting` / `Generating`、`Ready`だが現在の画像モデルで`onSuccess`前、または再生成・再読込中。
- `Image`: 現在の`MediaGridThumbnailSource`と`Ready`ファイルモデルに対する`AsyncImage.onSuccess`済み。
- `Error`: `Failed`、利用可能な画像元なし、`downloadState == "failed"`。

旧サムネイル経路の説明です。現行の候補 identity と `AsyncImage` 成功状態は、上記 direct preview pipeline の実装に従います。

## Placeholder描画

`mediaGridPlaceholder`は`Placeholder`時だけ`drawWithCache`を構成します。Brushはセルのサイズとテーマ由来の不透明な開始色・終了色から作り、左上から右下までセル内で完結します。フレームごとのBrush、色List、Offset生成、グリッド全体座標の利用はありません。

`Image`ではPlaceholder描画自体を行わず、`Error`では単色のセル背景と既存のエラーアイコンだけを表示します。アニメーション、罫線、余白、`SubcomposeAsyncImage`は追加しません。

## 関連テスト

- `app/src/test/java/com/lyco256/llm/MediaGridPlaceholderRenderingTest.kt`
- `app/src/androidTest/java/com/lyco256/llm/MainActivityComposeTest.kt`
- `app/src/androidTest/java/com/lyco256/llm/UiStateRenderingTest.kt`
