# `MediaGridThumbnailManager.kt`

2026-07-17: Viewport updates are delivered through `MediaGridViewportSnapshot` and a single latest-value worker. The UI sends only stable asset IDs, source indexes, center distances, column count, and source revision; source lookup and thumbnail work remain in the manager.

`app/src/main/java/com/lyco256/llm/data/MediaGridThumbnailManager.kt`

本番メディアグリッドのThumbnail生成キューを管理します。1件直列で、完了ごとに最新viewportから表示中・隣接行・広域準備の優先順位を再計算します。source revision、generation token、StateFlow、表示失敗時の1回再試行、foreground/background pauseを保持します。benchmark settings、metrics、counter、Traceは受け取りません。
