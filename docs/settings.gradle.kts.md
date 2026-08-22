# 対応ソース

`settings.gradle.kts`

## 役割

`:app`、`:macrobenchmark`、オフラインPP-OCRv6実行用の`:ppocr-sdk` moduleを登録し、pluginとdependency repositoryを一元管理します。OpenCVの16KB対応AARは`:ppocr-sdk/libs`からexclusive contentで解決します。
