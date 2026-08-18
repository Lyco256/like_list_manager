# `PostStorageManager.kt`

## 対応ソース

`app/src/main/java/com/lyco256/llm/data/PostStorageManager.kt`

## 容量表示と待機表示

- 現在地の使用容量と移動見積もりは、画像ディレクトリとDB/WAL/SHMの実ファイルを集計して求める
- 画像枚数は実ファイル数として数え、DBに残っているだけの失敗アセットは含めない
- 見積もり済みの容量は直後の空き容量確認へ再利用し、SDカードからの移動開始時に同じ集計を繰り返さない
- 容量をまだ取得していない起動直後は `usedBytes = null` とし、UIで0 Bと誤表示しない

## 役割

投稿データ用Room DBと画像ディレクトリの保存先を、内部ストレージまたは取り外し可能なSDカードのアプリ専用領域から選択・移動します。OAuth情報と保存先設定自体は内部SharedPreferencesに残します。

## 主な処理

- 利用可能な保存先、使用容量、空き容量、現在地を公開
- 起動時は容量走査を行わず、設定画面を開いた際にIOスレッドで容量を更新
- DBを閉じ、DBと画像を一時ファイルへコピーして件数・容量・SQLite integrityを検証
- 検証成功後だけ保存先を切り替え、旧データを削除
- 移動状態を内部設定へ記録し、アプリ終了後の起動時にコピー中断の破棄または切り替え完了を復旧
- 選択中のSDカードがない場合はDBを新規作成せず、Repository操作を停止
- 内部／SDカードのどちらでDBを開く場合もversion 1→2から8→9までのmigrationを登録する
- `UndoDatabaseProvider` として現在DBのFlowと排他付き `withDatabase` をUndo coordinatorへ提供する

## 変更時の確認

移動前後の投稿、タグ、概要、同期状態、画像パスを確認します。SDカードのアプリ専用領域はアンインストール時に削除されます。任意フォルダ選択には対応していません。
画像数が多い場合、容量集計とSDカードへのコピーには時間がかかります。処理中は専用の待機画面を表示し、メインスレッドを占有しないことも確認します。

preview JPEGは保存先移動の対象に含めず`filesDir`へ維持します。移動中の`localPath`更新とpreview公開・削除は共有公開ロックの順序を守り、移動後の古い元画像からpreviewを公開しません。

## DB migration登録（2026-06-20、履歴）

初期の記録では内部・SDカードの全保存先へ `MIGRATION_1_2`、いいね数列を追加する `MIGRATION_2_3`、同期継続tokenを追加する `MIGRATION_3_4` を登録しました。現行のDB生成処理では、月別API使用量履歴の `MIGRATION_4_5`、タグ色IDの `MIGRATION_5_6`、OCR列の `MIGRATION_6_7`、`isDeleted` 廃止の `MIGRATION_7_8`、永続Undo slot追加の `MIGRATION_8_9` まで登録します。

## テスト分離

`PostStorageConfig` でDB名、画像ディレクトリ、外部保存用ディレクトリ、保存先Preferences名を指定できます。通常値は従来と同一で、統合テストvariantだけ別名を使います。DB生成時はversion 9までの全migrationを登録します。
