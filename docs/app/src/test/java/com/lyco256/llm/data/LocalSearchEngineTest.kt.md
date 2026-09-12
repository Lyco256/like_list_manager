# LocalSearchEngineTest

fake storage / analyzer / query embedder / ANNによる処理契約テスト。候補sourceごとのadmission、clip重複排除、chunk・画像最大値集約、finite score・同値順、blankと空modality、独立revision cache、交換後close、build失敗・cancel時の旧cache不使用と再試行、検索serialize・cancel待機、冪等close・close後拒否を確認する。自然言語精度fixtureは使わない。

全cache更新→FTS→text推論→image推論の順序と、ANN構築中にlexical revisionが変わった場合のFTS取得時cache再整合も確認する。
