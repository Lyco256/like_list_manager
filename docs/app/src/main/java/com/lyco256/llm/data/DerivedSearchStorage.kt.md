# `DerivedSearchStorage.kt`

再生成可能なローカル文字検索用SQLiteストレージです。正本Room DB、投稿画像、Undo、投稿保存先設定とは別の派生データだけを保持します。

- 保存先は`noBackupFilesDir/derived_search/search_index.db`です。投稿データ保存先の内部／SDカード領域には置きません。
- `androidx.sqlite:sqlite-bundled:2.7.0`の`BundledSQLiteDriver`を`SQLITE_OPEN_FULLMUTEX`付きで使用します。
- 生のSQLite connectionは公開せず、内部の単一connectionと`Mutex`で全操作を保護します。
- `lexical_documents`、通常FTS5、`trigram` tokenizerのFTS5、`lexical_sync_state`（clip単位のSHA-256 fingerprint）に加え、`semantic_documents`と`semantic_source_sync_state`をschema version 3として管理します。semantic embeddingは768個のFloat32をlittle-endianで3072 bytesのBLOBに保存します。
- `replaceClipDocuments(clipId, sourceFingerprint, documents)`は1 clipの通常テーブル、2索引、fingerprintを旧削除後に1トランザクションで置換します。Sudachi解析は呼び出し側でtransaction開始前に完了します。
- `deleteClipDocuments`は通常テーブル、2索引、fingerprintを同時に削除し、`getAllClipFingerprints`は同期用mapを一括取得します。通常検索、trigram検索、全初期化、closeも提供します。
- `replaceSemanticSource`は1つの`(clipId, sourceType)`について、全chunkのembedding検証後に既存documentとfingerprintを同一transactionで置換します。`deleteSemanticSource`、`deleteSemanticClip`、semantic fingerprint/document読み出し、semanticを含む`clear`も提供します。
- semantic document ID、source ordinal、source type、embedding dimension／finite値／BLOB長はstorage境界で検証し、壊れたBLOBの読み出しも拒否します。
- schema version不一致、必須schema欠落、integrity check失敗などを検出した場合は、派生DB本体とSQLite sidecarだけを1回再作成します。2回目も失敗した場合は例外を返し、正本データへは触れません。
- Sudachiによる正規化・読み生成、semantic chunk化、fingerprint計算、Embedding、ANN、既存検索UIとの接続はこの層の責務外です。生成済みのlexical documentまたはsemantic documentとfingerprintを後続処理から渡します。

`DerivedSearchStorageIntegrationTest`はBundled SQLite上のFTS5/trigram動作、通常テーブル・fingerprintを含むclip置換・削除・clearの原子性、semantic sourceのchunk置換・削除・BLOB検証・transaction rollback、close/reopen、並行アクセス、schema不一致・破損復旧、およびintegration variantの正本DB・画像・設定不変を確認します。
