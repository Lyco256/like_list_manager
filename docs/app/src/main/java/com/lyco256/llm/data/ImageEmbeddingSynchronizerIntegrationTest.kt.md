# `ImageEmbeddingSynchronizerIntegrationTest.kt`

隔離Room DBとtest fileを使い、fake decoder／fake image embedderで対象asset、fingerprint再利用、path-only変更、file signature変更、削除／復元、DB null、保存先切替、初回推論途中停止からの再開、decode／推論失敗時の旧row保持とreconcile継続境界を確認します。remote／preview URLからの取得は行いません。
