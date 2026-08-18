# `HeavyLocalWorkTracker.kt`

DB大量更新や画像変換・ファイル操作など、操作可能な画面と競合し得る重いローカル処理だけを参照カウントで追跡します。`isActive` は1件以上の対象処理が実行中の間 `true` となる `StateFlow<Boolean>` です。

`track` は `try/finally` でカウントを戻すため、例外やキャンセルでactiveが残りません。複数処理が重なった場合は、最後の1件が終了するまでactiveを維持します。

HTTP/X APIの応答待ち、MediaGridのpreload・preview・morph準備、Compose処理、単一rowの軽量更新、専用Progressを表示する保存先移動は登録しません。
