# `MediaGridThumbnailStore.kt`

`app/src/main/java/com/lyco256/llm/data/MediaGridThumbnailStore.kt`

The store implements `MediaGridThumbnailStoreGateway`, allowing coordinator behavior to be unit-tested with a fake gateway without changing production image generation.

## 2026-07-19 第5実装: canonical cache lookup

- `mediaGridThumbnailCacheKey()` is the single identity rule used by generation and lookup. It includes asset ID, media key, resolved input name, actual local file size, and actual local modified time.
- `findCached()` only resolves the expected completed `.jpg` path and accepts it when it is a non-empty regular file. It performs no network access, decode, resize, JPEG compression, or output creation.
- Local files and preview/remote URL sources use the same resolution logic. `allowRemote = false` remains wide-preparation-only behavior and does not select a URL source.
- Temporary files and zero-length files are not cache hits; `invalidate()` deletes the completed file and the manager drops its in-memory miss/completion identity before retrying.

本番メディアグリッド用の `cacheDir/media_grid_thumbnails` を管理します。元画像を最大256px相当で縮小デコードし、256×256のJPEG quality 60へ変換して一時ファイルから原子的に配置します。表示中はpreview/remote URLへフォールバックし、広域準備ではlocal fileだけを使います。計測用依存はありません。
