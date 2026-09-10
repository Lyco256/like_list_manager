# `LexicalIndexSynchronizerIntegrationTest.kt`

隔離Room in-memory DB、派生検索DB、fake analyzerで初回構築・新規追加・概要/OCR編集・削除・復元、検索対象外field変更、再起動相当の再start、派生DB再構築、正本DB一時不可、1 clipの解析失敗継続を確認します。FTSはfixtureの登録・更新・削除確認だけに使い、検索精度・順位は評価しません。
