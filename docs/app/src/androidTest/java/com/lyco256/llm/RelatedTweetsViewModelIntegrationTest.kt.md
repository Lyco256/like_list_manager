# `RelatedTweetsViewModelIntegrationTest.kt`

fake related engineとfake semantic/image synchronizer stateを注入し、selected clipの詳細Flowをrelated jobから分離します。同期中のWAITING、FAILED時の検索継続、progress、READY、retry、clip切替時のcancelとgeneration保護、Dialog close時のINACTIVEを確認します。
