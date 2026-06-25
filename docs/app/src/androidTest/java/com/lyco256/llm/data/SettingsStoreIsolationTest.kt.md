# 対応ソース

`app/src/androidTest/java/com/lyco256/llm/data/SettingsStoreIsolationTest.kt`

## 役割

隔離package内の専用EncryptedSharedPreferencesだけを使い、Client IDとOAuth sessionの保存、読込、sessionのみの消去、全消去を実機で確認します。

## 安全条件

- `TEST_HARNESS` と `com.lyco256.llm.test` を開始時に必須化します。
- production用およびvariant既定のPreferences名を使いません。
- tokenは固定の `test-` 値だけを使い、各テスト前後に専用Preferencesを消去します。

## 関連ファイル

- `app/src/main/java/com/lyco256/llm/data/ApiSettingsStore.kt`
- `app/src/main/java/com/lyco256/llm/data/AppContainer.kt`
