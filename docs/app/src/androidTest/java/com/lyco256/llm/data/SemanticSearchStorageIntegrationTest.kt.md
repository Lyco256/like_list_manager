# `SemanticSearchStorageIntegrationTest.kt`

TEST_HARNESSの隔離noBackup派生DBでsemantic storage境界を確認します。

- source単位のchunk置換、fingerprint更新、close/reopen、source／clip削除
- lexical dataとsemantic dataを同時に消す`clear`
- insert triggerによるtransaction rollbackで旧documents／fingerprintを保持
- dimension、finite値、3072-byte BLOB、document ID、ordinalの入力検証と破損BLOBの読み出し拒否
- schema version mismatch時の派生DBだけの再作成

正本Room、投稿画像、認証情報は使用せず、テスト終了時にテスト専用派生ディレクトリだけを削除します。
