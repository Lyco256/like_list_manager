# `MediaGridPersistentPreviewIntegrationTest.kt`

隔離test appの`filesDir`だけを使い、実Bitmapからの256×256中央crop JPEG、JPEG形式、invalid output再生成、stale assetの公開抑止、WorkManager unique workによる生成を検証します。本番DB・画像・設定・認証情報は扱いません。
