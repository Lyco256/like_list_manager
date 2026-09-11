# `SemanticIndexEmbeddingIntegrationTest.kt`

実機のTEST_HARNESSで、実際の`LocalTextEmbedder`を正本Roomとsemantic派生DBへ接続する一気通貫テストです。

text、summary、長いOCRを初回同期し、Unicode chunkが複数になること、768次元finite embeddingが保存・読出しできることを確認します。その後OCR更新、clip削除、同じclip IDでの復元と再同期を確認します。

embedding値の意味、cosine、semantic precision、順位、検索UIは判定対象にしません。Roomはin-memory、派生DBはTEST_HARNESS専用領域を使います。
