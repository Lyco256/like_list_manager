# `LocalImageEmbeddingBitmapDecoder.kt`

`AssetEntity.localPath`のregular fileだけをAndroid標準`BitmapFactory`で読み込みます。bounds decode後に2の累乗`inSampleSize`を決め、原画像の長辺が224より大きい場合はdecode後の長辺が概ね224〜447pxになるように縮小します。`ARGB_8888`、`inScaled=false`を指定し、URL取得や追加decode libraryは使用しません。生成したBitmapの所有権は同期処理側にあり、推論後に必ずrecycleされます。
