# `ImageDuplicateMediaGridTest.kt`

duplicate READY resultをasset ID単位でMediaGrid entriesへ変換する経路を検証します。ordered assetの相対順、missing asset省略、同一parent clipの他assetを補充しないこと、parent clip filter、実filtered asset件数、既定sortで連続cellだけを作ること、通常MediaGridの全asset展開が変わらないことを固定します。
