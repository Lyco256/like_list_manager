# MediaGridMetadata.kt

メディアグリッド専用のメタデータ処理を担当する。Repositoryから受け取った軽量Asset行・active Clip・active ClipTagのスナップショットを、`Dispatchers.Default`でlatest-wins処理する。

完成結果は最大3件のLRUキャッシュへ保持し、Bitmapやファイル内容は保持しない。メタデータ準備中は`Calculating`、完了後は`Ready`を発行する。枠は静的グラデーションを先に描画し、次フレーム以降に既存URLの画像要求を開始する。メタデータ処理中にFile I/Oや画像デコードは行わない。

Paging、低解像度サムネイル、画像処理キュー、列数アニメーションは後続実装の範囲とする。
