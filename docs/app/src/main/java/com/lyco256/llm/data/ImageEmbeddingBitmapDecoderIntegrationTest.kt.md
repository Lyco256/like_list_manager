# `ImageEmbeddingBitmapDecoderIntegrationTest.kt`

隔離test fileをAndroid標準`BitmapFactory`でdecodeし、小さい画像の非拡大、大きいlandscape／portraitのbounded dimensions、2の累乗`inSampleSize`、PNG／WebP、破損fileの拒否、入力fileの内容と更新時刻の不変を確認します。
