ユーザーの変更巻き戻しや、依頼外の大改修やリファクタ禁止
実機・DB・画像・認証情報は本番データ扱い。消す・初期化する・壊し得る操作は明示許可なしにやるな
adb uninstall、adb shell pm clear、本番データを消し得るconnected test、git reset --hardは禁止
検証・実機操作は `SAFE_DEBUG_ROUTINE.md` に書かれた `.cmd` 入口を使う。gradlew、検証用adb、.ps1、スクリプト中の処理を自己判断で直接実行しない。ただしADB接続障害時に限り、データ・アプリ状態を変更しない `adb devices` / `adb mdns services` / `adb connect` / `adb disconnect` / `adb reconnect` / 接続確認用のserial・state取得は、接続確立と復旧のために直接実行してよい。
secret、token、実アカウント情報、Client IDなどの機密値をコミットしない
docsより実ソースと現在のGit差分を正とする。矛盾したら実装を見て判断して
文書、diff、ログ、テスト結果は必要範囲だけ読む。実行中ログ、成功ログは読まない
変更したら関連docsと必要な検証を最小限で合わせ、未確認事項は報告して
