# `OcrSession.kt`

OCR画面の未保存セッションと、遅延した検出／保存callbackの競合防止を担当します。

`OcrSessionController`は開始時の保存済み`ocrText`、現在のdraft、投稿単位の構造化OCR結果、検出中／保存中、エラーを保持します。検出要求ごとのtokenとセッションの有効状態を確認するため、dismiss後、再オープン後、再検出後に古い結果が現在の状態へ反映されません。

保存中は文字編集、再検出、二重保存、dismissを受け付けません。保存callbackが成功した場合だけUIへ閉じる処理を通知し、失敗時はdraftと構造化結果を保持したままエラーを表示します。構造化結果とセッション情報は永続化しません。

`OcrImagePage`はasset IDと表示可能なlocal pathを一体で保持し、`OcrPostRecognitionResult.assetFor()`はページのasset IDに一致する結果だけを返します。セッションを閉じるとページのzoom/panはUI側のremember stateとともに破棄され、再オープン時はFitへ戻ります。

構造化結果があるセッションでは`OcrRegionKey(assetId, regionIndex)`でregionを識別し、`editRegion()`が対象`OcrTextRegion.text`を更新した構造化結果からasset全文・投稿全文・`draftText`を再構成します。region編集はDBへ直接書き込まず、保存時だけ既存のOCR保存経路へ`draftText`を渡します。空regionは構造化結果とpolygonに残しながら全文から除外し、polygonなしregionも全文再構成に含めます。構造化結果がある間の投稿全文編集は受け付けません。
