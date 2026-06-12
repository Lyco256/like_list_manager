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
- タグ付き投稿を分類リストでタグ絞り込みできる
- 本文、投稿者、概要を検索できる
- 分類後もタグを再割り当てできる
- タグの追加、名称変更、削除、別タグへの一括追加ができる
- 月間取得数と15分rate limitをメニューから確認できる
- 画像は回線を問わず保存し、動画/GIFはWi-Fi時だけpreview thumbnailを保存する
- X側へ書き込みや「いいね」解除を行わない
- Client Secretをアプリへ埋め込まない

## 現時点で達成済み

MVPの上記機能は実装済みです。OAuth callback、token暗号化保存、自動refresh、logout/revoke、liked posts pagination、Room保存、タグ・概要・検索・使用量表示までコードとAPKビルドで確認済みです。

実アカウントを使ったOAuth許可とliked posts取得の最終確認は、実機上でClient IDを入力して行います。

## 次の目標

1. 実機でOAuthログイン、callback、初回同期、token refreshを確認する
2. OAuth/API/JSON変換/Repositoryの自動テストを追加する
3. WorkManagerによる低頻度バックグラウンド同期を追加する
4. タグ色変更、並び替え、完全なタグ統合を追加する
5. backup/export/importを追加する
6. 画像容量表示と手動整理機能を追加する
7. DB schema変更に備えたmigration testを追加する

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
