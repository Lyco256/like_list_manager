# `LocalRelatedTweetsEngineTest.kt`

Fake ANN backendでrelated engineの制御フローを確認します。本文・概要・OCR・画像のchannel最大値集約、reference側available weight分母、threshold、selected clip除外、candidate上限256、全pair scanなし、決定的sortと50件上限、revision cache交換、close、cancel、non-finite scoreを扱います。自然言語的な関連度品質や期待順位はassertしません。
