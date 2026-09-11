# `ImageEmbeddingVisionIntegrationTest.kt`

隔離Room DBとtest画像fileに対し、実Japanese CLIP vision runtimeで256次元embeddingの保存、fingerprint一致時の推論再利用、clip削除時の派生row削除を確認します。類似度、duplicate判定、検索順位、実データ目視は評価しません。
