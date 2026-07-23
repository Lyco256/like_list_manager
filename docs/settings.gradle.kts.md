# 対応ソース

`settings.gradle.kts`

## 役割

`:app` と隔離性能測定用の `:macrobenchmark` moduleを登録し、pluginとdependency repositoryを一元管理します。

第15実装の一時branchでは、production target専用の `:rgb565backfill` test moduleも登録します。このmoduleはfeat/devenvへmergeしません。
