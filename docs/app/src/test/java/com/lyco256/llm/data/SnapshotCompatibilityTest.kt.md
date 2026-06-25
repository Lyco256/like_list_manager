# `SnapshotCompatibilityTest.kt`

明示指定されたDBと画像バックアップを一時ディレクトリへ複製し、DB integrity/table、画像名・サイズ・SHA-256、コピー元の前後不変を検証します。指定なしの通常unit testではskipします。
