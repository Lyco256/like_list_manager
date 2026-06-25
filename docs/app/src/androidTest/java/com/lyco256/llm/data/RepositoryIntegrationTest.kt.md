# `RepositoryIntegrationTest.kt`

テスト専用Room DBとFake API/OAuthで、再同期時の手動概要・タグ保持、重複排除、pagination、空ページ終端、401 refresh、refresh失敗時の旧session保持、2ページ目失敗後のcontinuation再開、403/429/500、月間停止、WebP画像保存、画像失敗時の投稿保持、無効な保存先への移動失敗時のDB/画像維持、タグ/グループ削除時の投稿保護を検証します。
