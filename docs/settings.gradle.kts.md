# 対応ソース

`settings.gradle.kts`

## 役割

`:app`、公式PaddleOCR Android SDKを取り込む`:ppocr-sdk`、隔離性能測定用の`:macrobenchmark` moduleを登録し、pluginとdependency repositoryを一元管理します。`com.quickbirdstudios:opencv` だけは `ppocr-sdk/libs` の16 KB対応AARへ解決する限定Ivy repositoryを使い、Android library moduleから直接file dependencyを持たせずAAR/Lintを正常に生成します。
