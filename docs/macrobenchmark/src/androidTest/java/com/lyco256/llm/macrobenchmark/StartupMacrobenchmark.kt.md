# 対応ソース

`macrobenchmark/src/androidTest/java/com/lyco256/llm/macrobenchmark/StartupMacrobenchmark.kt`

## 役割

隔離benchmarkアプリのcold startとwarm startを各5回測定し、標準Macrobenchmark reportへStartupTimingMetricを出力します。

## 変更時の確認

本番packageを測定対象にしてはいけません。許可serialとproduction metadata保護を行う安全スクリプトから実行します。
