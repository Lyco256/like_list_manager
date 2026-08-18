# 17. 未分類画面の初回読込状態

## 目的

Roomから初回データが届く前に「未分類 0件」と誤表示せず、読込中であることを明示する。

## 現状問題

`MainUiState()` の初期値が空listのため、初回repository emission前でも0件として描画できる。DBが本当に0件なのか、まだ読めていないのかを区別できていない。

## 実装

- 投稿一覧について「初回DB-backed emissionを受け取ったか」を明示するload stateを持つ。
- 単に `clips.isEmpty()` をloading判定に使わない。
- 未分類タブで初回未読込中は、
  - titleの `0件` を表示しない。
  - 画面中央に `CircularProgressIndicator` を表示する。
  - empty-state文言を表示しない。
- 初回読込完了後に0件なら、通常の「0件」とempty stateへ切り替える。
- 初回読込後にDB更新で0件になってもloadingへ戻さない。
- 保存先利用不可等、既存の明示的エラー/停止状態をloadingで隠さない。
- 後続のTopAppBar workload indicatorが、この中央Progressと同時に出ないよう「初回loading中」をUI stateから判定できる形にする。

## テスト

- Activity初期frameでDB data未着なら `未分類 0件` が出ず中央Progressが出る。
- 初回DB emissionが空ならProgressが消えて0件表示になる。
- 初回DB emissionが非空なら正しい件数になる。
- 後続更新で0件になってもloading表示へ戻らない。
- 保存先エラーをloadingが隠さない。
