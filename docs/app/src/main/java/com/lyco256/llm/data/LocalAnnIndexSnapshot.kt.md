# `app/src/main/java/com/lyco256/llm/data/LocalAnnIndexSnapshot.kt`

## 役割

USearch v2.26.0を使った、完全ローカルな不変HNSW ANN snapshotのwrapperです。現在のEmbedding同期、`DerivedSearchStorage`、検索UI、画像重複検索には接続しません。

## 入力と検索

- 許可するdimensionは256と768だけです。
- snapshot構築前にdimension、finite値、正の有限L2 norm、duplicate keyを全件検証します。
- 入力vectorを変更せず、内部copyをFloat32のままL2 normalizeしてUSearchとexact rerankの両方で使います。
- USearchはcosine / Float32 / connectivity 32 / expansion add 256 / expansion search 256で構築します。
- USearchの候補keyだけを正規化済み元vectorとのFloat32 dot productで再計算し、score降順・同値時key昇順で返します。
- score閾値、検索用途別重み、query自身の自動除外、save/load/viewによるindex永続化は持ちません。

## Lifecycleとnative境界

`LocalAnnIndexSnapshot`はbuild完了まで外部へ公開されません。buildはDefault dispatcher上で実行し、cancellation・例外時はpartial backendをcloseします。完成後のsearchとcloseはwrapper内でserializeし、closeは冪等、close後searchは`IllegalStateException`です。

対応ABIは`arm64-v8a`と`armeabi-v7a`だけです。native Indexを作る前に端末ABIを検査し、その他のABIでは明示的な`IllegalStateException`として拒否します。

Java bindingはUSearch v2.26.0 tagの`Index.java` / `NativeUtils.java`をvendorしています。公式Android releaseの`libusearch_c.so`はC ABIのため、アプリ側の小さなJNI load adapterが`libusearch.so`としてbindingから呼び出し、両者を同じABIへpackageします。

native archiveはGradle build時だけGitHub Releasesから取得し、固定SHA-256検証後にgenerated JNI libsへ展開します。application runtimeでnetwork取得は行いません。

## 関連テスト

- `app/src/test/java/com/lyco256/llm/data/LocalAnnIndexSnapshotTest.kt`: validation、copy/normalize、exact rerank、candidate clamp、unknown key、close、build failure、cancellation cleanup。
- `app/src/androidTest/java/com/lyco256/llm/data/LocalAnnIndexSnapshotIntegrationTest.kt`: 実USearch JNIによる256/768 snapshot、反復search、並行search、空snapshot、close、lifecycle。
- `app/build.gradle.kts`: release archiveのSHA検証、ABI別native準備、JNI adapter build、生成物検査。
