# `MediaGridPersistentPreviewStore.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/MediaGridPersistentPreviewStore.kt`

## 役割

新規local asset用の永続表示JPEGだけを生成・公開・削除する専用Storeです。出力先は`filesDir/media_grid_previews/v1/<assetId>.jpg`に固定され、DB列、元画像、既存Coil cache、保存先移動中の投稿データは扱いません。

## 生成仕様

- bounds decodeで寸法を先に取得し、256pxの中央正方形cropに必要な`inSampleSize`でdecodeする
- `Bitmap`の中央cropと縮小を1回のCanvas描画で行い、黒背景の不透明JPEG quality 80へ書き出す
- 読込、decode、描画、圧縮、書込は`Dispatchers.IO`で行い、処理中は元Bitmapと出力Bitmapをfinallyでrecycleする
- 一時ファイルは同じ`v1`ディレクトリへ作成し、flush/sync後に同一ディレクトリ内でreplaceする
- 生成失敗、キャンセル、asset削除・`localPath`変更時は一時ファイルだけを破棄し、既存の有効JPEGを維持する
- 有効な256×256 JPEGが元画像より新しい場合は再生成しない
- ファイル名はasset IDだけから決定し、保存先外を削除・上書きできないことを検査する

## 変更時の確認

crop、sample size、JPEG形式・寸法・品質、再生成判定、原子的置換、キャンセル時の既存ファイル保護をUnit/AndroidTestで確認します。グリッドの画像候補順、Coil設定、既存cache、Macrobenchmarkは変更しません。
