# `MediaGridPreviewWork.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/MediaGridPreviewWork.kt`

## 役割

WorkManagerへ永続JPEG生成要求を登録する接続層です。

- 固定unique work名`media-grid-persistent-jpeg-preview`を使う
- asset IDを最大100件のData batchへ分割する
- `APPEND_OR_REPLACE`でbatchを直列化し、同時に複数batchを実行しない
- 非expeditedのOneTimeWorkRequestに`requiresStorageNotLow`だけを設定する
- ネットワーク、充電、アイドル条件は設定しない
- 再要求はStore側の有効JPEG判定で不要な再生成を抑止する

テストfixtureやseed用の直接DAO挿入はこのschedulerを経由しないため、本番用生成を意図せず開始しません。
