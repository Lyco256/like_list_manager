# 19. TopAppBarの灰色CircleProgress表示

## 目的

18のtrackerがactiveで、かつ別のProgressで既に説明されていない時だけ、main title barへ控えめな処理中表示を出す。

## UI仕様

- main `TopAppBar` の設定IconButtonの直左に、小さいindeterminate `CircularProgressIndicator` を表示する。
- 色はgray/onSurfaceVariant系で、設定iconやtitleより目立たせない。
- inactive時は何も表示しない。
- spinnerの有無でtitle barの高さを変えない。
- 17の未分類初回中央Progressが出ている間は、このTopAppBar Progressを重複表示しない。
- 専用の画面全体/Dialog Progressが現在ユーザーへ表示されている処理と重複させない。
- MediaGridのinitial/grid計算Progress、preload/morph等UI処理はそもそも18のtracker対象外なので表示しない。
- network待ちだけでは表示しない。

## 状態接続

- `MainViewModel` から18のactive stateをUIへ公開する。
- UI側で「中央/専用Progressが可視か」を合わせ、最終的な `showHeavyWorkIndicator` を一意に決める。
- 複数active処理がある場合もspinnerは1個だけ。

## テスト

- active時だけ設定button左にspinnerが存在する。
- spinnerがgray/neutralの指定style。
- inactiveで消える。
- 未分類初回中央Progressと同時に存在しない。
- UI系MediaGrid loadingだけでは出ない。
- network waitだけでは出ない。
- trackerが重複activeでもspinnerは1個。
