# `app/src/integrationTest/AndroidManifest.xml`

## 役割

統合テスト用アプリのlabelを分離し、AppAuth callback receiverを削除します。`.test` アプリから本番OAuth callbackを開始・受信できません。cleartext通信は専用network security configを通じたloopbackだけを許可します。package visibilityは同居するメイン `com.lyco256.llm` の存在と別UIDを確認する目的だけに限定します。

## 確認

merged manifestでapplicationIdが `com.lyco256.llm.test` となり、RedirectUriReceiverActivityが含まれないことを確認します。
