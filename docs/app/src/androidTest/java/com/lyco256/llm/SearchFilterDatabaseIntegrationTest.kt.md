# `app/src/androidTest/java/com/lyco256/llm/SearchFilterDatabaseIntegrationTest.kt`

隔離された `com.lyco256.llm.test` 上で、Room DBに投入した投稿・タグ・グループを `ClipRepository` のFlowから取得し、分類済み検索ロジックへ渡す一気通貫Instrumentationテストです。

主な検証内容:

- Repositoryの `clipsWithDetails` と `tagHierarchy` が、Room上のタグ階層・投稿タグ関係を検索入力として構成できること
- 正規表現、検索対象、投稿日、投稿者、グループ必須、タグ排除、タグ付きのみを同時に適用して対象投稿だけが残ること
- 検索処理が投稿、タグ、グループ、投稿タグ関係を変更しないこと
- version 9 schemaの全現存clipとrelationをfingerprintし、廃止済みの `isDeleted` に依存しないこと

本番package、実X API、実OAuth tokenは使いません。変更時は `scripts/run-safe-integration-check.cmd` で、同じ実機上の本番package metadataが前後不変であることも合わせて確認します。

## 2026-07 OCR update

- Added search coverage for the OCR text field so OCR content is included in text search targets.
