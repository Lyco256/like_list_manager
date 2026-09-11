# `SemanticModels.kt`

`SemanticDocument`は派生semantic DBへ保存する1 chunk分の値です。clip ID、`SemanticSourceType`、source ordinal、決定論的document ID、768次元Float embeddingを持ち、正本Roomへは追加しません。

`SemanticSourceKey`は同期fingerprintのキーで、`(clipId, sourceType)`単位の差分・削除・失敗状態を表します。
