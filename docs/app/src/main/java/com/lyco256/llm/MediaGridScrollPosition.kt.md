# `MediaGridScrollPosition.kt`

分類済みメディアグリッドの一時的な現在位置ピルと、後続のスクロールナビゲーションで再利用できる現在位置算出を担当します。

- `currentMediaGridPosition` は `MediaGridViewportAnchorSignature.firstVisibleMediaOrdinal` から既存 `MediaGridOrdinalIndex` を使って代表セルを直接逆引きし、既存の `mediaGridMorphBucketSpec` で見出しと同じ日／週／月・いいね数区間のラベルを作ります。保存順、無効な代表セル、古いframe keyではラベルを返しません。
- 現在位置の算出はvisible ordinalの逆引きと単一セルのbucket生成だけで、`frame.items` 全走査、DB／Repositoryアクセス、画像処理、非同期読み込みを行いません。
- `MediaGridPositionPillState` は `Hidden`、`Visible`、`Hiding` と世代番号を持ちます。スクロール開始で表示し、停止後3秒の予約を設定し、期限後に上方向へ180msスライドアウトします。再スクロールは世代を進め、古いtimeout／終了イベントを無効化します。
- 非表示アニメーション中の再スクロールは同じComposableを表示へ戻し、ラベル変更だけでは再表示アニメーションをやり直しません。
- フィルター・検索・並べ替え・列数変更で通常frameが変わった場合は旧ピルComposableを即時に外し、古いラベルを終了アニメーション中も残しません。Morph中の列数変更だけは表示中のラベルを固定し、handoff後に新frameのbucket規則で再評価します。ピンチ単独ではHiddenのままです。
- UIはグリッド領域の上端中央へ配置し、非操作のSurfaceとして既存toolbar、セル操作、選択状態、インライン見出しのレイアウトを変更しません。
