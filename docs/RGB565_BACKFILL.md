# RGB565 pack backfill

## Scope

`temp/media-grid-rgb565-pack-backfill` 専用の一時移行です。通常アプリのUI、起動経路、DBスキーマには入口を追加しません。このブランチは `devenv` へmergeしません。

## Implementation

- `rgb565backfill` は `com.android.test` の専用instrumentation APKです。packageは `com.lyco256.llm.rgb565backfill`、targetは `com.lyco256.llm` です。
- runnerはAndroid標準 `Instrumentation` だけで動き、本番Applicationを起動せず、plain `Application` で対象packageのContextだけを使用します。JUnit/AndroidX Test runtimeには依存しません。
- DBは `OPEN_READONLY` で開き、`assets` の `id` と `localPath` だけを読みます。
- 有効な既存raw slotはCRC検証後にskipします。変換元は永続JPEGを優先し、失敗時はlocal WebPへfallbackします。
- pack間は最大4並列、pack内はasset ID順の直列です。失敗対象だけをdelayなしで2 round再試行します。
- raw packとreport以外は書きません。DB/WAL、local WebP、永続JPEG、SharedPreferencesは処理前後のfingerprint一致を要求します。SQLiteがread-only接続でもreader markを更新し得る一時`-shm`は内容fingerprintの対象外です。
- reportは `filesDir/media_grid_rgb565_packs/v1/backfill/latest-report.txt` に件数、開始・終了時刻、完了状態だけを原子的に保存します。
- pack形式は一時側へ複製せず、インストール済みfeat APKの `MediaGridRgb565PackStore` を呼び出します。

## Safe entry

実機実行は `scripts/run-media-grid-rgb565-backfill.cmd` だけを使います。スクリプトは、許可端末が1台だけであること、本番debug packageのUID・初回install時刻、featの安全install証跡、一時branchがfeat直上の1 commitでoriginと一致すること、専用APKのpackage・target・runner、read-only DB、raw保存先、必要容量をpreflightします。

本番packageのuninstall/clear、DBのcopy/restore、root、`run-as` DB書込みは行いません。追加・削除するpackageは専用instrumentationだけです。
