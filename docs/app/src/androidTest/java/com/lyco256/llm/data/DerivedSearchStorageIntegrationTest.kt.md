# `DerivedSearchStorageIntegrationTest.kt`

Bundled SQLite上の派生検索ストレージを隔離integration variantで検証します。

- 通常FTS5と`trigram` tokenizerの作成・検索を確認します。
- 1 clip分の通常テーブル、両FTS索引、fingerprintの原子的な置換・削除、全初期化を確認します。
- close/reopen、複数coroutineからの並行操作、schema version不一致、破損DBの1回再作成を確認します。
- 検索DBの初期化・復旧前後で、integrationTestの正本Room DB、画像ディレクトリ、投稿保存先設定、API設定が不変であることを確認します。
- fixtureの既知文字列に対するID取得とfingerprint状態だけを検証し、検索精度・意味的順位・実機データ目視は扱いません。
