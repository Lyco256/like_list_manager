# `LocalImageDuplicateSearchEngine.kt`

保存済み画像embeddingの単一snapshotを読み、Japanese CLIPの256次元ANNだけで画像重複候補を作る、通常の文字検索から独立したread-only engineです。画像を再decode・再embeddingせず、`DerivedSearchStorage.getImageSnapshot()`を検索開始時に一度だけ読みます。

- 0件／1件ではANNを構築せず、空の結果を返します。2件以上ではasset ID昇順の一つの256次元ANNを構築します。
- 各assetは`min(N, 33)`件を問い合わせ、selfを除いた最大32近傍だけを保持します。ANNが返したexact cosine scoreをそのまま使い、pairのcosineを再計算しません。
- `score >= 0.72`の一方向edge、または`score >= 0.55`かつ相互近傍のedgeだけを採用し、unordered pairをdeduplicateします。非finite score、unknown asset ID、重複asset IDは失敗として扱います。
- primitive-arrayのUnion-Findでedgeの連結成分を作り、孤立assetを除外します。各group内は最強edgeのpairから開始し、既配置endpointへのedge score最大順（同点はasset ID昇順）で追加します。隣接edgeのcompact indexとprimitive max-heapを使い、配置のたびに全edgeを再走査しません。groupは最大edge score降順、最小asset ID昇順です。
- 進捗はANN query単位で公開します。検索・edge構築・group構築の長いloopではcancellationを確認し、成功／失敗／cancelの全経路でその検索が所有するANNを`close`します。partial resultは返しません。

`AppContainer`はengineを一つだけ構築し、既存の`DerivedSearchStorage`とANN runtimeだけを共有します。画像embedding同期のstateが`SYNCING`の間は`MainViewModel`が待機し、`COMPLETE`以外の`NOT_STARTED`／`FAILED`でも保存済みsnapshotを使って検索を続行します。

`LocalImageDuplicateSearchEngineTest`はfake ANNで候補数、閾値、相互近傍、連結成分、決定的順序、非finite／例外／cancel時のcloseを確認します。`LocalImageDuplicateSearchEngineIntegrationTest`は隔離派生DBと実USearchを人工256次元vectorで接続します。semantic quality、accuracy、crop、threshold tuning、実データ目視はテスト対象にしません。
