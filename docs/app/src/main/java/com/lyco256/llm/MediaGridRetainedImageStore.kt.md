# `MediaGridRetainedImageStore.kt`

`MediaGridSessionCoordinator`が所有する、分類済みメディアグリッド全session共有の画像保持storeです。

- `MediaGridRetainedImageKey`はasset ID、Coil memory cache key、source identityで構成し、行・列数・ordinal・viewportを含めません。
- Coilの`MemoryCache.Value`と推定bytesだけを保持し、Bitmapの複製、pixel buffer、画像ファイル、Composable stateは保持しません。
- access-order `LinkedHashMap`で目標300entry、強参照byte上限`min(48 MiB, memory cache最大容量の75%)`を管理します。
- trimは非visibleかつ非active、次に非visible active、最後にvisible以外の順で行い、visible entryを通常trimで破棄しません。
- Coilからevictされたcandidateは同じBitmap handleをCoilへ再挿入してrestoreします。追加request、decode、pixel copyは開始しません。
- controllerごとのowner tokenでvisible／active保護を管理し、pause／resume／disposeではowner保護だけを更新します。
- `ComponentCallbacks2`のmemory trimではvisibleを維持し、DB、元画像、JPEG、RGB_565 packへ変更を加えません。
- `MediaGridResidentImageIdentity`は既存keyと同じasset ID、Coil memory cache key、source identityだけで構成し、`MediaGridResidentDrawHandle`は同じ`MemoryCache.Value`と推定bytesを不変参照します。coordinator dispose時は`close()`で空snapshotを公開し、新規retainを受け付けません。
- `AtomicReference<MediaGridResidentDrawIndex>`が最大約300entryのimmutable handle mapとasset ID補助mapを公開します。`lookupDrawHandle()`はsnapshotだけを読み、store lock、Coil restore、request、pack read、decode、pixel copyを行いません。
- retain、candidate置換、eviction、asset invalidation、memory trim、clearはmutable LRU、asset補助index、draw indexを同一lock内で更新してから一度だけsnapshotを公開します。restoreとvisible touchはdraw indexを再構築しません。
- `updateProtection()`はasset ID補助indexからvisible entryをO(1)でaccess-order touchします。セル描画側からstoreの`touch()`を呼び出さず、UI、Placeholder、AsyncImage、frame publication、queue、worker、先読み範囲は変更しません。
