# `MediaGridThumbnailManager.kt`

## 2026-07-18 第2実装

- Source snapshot、最新viewport、foreground、生成中job、completed cache identity、表示再試行を1本のserial coordinatorだけが変更する。
- Viewportは最新値メールボックスに保存し、pixel単位の`centerDistance`は構造同一性に使わない。表示セルの安定ID・source indexの並び、列数、source revisionだけが再処理条件になる。
- 生成中はviewportの保存だけを行い、生成完了時に最新状態から次候補を1回だけ選ぶ。古いrevision、dispose後の生成完了・表示結果は適用しない。
- UIのholder registryはscheduler状態から分離した`ConcurrentHashMap`で、`state()` / `stateIfPresent()`はcoordinatorを待たずにholderの`StateFlow`を返す。UIはmutableなWorkを共有しない。
- source更新時だけ`sourceByAssetId`と`sourceIndex`を構築し、viewportごとのMap/Set/全work状態コピーを行わない。
- 表示中、前後1行、wide preparationは既存の範囲と優先順を維持し、各範囲を直接走査して候補を選ぶ。生成は常に1件直列。
- 画像形式、256px JPEG quality 60、placeholder、保存先、display failureの1回再試行、foreground pauseは変更していない。

`app/src/main/java/com/lyco256/llm/data/MediaGridThumbnailManager.kt`
