# `ImageEmbeddingFingerprint.kt`

保存画像の再推論判定用SHA-256 fingerprintとfile signatureを定義します。asset ID、clip ID、mediaKey、type、`sizeBytes`、実ファイルのlength／lastModified、Japanese CLIP revision、画像前処理revision、同期revisionを決定論的な長さ付きUTF-8 fieldとして含めます。`localPath`文字列や画像内容hashは含めません。
