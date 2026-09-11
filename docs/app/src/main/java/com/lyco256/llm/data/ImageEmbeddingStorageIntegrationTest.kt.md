# `ImageEmbeddingStorageIntegrationTest.kt`

Bundled SQLiteのimage embedding tableについて、1024-byte BLOB、asset／clip単位の読み書きと削除、replace transaction rollback、clear、close/reopen、schema不一致時の派生DBだけの再作成を確認します。
