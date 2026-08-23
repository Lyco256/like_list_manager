# `OcrReadingOrder.kt`

OCR成功時に元画像polygonからreading orderとtext groupを一度だけ構築し、region編集後も同じ構造から全文とrangeを再構成する純粋ロジックです。

- polygonの投影矩形、中心、面積、辺長から横書き・縦書き・ambiguousを判定し、1.20倍基準とregion数多数決で画像の優勢方向を決めます。
- 同一行／列と隣接行／列を、alignment、projection overlap、局所文字サイズ、画像10%上限、相互最良候補、介在region vetoで候補化し、方向混在や弱い連鎖groupを抑制します。
- 横書きgroupは行→左から右、縦書きgroupは列→右から左、group間は優勢方向に応じて上段→下段へ並べます。
- polygonなしregionはraw近傍のgroupへ付加し、全文・保存対象から失いません。polygonがない結果だけの場合はraw region順を維持します。
- CJK・句読点は直接連結し、Latin英数字の必要な境界だけ半角スペースを挿入します。group／asset間は空行で区切ります。
- `OcrRegionTextRange`と`OcrPostRegionTextRange`を同じ再構成結果から生成し、自動挿入区切りをrangeへ含めません。

`textLayout`とrangeはセッション中だけ保持し、DBへ保存しません。`OcrPostRecognitionResult.rebuildOcrPostText()`はRepository検出直後にもpost全文rangeを確定します。
