# `MediaGridRgb565IntegrationTest.kt`

The device suite verifies RGB_565 crop/output, Coil integration, memory caching, pack mapping reuse, and corrupt-slot isolation. The former scheduler-dependent assertion that four fetches must start simultaneously within five seconds was removed because the semaphore defines a maximum, not a scheduling minimum.

`com.lyco256.llm.test`のfilesDirだけを使い、中央crop、`RGB_565` Bitmap、専用Coil Fetcher、memory cache、Fetcher最大4、同一pack mapping再利用、1slot破損の隔離を実機で検証します。本番package・DB・画像へは触れません。

