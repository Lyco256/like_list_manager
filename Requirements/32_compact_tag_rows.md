# 32. タグ管理行を1行の薄型表示へ変更

## 目的

タグ管理画面の1nodeあたりの縦幅を減らし、tag数・group数が多い時の一覧性を上げる。この作業ではTree縦guideはまだ追加しない。

## UI仕様

- 現在の2行目 `タグ` / `グループ` 文字説明を完全に削除する。
- name、件数、expand/icon、操作buttonを1行にまとめる。
- tag/group種別は既存iconと階層で判別し、別の文字ラベルを追加しない。
- vertical paddingを減らし、icon/action寸法をcompact化する。
- touch targetを極端に小さくして誤操作を増やさない。
- 長いnameはellipsis等でaction buttonsを画面外へ押し出さない。
- tag countは名前と同一行で読める。
- group count等、現在表示している意味は不要に変えない。

## Drag & Drop

- compact化後の実測row boundsをdrag placeholder/previewへ反映する。
- drag previewも実rowから大きく外れない高さにする。
- 縦方向追従、drop target、同parent reorder、group内dropの操作性を維持する。

## テスト

- `タグ` / `グループ` の2行目文字がない。
- name/count/actionsが1行。
- 長いnameでも操作buttonが残る。
- row heightが旧2行構成へ戻らない。
- 既存drag/reorder/group drop Compose testがcompact rowでも成立する。
