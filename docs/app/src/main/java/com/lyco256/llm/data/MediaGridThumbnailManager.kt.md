# `MediaGridThumbnailManager.kt`

## 2026-07-19 第5実装: viewport cache hydration

- After the existing 100 ms Idle gate and latest viewport application, one `Dispatchers.IO` job checks the visible cells and one adjacent row on each side. Candidates are ordered as visible first, then adjacent priority, and duplicate cache identities are checked once.
- The job calls `findCached()` only and returns one result event containing source revision, viewport token, hit source/file pairs, and confirmed miss identities. Hits are applied by the coordinator directly to `Waiting` holders as `Ready`; no generation job is used for a hit.
- Cache misses are remembered only for the current source revision to avoid repeated file stats when the viewport shifts. Miss records do not remove candidates from normal generation; generation success and display invalidation remove the corresponding record.
- Cache hydration is canceled on Dragging/Flinging, source revision change, foreground leave, viewport replacement, and dispose. Token/revision/viewport/source checks reject stale results. Hydration and normal/wide generation never run concurrently.
- Scheduler order is fixed: latest viewport, cache hydration, visible misses, adjacent misses, then wide preparation. Existing one-at-a-time generation, ranges, placeholders, display retry, and operation-state behavior remain unchanged.

## 2026-07-18 第2実装

- Source snapshot、最新viewport、foreground、生成中job、completed cache identity、表示再試行を1本のserial coordinatorだけが変更する。
- Viewportは最新値メールボックスに保存し、pixel単位の`centerDistance`は構造同一性に使わない。表示セルの安定ID・source indexの並び、列数、source revisionだけが再処理条件になる。
- 生成中はviewportの保存だけを行い、生成完了時に最新状態から次候補を1回だけ選ぶ。古いrevision、dispose後の生成完了・表示結果は適用しない。
- UIのholder registryはscheduler状態から分離した`ConcurrentHashMap`で、`state()` / `stateIfPresent()`はcoordinatorを待たずにholderの`StateFlow`を返す。UIはmutableなWorkを共有しない。
- source更新時だけ`sourceByAssetId`と`sourceIndex`を構築し、viewportごとのMap/Set/全work状態コピーを行わない。
- 表示中、前後1行、wide preparationは既存の範囲と優先順を維持し、各範囲を直接走査して候補を選ぶ。生成は常に1件直列。
- 画像形式、256px JPEG quality 60、placeholder、保存先、display failureの1回再試行、foreground pauseは変更していない。

## 2026-07-19 メディアグリッド スクロール改善 第4実装

- `MediaGridScrollOperationState` は `Idle` / `Dragging` / `Flinging` を持ち、操作状態、source、viewport、foreground、holder、表示結果、生成完了を既存の単一直列coordinatorへeventとして渡す。UIは状態変化時だけ通知し、pixel単位のviewport更新を操作状態eventへ変換しない。
- `Dragging` / `Flinging` 中は最新viewportだけをmailboxへ保持し、表示中・前後1行・wide preparationの新規生成を開始しない。操作開始時のwide生成だけはキャンセルし、通常生成1件の完了結果は反映するが、次候補へ連鎖しない。
- `Idle`への遷移では100msのtoken付き再開eventを予約する。再ドラッグ、source revision変更、dispose、foreground離脱はtokenとjobを無効化し、古い再開eventを無視する。再開時は最新viewportを適用してから、既存の表示中→前後1行→wideの優先順で選択する。
- `updateSourceSnapshot()` と `dispatchViewport()` はsource revisionをatomicに検査し、未登録・古いrevision・dispose後のviewportを拒否する。生成・再開の開始条件は常に`Idle`、foreground、再開待機完了、生成中なしを共通判定する。
- 画像形式、256px JPEG quality 60、placeholder、保存先、キャッシュidentity、通常時の同時生成数1件、候補範囲・優先順は変更していない。

`app/src/main/java/com/lyco256/llm/data/MediaGridThumbnailManager.kt`
