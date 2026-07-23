# like list manager Goals

## プロダクトの目的

自分のXアカウントで「いいね」した投稿を端末へ取り込み、あとからタグと概要で整理・検索できる個人用Androidアプリを作る。

X側の「いいね」を変更するクライアントではなく、取得した投稿を自分用に保存・分類するローカル管理ツールとする。

## MVPの目標

- OAuth 2.0 + PKCEで自分のXアカウントへ安全にログインできる
- X APIから自分のliked postsを取得できる
- 投稿本文、投稿者、画像をXに近い見た目で表示できる
- 新規取得投稿を未分類リストへ入れられる
- 投稿へ複数タグと概要を設定できる
- タグをグループ階層で整理し、タグまたはグループを条件に分類リストを絞り込みできる
- 本文、投稿者、概要を検索できる
- 分類後もタグを再割り当てできる
- タグとグループの追加、名称変更、移動、並び替え、削除ができる
- 月間取得数と15分rate limitをメニューから確認できる
- 画像は回線を問わずWebP形式で保存し、動画/GIFはWi-Fi時だけpreview thumbnailを保存する
- X側へ書き込みや「いいね」解除を行わない
- Client Secretをアプリへ埋め込まない
- 投稿DBと保存画像の保存先を内部ストレージまたはSDカードから選択できる

## 現時点で達成済み

MVPの上記機能は実装済みです。OAuth callback、token暗号化保存、自動refresh、logout/revoke、liked posts pagination、Room保存、階層タグ・概要・複合絞り込み・検索・使用量表示、投稿データ保存先変更までコードで実装済みです。

実アカウントを使ったOAuth許可とliked posts取得の最終確認は、実機上でClient IDを入力して行います。

## 次の目標

1. 実機でOAuthログイン、callback、初回同期、token refreshを確認する
2. OAuth/API/JSON変換/Repositoryの自動テストを追加する
3. WorkManagerによる低頻度バックグラウンド同期を追加する（同期そのものは未実装のため継続）
4. backup/export/importを追加する
5. 保存先別の容量表示に加えて、画像の手動整理機能を追加する
6. DB migration testを実機で継続実行できる検証手順へ組み込む

## 非目標

- 複数ユーザー向けSaaS
- Xへの投稿、いいね、いいね解除
- 他人のいいね一覧収集
- 動画/GIF本体の恒久保存
- Client SecretやConsumer SecretのAPK埋め込み

## 判断基準

- 個人利用で操作が簡単であること
- APIコストとrate limitをユーザーが把握できること
- 端末内データと認証情報を安全に扱うこと
- X API仕様変更時に影響箇所を文書から辿れること
- 低スペックPCと実機でも開発・検証を継続できること

## 2026-06-20 達成済み

- X API取得時点のいいね数保存・表示と、暫定値警告を追加
- 料金確認と月間残り枠を伴う明示的ないいね数再取得を追加
- 投稿者の件数順、タグ投稿数、グループ直下要素数の表示を追加
- liked posts同期が途中終了した場合のnext token保存と次回再開を追加

## 2026-06-22 達成済み

- 本番packageを維持したまま、`com.lyco256.llm.test` の隔離統合テストvariantを追加
- 本番OAuth、token store、X APIをテスト用アプリから利用できないfail-closed構成を追加
- 再同期時の手動概要・タグ保持、重複排除、pagination、401 refresh、429、月間停止のRepository統合テストを追加
- WebP保存、タグ/グループ削除時の投稿保護、MockWebServer異常系、主要Compose画面テストを追加
- DB・実画像バックアップを一時コピーだけで検証する任意snapshotテストを追加

## 2026-06-23 達成済み

- SC-56Cへ本番 `com.lyco256.llm` と隔離テスト `com.lyco256.llm.test` を同時に導入し、異なるUIDで共存することを確認
- 本番packageのpath、UID、version、初回導入日時、更新日時が実機テスト前後で不変であることを安全スクリプトで確認
- AndroidJUnitRunnerによる実機統合テスト（Compose UI、環境分離、Room migration、Repository、MockWebServer）を安全スクリプト経由で継続実行できる状態にした
- SC-56Cで正常終了をクラッシュ扱いするOrchestratorは使わず、各UIテスト前に隔離DBだけを初期化する構成へ変更

## 2026-07-01 達成済み

- メインと隔離テストpackageを共存させた実機で、安全スクリプト経由のAndroidJUnitRunner結果を継続確認できる状態に更新
- 大量データ、Macrobenchmark、property-based testing、検索/分類/タグ管理/設定/エラー復旧/軽微UI状態の主要自動検査を追加
- メインメニュー、結果Dialog、いいね数再取得見積もり、ローカル削除Dialog、スクロール後の検索条件維持、スクロール後の未確定タグ選択維持をtestTagとDB assertで固定
- SC-56CではOrchestratorが正常終了をクラッシュと誤判定するため採用しない方針を維持

実画像backupの提供がないため、実画像backupによるsnapshot最終確認だけは未完了として残します。

## 2026-07-11 達成済み

- 分類済みメディアグリッドの2〜12列、投稿日／いいね数見出し、ピンチ列数変更、投稿単位の複数選択、一括タグ編集、選択中のカードDialog導線を実装
- 複数選択を0件まで維持し、×／戻るで終了、0件時のタグ編集無効化、セルタップとDialogボタンのイベント分離を実装
- 選択表示をチェックボックスだけに限定し、単一画像の水色＋黒チェック、複数画像の青色＋白チェック、選択開始時だけのハプティックを実装
- タグ／グループの色パレットと色設定を実装
- wireless ADBのmDNS endpoint自動解決を安全な統合テストスクリプトへ追加

## 2026-07-20 達成済み

- 新規local asset向け256×256中央crop JPEG previewを`filesDir/media_grid_previews/v1/<assetId>.jpg`へ非同期生成するWorkManager経路を追加
- DB schema、元画像、既存cache、グリッド表示経路を変更せず、削除・localPath変更競合と原子的置換を検証
- wireless隔離統合テストと本番安全上書き検証をSuccessで完了。Macrobenchmarkは対象外として未実行

## 2026-07-22 steady-load controller 達成済み

- 初期Progress中に表示位置周辺のmetadataと永続JPEG memory warm-upを行い、全terminalまたは3秒でグリッドを公開する経路を追加
- viewport通知をlatest anchor上書きに限定し、50ms周期・固定予算・同時2requestの単一controllerへ画像処理を集約
- active bitmap windowを表示中＋前後1行に限定し、範囲外requestとUI load stateを破棄しつつframe内metadataとCoil LRUを再利用
- wireless隔離統合テストと本番安全上書き検証をSuccessで完了。Macrobenchmarkは要件指定により未実行

## 2026-07-23 第14 RGB_565 pack 達成済み

- 256×256 `RGB_565`を128asset固定slot、二重bank＋generationのpackへ保存する通常経路を追加
- source Bitmapからraw payloadを作り、WebP・Asset・既存JPEG生成を維持
- Decoderを通さないCoil Fetcherとraw→JPEG→local→URL fallbackを追加
- wireless隔離統合テストと本番安全上書き検証をSuccessで完了。Macrobenchmarkは要件指定により未実行
