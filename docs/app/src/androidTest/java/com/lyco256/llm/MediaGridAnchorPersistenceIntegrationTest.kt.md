# `MediaGridAnchorPersistenceIntegrationTest.kt`

隔離integration-test variantで、session keyを明示したanchor保存を検証する。

- session Aのanchorを保存した後にsession Bをactiveにしても、AのanchorがBへ混入しない。
- inactiveなAへkey指定で保存でき、Aへ戻った時にそのanchorが復元対象になる。
- 同一anchorの再保存は`MediaGridSessionUiState`をpublishしない。
- Bのanchorを保存してもAの保存値を変更しない。
