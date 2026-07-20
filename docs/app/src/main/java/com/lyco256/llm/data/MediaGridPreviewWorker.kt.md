# `MediaGridPreviewWorker.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWorker.kt`

## 役割

WorkManagerから受け取ったasset IDを1件ずつDBで再取得し、現在の`localPath`だけを入力として`MediaGridPersistentPreviewStore`へ渡します。

- asset削除済み、`localPath`なし、元画像なしはDBへ失敗状態を書かずにスキップする
- 生成完了後の公開直前にassetと現在の`localPath`を再確認する
- 一時的な`IOException`だけを最大3回までretryし、読込不能画像やキャンセルは無限retryしない
- preview生成結果を`AssetEntity.downloadState`、元画像保存、同期結果へ反映しない
- WorkManagerの通常バックグラウンド実行で動き、UIスレッドを使用しない

削除と保存先移動はStoreの共有公開ロックと同じ順序で直列化され、削除済みassetや変更前`localPath`のJPEGが後から復活しないようにします。
