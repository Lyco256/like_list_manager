# `MediaGridThumbnailStore.kt`

`app/src/main/java/com/lyco256/llm/data/MediaGridThumbnailStore.kt`

The store implements `MediaGridThumbnailStoreGateway`, allowing coordinator behavior to be unit-tested with a fake gateway without changing production image generation.

本番メディアグリッド用の `cacheDir/media_grid_thumbnails` を管理します。元画像を最大256px相当で縮小デコードし、256×256のJPEG quality 60へ変換して一時ファイルから原子的に配置します。表示中はpreview/remote URLへフォールバックし、広域準備ではlocal fileだけを使います。計測用依存はありません。
