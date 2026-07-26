# `MediaGridRetainedImageStore.kt`

`MediaGridSessionCoordinator`が所有する、分類済みメディアグリッド全session共有の画像保持storeです。

- `MediaGridRetainedImageKey`はasset ID、Coil memory cache key、source identityで構成し、行・列数・ordinal・viewportを含めません。
- Coilの`MemoryCache.Value`と推定bytesだけを保持し、Bitmapの複製、pixel buffer、画像ファイル、Composable stateは保持しません。
- access-order `LinkedHashMap`で目標300entry、強参照byte上限`min(48 MiB, memory cache最大容量の75%)`を管理します。
- trimは非visibleかつ非active、次に非visible active、最後にvisible以外の順で行い、visible entryを通常trimで破棄しません。
- Coilからevictされたcandidateは同じBitmap handleをCoilへ再挿入してrestoreします。追加request、decode、pixel copyは開始しません。
- controllerごとのowner tokenでvisible／active保護を管理し、pause／resume／disposeではowner保護だけを更新します。
- `ComponentCallbacks2`のmemory trimではvisibleを維持し、DB、元画像、JPEG、RGB_565 packへ変更を加えません。
