# 対応ソース

`macrobenchmark/build.gradle.kts`

## 役割

隔離された `:app` の `benchmark` APKを外部から計測する、独立したMacrobenchmarkホストアプリとInstrumentation Testを構成します。AndroidJUnitRunner、UI Automator、benchmark-macro-junit4を使用します。

## 安全条件

ホストのapplication IDは `com.lyco256.llm.macrobenchmark.host`、測定対象は `com.lyco256.llm.test.benchmark` です。本番packageを対象にしません。対象APKのビルド・導入とproduction metadata保護は安全実行スクリプトで行います。
