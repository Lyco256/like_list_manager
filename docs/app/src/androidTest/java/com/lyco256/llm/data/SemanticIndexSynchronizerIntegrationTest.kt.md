# `SemanticIndexSynchronizerIntegrationTest.kt`

in-memory Room、fake `DocumentEmbedder`、隔離派生DBで同期状態機械を確認します。

- 初回build、固定source順、Unicode長文OCRの複数chunk
- likeCountなど対象外fieldの無再embedding、source単位の更新、blank削除
- clip削除／同じclip IDの復元、1 source失敗時の旧データ保持と他source／clip継続
- stop／restart、処理済みsourceの再embedding回避、DB null時の無削除、最新snapshotのみ反映
- モデル初期化失敗をreconcile内で一度だけ扱い、次回startで再試行

fakeは受け取ったdocument textを記録し、embedding値の意味や類似度は検証しません。
