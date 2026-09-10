# `DerivedSearchStorage.kt`

再生成可能なローカル文字検索用SQLiteストレージです。正本Room DB、投稿画像、Undo、投稿保存先設定とは別の派生データだけを保持します。

- 保存先は`noBackupFilesDir/derived_search/search_index.db`です。投稿データ保存先の内部／SDカード領域には置きません。
- `androidx.sqlite:sqlite-bundled:2.7.0`の`BundledSQLiteDriver`を`SQLITE_OPEN_FULLMUTEX`付きで使用します。
- 生のSQLite connectionは公開せず、内部の単一connectionと`Mutex`で全操作を保護します。
- `lexical_documents`、通常FTS5、`trigram` tokenizerのFTS5、`lexical_sync_state`（clip単位のSHA-256 fingerprint）をschema version 2として管理します。
- `replaceClipDocuments(clipId, sourceFingerprint, documents)`は1 clipの通常テーブル、2索引、fingerprintを旧削除後に1トランザクションで置換します。Sudachi解析は呼び出し側でtransaction開始前に完了します。
- `deleteClipDocuments`は通常テーブル、2索引、fingerprintを同時に削除し、`getAllClipFingerprints`は同期用mapを一括取得します。通常検索、trigram検索、全初期化、closeも提供します。
- schema version不一致、必須schema欠落、integrity check失敗などを検出した場合は、派生DB本体とSQLite sidecarだけを1回再作成します。2回目も失敗した場合は例外を返し、正本データへは触れません。
- Sudachiによる正規化・読み生成、fingerprint計算、backfill、Embedding、ANN、既存検索UIとの接続はこの層の責務外です。生成済みの各テキスト列とfingerprintを後続処理から渡します。

`DerivedSearchStorageIntegrationTest`はBundled SQLite上のFTS5/trigram動作、通常テーブル・fingerprintを含むclip置換・削除・clearの原子性、close/reopen、並行アクセス、schema不一致・破損復旧、およびintegration variantの正本DB・画像・設定不変を確認します。
