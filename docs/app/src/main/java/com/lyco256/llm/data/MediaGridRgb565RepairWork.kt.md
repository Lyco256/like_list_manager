# `MediaGridRgb565RepairWork.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/MediaGridRgb565RepairWork.kt`

## 第14実装

save-time publish失敗、invalid/missing slot、表示時raw read失敗を収束させるunique WorkManager repairです。sourceは有効な256×256 JPEGを優先し、なければ現在のlocal WebPをsampled decodeします。DB、元WebP、JPEGへは書き込みません。

WorkManager Dataはasset IDを最大100件に分割し、`requiresStorageNotLow`を設定します。worker内の生成はSemaphoreで最大2件、同一pack writeはpack storeのlockで直列です。固定Delayはなく、一時IO失敗だけをWorkManager backoffで最大2回再実行します。

publish直前にDBのasset/localPathとsource length/lastModifiedを再確認し、stale結果を公開しません。成功時はasset単位のpreview通知を発行し、該当session metadataだけを再準備します。

