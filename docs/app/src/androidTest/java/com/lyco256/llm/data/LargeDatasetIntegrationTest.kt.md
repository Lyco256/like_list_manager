# 対応ソース

`app/src/androidTest/java/com/lyco256/llm/data/LargeDatasetIntegrationTest.kt`

## 役割

隔離されたin-memory Room DBへ10,000投稿を1 transactionで投入し、欠落、重複、並び順、検索対象件数を実機で確認します。メディアグリッド用10,000投稿テストでは、選択clipのclip/assetだけを対象clip-scoped queryで取得できることも確認します。

50件の固定risk seedでは本文/概要/投稿者欠落、削除済み、画像なし/単一/複数、video/GIF thumbnail、タグなし/単一/複数、同名タグ、深い/空グループを作成し、パターンが欠落していないことを確認します。

## 安全条件

- `TEST_HARNESS` とtest applicationIdを必須化します。
- 本番DB名や端末ファイルは使用しません。
- テスト終了時にin-memory DBを閉じます。

## 2026-07 OCR update

- Populates OCR text on a subset of the large dataset to keep search and storage coverage realistic.
