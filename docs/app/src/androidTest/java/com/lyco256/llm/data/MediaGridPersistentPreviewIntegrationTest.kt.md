# `MediaGridPersistentPreviewIntegrationTest.kt`

WorkManager test records existing unique-work IDs before enqueue and waits only for the newly created request, so retained completed history cannot satisfy the assertion early.

隔離test appの`filesDir`だけを使い、実Bitmapからの256×256中央crop JPEG、JPEG形式、invalid output再生成、stale assetの公開抑止、WorkManager unique workによる生成を検証します。本番DB・画像・設定・認証情報は扱いません。

JUnitの`@Before`/`@After`は明示的な`Unit`ブロックとして定義し、AndroidJUnit4が全テストを列挙・実行できることを維持します。

JPEG中央色はquality 80の圧縮誤差を小さなRGB許容差で検証します。WorkManager testはenqueue直後の状態を決め打ちせず、同じunique workが完了状態になるまで上限付きで待ってから`SUCCEEDED`と生成ファイルを確認します。
