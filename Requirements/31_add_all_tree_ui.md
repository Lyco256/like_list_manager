# 31. 「別タグへ一括追加」のTree選択UI

## 目的

tagが大量・深階層になっても、「別タグへ一括追加」の追加先を必ず選べるようにする。操作自体は従来どおり1回につき1tagだけ。

## UI仕様

- 現在のflat `targets.forEach` を廃止する。
- 27で作ったTree表示部を再利用し、縦スクロール可能な展開式Treeで全tagを表示する。
- groupは階層を展開/折りたたむための構造要素で、追加先としては選択しない。
- source tag自身は追加先として選択不可。表示から除外するかdisabledで明確にする。
- 追加先tagを1件押した時点で既存どおり一括追加を実行し、dialogを閉じる。
- 複数tagを同時選択するUIにはしない。
- 深い階層・大量tagでもscrollで末尾まで到達できる。
- 同名tagが別groupにあってもTreeの位置から区別できる。

## 既存動作の維持

- source tagは残す。
- 一括追加のDB semanticsとUndoは11の実装を使う。
- dialog cancelでは何も変更しない。

## テスト

- root/深階層tagをそれぞれ追加先に選べる。
- groupを押しても追加処理は走らずexpandする。
- source tagを選べない。
- 大量tag fixtureでscroll末尾のtagを選べる。
- 選択後は1回だけadd-allが呼ばれdialogが閉じる。
- CancelではDB不変。
