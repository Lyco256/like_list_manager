# `UndoCoordinatorIntegrationTest.kt`

`app/src/androidTest/java/com/lyco256/llm/data/UndoCoordinatorIntegrationTest.kt`

Room実DBで編集とslot保存のtransaction、旧slotの確定と置換、invalidateと内部更新の境界、新規編集失敗時のrollback、不正payload/action mismatchの保持、Undo失敗時のslot保持、古い通知の無害化、同一内容連続編集のslot identity分離を検証します。

