# `ImageEmbeddingSynchronizer.kt`

現在選択されている正本Room DBの`ClipDao.observeAssets()`を監視し、`photo`／`video_thumbnail`のうち利用可能なlocal fileだけをasset ID順に逐次処理します。

- `localPath`以外のremote／preview URLは参照せず、DB nullは0件扱いせず停止します。
- file signatureを含むfingerprintが一致するassetはdecode・推論を省略します。pathだけ変わりsignatureが同じ場合も再利用し、length／lastModifiedが変われば再推論します。
- Roomの新しいasset snapshotは`conflate`で集約し、`mapLatest`で処理中の古いreconcileをcancelして最新snapshotへ収束させます。
- decode失敗はasset単位で継続し、推論失敗は同じreconcile内の無意味な再試行を避けてFAILEDで終了します。いずれも旧正常rowを先に削除しません。
- 推論・検証完了後だけ`DerivedSearchStorage.replaceImageEmbedding`を呼び、処理中のBitmapは必ずrecycleします。
- stateは`NOT_STARTED`／`SYNCING`／`COMPLETE`／`FAILED`、対象数、処理数、再embedding数、失敗asset ID、最後のエラーを公開します。UI接続は行いません。

productionではApplication scopeから非同期開始し、TEST_HARNESSでは明示起動だけを許可します。投稿保存、削除、Undo、保存先移動へ直接処理を追加しません。
