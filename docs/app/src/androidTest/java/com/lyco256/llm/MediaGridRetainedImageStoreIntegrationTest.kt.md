# `MediaGridRetainedImageStoreIntegrationTest.kt`

`MediaGridRetainedImageStore`の実機画像保持とlock-free draw indexを検証します。

- 256×256 `RGB_565` Bitmapを300個、すべて別インスタンス・異なるpixel内容で作成し、既存の48 MiB／memory-cache 75%上限内で約300entryを保持します。301件目では既存のLRUとvisible／active保護に従って未保護の古いentryをevictします。
- asset ID、cache key、source identityの各不一致で古いhandleや別assetのhandleを返さず、同じhandleを1000回lookupしてもdraw index versionとstore lock取得回数を変えません。
- candidate置換、asset invalidation後に古いhandleを取得できないこと、clear後の空index、Coil `MemoryCache.Value`を使う既存restoreを確認します。
- 固定barrier順序の6並列操作でretain、lookup、invalidate、protection、trim、restoreを競合させ、mutable entry、asset補助index、draw snapshotの一致、異asset参照、exception、deadlockを検証します。sleepやproduction UI描画経路は使用しません。
