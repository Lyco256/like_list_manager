# LocalSearchEngine

文字列queryから `LocalSearchResult(clipId, score)` を返す、完全ローカルの独立検索API。既存検索UI、一覧、Room、保存・編集・Undoへは接続しない。

- `AppContainer` の共有Sudachi、EmbeddingGemma、Japanese CLIPを借用する。生成時とblank検索時は解析器・model・ANNを初期化しない。
- `DerivedSearchStorage` の読み出し専用境界からlexical / semantic / imageのrevisionとdocumentを取得する。lexicalのFTS候補とcache更新は同じstorage mutex区間のデータを使う。
- 全cacheを更新してからFTS候補を取得する。ANN構築中のlexical書き換えも、FTS取得と同じmutex区間でrevisionを再確認してcacheへ反映する。
- 通常FTSのphrase / AND、trigram、短query部分一致、bounded OSA typo、低優先度anagram、本文・概要・OCR semantic、画像cross-modalを統合する。fallback走査では距離計算の前に通常一致も確認する。
- semanticは768次元、imageは256次元。ANN候補数は各 `min(2048, document数)`。同じclipのchunk／画像は最大値で集約する。空のmodalityはANN構築・query embeddingを行わない。
- semantic keyはclipId / sourceType / sourceOrdinal / documentId順の連番Long、image keyはassetId。String hashをkeyに使わない。
- 各ANN cacheはrevision不変なら再利用し、変更時だけ全件から再構築する。build成功・cancellation確認後に交換し旧snapshotをcloseする。build失敗は例外を返し、古いrevisionやpartial snapshotを検索しない。次検索で再試行する。
- text source weightは本文1.00／概要0.95／OCR0.90。画像はclip内最大cosine。cosineはfinite確認後0〜1へclampする。
- `LocalSearchTuning` に重み `0.64 lexical + 0.24 text + 0.12 image` と足切り `lexical >= 0.30 OR text >= 0.20 OR image >= 0.18` を集約する。
- 総合score降順、lexical score降順、clipId昇順。公開scoreは内部ランキング用でpercentageではない。
- `search()` はDefault dispatcherと内部Mutexで直列化し、両modelは逐次推論する。走査・build準備・集約ではcancellationを確認し、partial resultを返さない。
- `suspend close()` は検索と直列化し冪等。所有ANNとcacheを解放する。共有runtime／storageはcloseしない。close後のblankを含むsearchはIllegalStateException。

`LocalSearchEngineTest` はfake境界でcache、候補集約、異常・cancel・closeを確認する。`LocalSearchEngineIntegrationTest` は隔離派生DB、fake query runtime、実FTS / 768 ANN / 256 ANNを統合する。自然言語の品質・実データ目視評価は行わない。
