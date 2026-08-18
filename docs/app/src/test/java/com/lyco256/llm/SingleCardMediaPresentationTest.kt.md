# `SingleCardMediaPresentationTest.kt`

ツイートカードの単一画像について、9:16は表示枠3:4かつcrop、境界の3:4・正方形・横長は元比率かつFitになることを検証します。width/height欠損または不正値では従来の16:10 fallbackを維持することも固定します。
