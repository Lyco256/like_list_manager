# `MediaGridPersistentPreviewStoreTest.kt`

`MediaGridPersistentPreviewStore`のJVM単体テストです。sample size、中央crop矩形、asset IDからのversioned output pathを検証します。Bitmapの実decode・JPEG・原子的置換はAndroid instrumentation testで確認します。
