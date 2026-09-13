# `LocalRelatedTweetsEngine.kt`

派生DBへ既に保存されているsemantic/image embeddingだけから、選択clipに関連するclip IDを取得する専用read-only engineです。`LocalSearchEngine`の文字検索や`LocalImageDuplicateSearchEngine`の画像重複グルーピングへ関連ロジックを混在させません。

## 検索契約

- referenceは選択中の1 clipです。`TEXT`、`SUMMARY`、`OCR`の768次元semantic documentと、clipの256次元image embeddingだけをqueryに使います。
- 新しいEmbedding推論、画像decode、EmbeddingGemma query prompt、Japanese CLIP text tower、Sudachi、FTS5、trigramは呼び出しません。
- reference vectorごとに最大256件だけUSearch ANNへ問い合わせ、candidate clipのunionを作ります。全clip pairの比較は行いません。
- 同一channelの複数chunk／画像はcandidateごとの最大cosineだけを使います。weightは本文0.40、概要0.20、OCR0.20、画像0.20で、referenceに存在するchannelのweightだけを分母へ含めます。
- 最大channel 0.25以上かつ正規化総合score 0.20以上のcandidateを、総合score降順・最大channel降順・clip ID昇順で最大50件返します。selected clip自身は除外します。

`RelatedTweetResult.score`はengine内部のsort用で、UI stateには保存せず表示もしません。`RelatedTweetsProgress`は`PREPARING`、`RETRIEVING`、`RANKING`とprocessed/totalを提供します。

## revision/cache

`DerivedSearchStorage.getRelatedRetrievalSnapshot`は、semantic/image revision、reference rows、必要な場合だけ全corpus rowsを1回のstorage Mutex区間で読みます。semantic/image ANN cacheはrevision単位で保持し、revision不変なら別clipへの切替でも再buildしません。revision変更時は対象snapshotを完全buildしてから交換し、build失敗・cancel時のpartial snapshotや旧revisionと新revisionの混在は採用しません。交換後の旧snapshotはcloseされます。

engineは内部Mutexでbuild/replace/searchを直列化し、長いloopでcancelを確認します。明示`close()`はengineが所有するANNだけを冪等にcloseし、DerivedSearchStorageやEmbedding runtimeはcloseしません。

## 関連UIとの境界

`MainViewModel`は選択clipの詳細Flowと関連検索stateを分離します。詳細clip IDを即時設定し、semantic/image synchronizerが`SYNCING`の間だけ関連sectionを待機させます。`FAILED`／`NOT_STARTED`では保存済み派生snapshotで検索します。選択変更時は前jobをcancelしてrequest generationを更新し、古いprogress/resultを新clipへ適用しません。

`LocalRelatedTweetsEngineTest`、`LocalRelatedTweetsEngineIntegrationTest`、`RelatedTweetsViewModelIntegrationTest`、`RelatedTweetsUiTest`で、集約・閾値・cache lifecycle・実USearch・state遷移・UIの非ブロック境界を確認します。自然言語的な関連度品質、期待順位、実データ目視はテスト対象外です。
