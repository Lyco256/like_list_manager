# `LocalImageDuplicateSearchEngineTest.kt`

fake `SearchAnnFactory`／`SearchAnn`を注入し、画像重複検索の制御フローだけを検証します。snapshot一回読み、0/1件のANN skip、256次元ANN、`min(N,33)`候補、最大32近傍、self除外、strong／mutual threshold、unordered pair dedup、Union-Findの連結group、決定的group内／group間順序、非finite／ANN例外／coroutine cancellation時のcloseを固定します。画像の意味精度やthreshold tuningは検証しません。
