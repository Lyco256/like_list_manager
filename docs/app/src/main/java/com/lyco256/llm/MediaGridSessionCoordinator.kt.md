# `MediaGridSessionCoordinator.kt`

`MainViewModel`が所有する、分類済みメディアグリッドの長寿命session管理です。

- session keyは有効なfilter/sortだけで構成し、列数・source revision・hierarchy revisionを含めません。
- sessionは現在と直前の最大2件をLRU保持し、明示条件変更時だけ新sessionを開始します。
- sessionはframe、確定列数、変更要求列数、controller、controller state、anchor、visible状態、refresh generationを保持します。
- 列数変更は既存frameを表示したまま新frameを構築し、controllerのframe更新と列数を公開更新します。
- source更新は保持frameを表示したままbackground refreshし、変更assetだけを再準備します。
- 画面離脱ではcontrollerをpauseし、`MainViewModel.onCleared()`またはLRU除外時だけdisposeします。
- controllerへActivityやBitmap/Drawable/Imageを渡したり、sessionへ画像本体を保持したりしません。
