# `ClipTagDraftStateTest.kt`

全ツイートカードで共有するタグdraft状態を検証します。変更なし／変更後／元へ戻した場合と空集合への変更のApply可否、clean draftのRoom追従、dirty draftの保持、Apply失敗後の再試行、成功直後の古いFlow emission抑止、一覧から消えたclipのstate掃除を対象にします。
