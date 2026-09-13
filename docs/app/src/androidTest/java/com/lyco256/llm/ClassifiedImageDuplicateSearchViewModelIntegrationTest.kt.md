# `ClassifiedImageDuplicateSearchViewModelIntegrationTest.kt`

fake duplicate engineと注入したimage sync stateで、`SYNCING`待機、`NOT_STARTED`／`FAILED`時の保存済みsnapshot検索、progress、READY、FAILED／retry／clear、通常文字検索解除、cancelされた古い世代のresult無視を検証します。DB・画像・embedding runtimeはfake engineの境界外です。
