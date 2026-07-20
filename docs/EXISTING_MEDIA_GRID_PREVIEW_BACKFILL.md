# 既存画像プレビュー生成の一時変換手順

対象ブランチ: `temp/media-grid-existing-preview-backfill`

このブランチは、実装8より前に保存されたlocal assetの`filesDir/media_grid_previews/v1/<assetId>.jpg`を生成する一時変換専用です。`devenv`へマージしません。実装8の256×256中央crop・JPEG quality 80・atomic replace・WorkManager worker/storeは変更せず、DB schema、元画像、設定、認証情報も変更しません。

## 変換開始

1. 本番packageへ一括変換を開始しない。Codexの実機検証は隔離packageだけで行う。
2. debugかつ非TEST_HARNESSの本番debug画面で、設定 → 「既存画像プレビュー生成」を開く。
3. 「再確認」でlocal asset総数、有効JPEG数、生成対象数を確認する。
4. 「開始」を押し、確認Dialogの件数を確認してから開始を確定する。
5. 状態表示で専用tagのqueued/running/succeeded/failed/cancelledと容量制約待ちを確認する。

## 停止・再開

- 「停止」は`media-grid-existing-preview-backfill` tagの未完了workだけをcancelする。通常同期のpreview workは停止しない。
- 「再開」は必ず再走査して、missing・stale・invalidだけを再enqueueする。途中位置やDB進捗を使わない。
- 「再確認」はenqueueせず、現在のファイル状態だけを更新する。
- 全tagged work完了後、再走査で生成対象0件になった場合だけ「変換完了」と判断する。

## 検証

変更後は次の順だけで実行する。

```powershell
.\scripts\run-safe-integration-check.cmd
.\scripts\run-safe-debug-check.cmd -InstallToDevice
```

安全スクリプトは隔離Integration APKを`com.lyco256.llm.test`として扱い、本番アプリのDB・元画像・設定・認証情報へ触れない。Macrobenchmarkは実行しない。Codexは本番アプリで変換開始を押さない。

## 完了後の通常版復帰

ユーザーが本番debug画面で変換完了を確認するまで、この一時ブランチを保持する。確認後、最新`devenv`へ移動し、次を実行して同じ本番packageへ安全に上書きする。

```powershell
git switch devenv
git pull --ff-only
.\scripts\run-safe-debug-check.cmd -InstallToDevice
```

通常版には一時画面・専用enqueue処理がなく、`filesDir/media_grid_previews/v1/`の生成済みJPEGは維持される。ユーザーの完了確認後にだけ一時ブランチを削除する。
