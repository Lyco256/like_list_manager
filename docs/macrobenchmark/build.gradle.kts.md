# 対応ソース

`macrobenchmark/build.gradle.kts`

## 役割

隔離された `:app` の `benchmark` APKを外部から計測する、独立したMacrobenchmarkホストアプリとInstrumentation Testを構成します。AndroidJUnitRunner、UI Automator、benchmark-macro-junit4を使用します。

## 安全条件

ホストのapplication IDは `com.lyco256.llm.macrobenchmark.host`、測定対象は `com.lyco256.llm.test.benchmark` です。本番packageを対象にしません。対象APKのビルド・導入とproduction metadata保護は安全実行スクリプトで行います。

独立したAndroid applicationホスト構成では、AGPがAndroidTestのinstrumentation targetをホスト本体に向けるため、AndroidX Benchmarkの `NOT-SELF-INSTRUMENTING` 検査を明示的に抑制します。測定対象は引き続き `com.lyco256.llm.test.benchmark` で、本番packageは対象にしません。
