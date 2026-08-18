# 18. 重いローカル処理の共通tracker

## 目的

画面操作中のカクつき原因になり得るDB・ファイル等の重いローカル処理区間だけを、data層で正確に追跡できるようにする。この作業ではTopAppBarへまだ表示しない。

## 対象

trackerでactiveにするのは、実際にローカル負荷を生む可能性がある区間。

含める例:

- 同期取得後のまとまったDB保存
- 同期メディアのdecode/圧縮/ファイル保存
- 多数clipへの一括tag DB更新
- 「別タグへ一括追加」の大量relation更新
- tweet削除/Undoの画像staging・復元

含めない:

- HTTP/X APIの応答待ちだけの時間
- MediaGrid preload、morph準備、preview描画等のUI表示系background処理
- 単純なCompose処理
- 単一rowの軽いDB更新まで何でも追跡して短時間点滅させること

## 実装

- Repository/data層に共通trackerを追加する。
- active件数をreference countで持ち、`StateFlow<Boolean>` または同等で公開する。
- 各対象処理はローカル負荷区間だけを `try/finally` で囲み、例外でもcountを必ず戻す。
- 複数処理が重なった時、1つ終了しても残りがactiveならtrueを維持する。
- network callとlocal persistを同じ巨大blockで囲まず、待機時間はinactiveにする。
- UI系MediaGrid coordinatorの「読み込み中」とこのtrackerを共有しない。
- operation名やtokenを持たせる場合でも、UIへは最終的に「重いlocal workが1件以上あるか」を提供できればよい。

## テスト

- 1件開始/終了。
- 2件重複時に1件終了してもactive維持。
- 例外時にactive解除。
- network wait模擬中はinactive、persist区間でactive。
- UI表示系処理はtrackerへ登録されない。
- 一括tag等、指定した大量更新経路でtrackerが有効になる。
