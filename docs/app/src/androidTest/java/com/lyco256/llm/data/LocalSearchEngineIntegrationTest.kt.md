# LocalSearchEngineIntegrationTest

`TEST_HARNESS` と `com.lyco256.llm.test` を必須とし、cache配下の専用fixtureディレクトリへ `ContextWrapper` で派生DBを隔離する。production DB・画像・認証情報を使用しない。

- 実Bundled SQLiteの通常FTS / trigram、fake query embeddingと実USearchの768 / 256 ANNによるend-to-end検索。
- 重複排除、finite、決定論的sort、revision変更後のcache更新、反復検索、close後native拒否。
- 全replace / delete / clearの独立revision、transaction内trigger failureでのrollback不変、schema不一致復旧での全revision更新。
- 105 documentで固定100件打切りがないこと、revision・document・FTS候補の整合。
- 記号・引用符・URL・日本語・全角・emoji queryの実FTS実行。自然言語のヒット品質は評価しない。

実行入口は `scripts/run-safe-integration-check.cmd`。
