# 22. 全ツイートカードのタグdraft/適用方式統一

## 目的

未分類だけでなく分類済み通常カードとMediaGrid previewも、タグを触った瞬間にDBへ書かず、「適用」で確定する同じ操作モデルへ統一する。

## 状態モデル

各表示中clipについて、

- persisted tag IDs
- UI上のdraft tag IDs
- `isDirty = draft != persisted`

を明確に分ける。

### 未分類

- 現在の保留選択方式を維持する。
- draftがpersistedと異なる時だけ適用可能。
- 一度変更して元の集合へ戻したら再び無効。
- 全tagを外す変更も差分なら適用可能。

### 分類済み通常カード

- tag chipを押しても即DB更新しない。
- draftだけ更新する。
- 適用成功後にDB状態へ追従する。
- 適用結果で現在filterから外れたclipは、DB Flow更新後に一覧から自然に消える。

### MediaGrid preview

- 分類済み通常カードと同じdraft方式。
- 適用結果で現在filter条件から外れてもpreview dialogは自動closeしない。
- dialogを閉じるまで対象clipを表示する。
- dialogを閉じて未適用draftを捨てた場合、DBへ反映しない。

## 同期と失敗

- persisted値のRoom更新が来た時、dirtyでないdraftは新persistedへ追従する。
- dirty draftを無関係なrecompositionやlikeCount更新で上書きしない。
- DB更新成功を確認してからdirtyを解消する。
- 更新失敗時はdraftを残し再試行可能にする。
- 一覧から消えたclipの不要draft stateを掃除し、state mapを増やし続けない。

## Undoとの関係

適用成功時だけ05の単一ツイートtag Undoを作る。draft操作だけではUndo slotを作らない。

## テスト

- 未分類: 変更前disabled、変更後enabled、元へ戻すとdisabled。
- 分類済み: chip tapだけではDB不変、Applyで反映。
- MediaGrid previewも同じ。
- Apply失敗でdraft保持。
- 分類済みfilter対象外になるApply後、一覧からは消えるがpreviewは開いたまま。
- 未適用でdialogを閉じるとDB不変。
- likeCount更新等でdirty draftが失われない。
