# `SemanticIndexSynchronizer.kt`

現在選択されている正本Room DBのclip snapshotを、独立した`DerivedSearchStorage`のsemantic tablesへ逐次反映します。

- `PostStorageManager.database`がnullの間は停止し、既存semantic dataを削除しません。DBが空の実snapshotなら残存clipを削除します。
- clip ID昇順、sourceはtext → summary → OCR、chunkはordinal順で処理します。fingerprintが同じsourceは再embeddingせず、blank sourceは削除します。
- 1 sourceの全chunkをembedしてから`replaceSemanticSource`へ渡すため、失敗時は旧documentsとfingerprintを保持します。通常のembedding失敗はsourceをfailedとして次へ進み、モデル初期化失敗は同じreconcileを停止して重複初期化を避けます。
- `state`は`NOT_STARTED`／`SYNCING`／`COMPLETE`／`FAILED`と対象source数、処理数、再embedding数、失敗キー、最後のエラーを公開します。`start`はactive jobがある場合に二重起動せず、`stop`で現在のreconcileを安全に中断します。

productionではApplicationのバックグラウンドscopeから起動し、TEST_HARNESSではintegration testが明示的に起動します。検索UI、ANN、similarity、ranking、Repositoryの個別編集処理はこの同期の責務に含めません。
