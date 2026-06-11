# Real API Verification

This app can be fully verified without committing API secrets. Use this checklist after entering real X API credentials in the app.

## Required App Settings

Open `...` -> `X API設定` and enter all of these values:

- `X User ID`
- `OAuth 1.0a API Key`
- `API Key Secret`
- `Access Token`
- `Access Token Secret`

`OAuth 2.0 Client ID` can be stored for later OAuth 2.0 work, but the current sync implementation uses OAuth 1.0a credentials.

## Expected Results

1. Open `...` -> `同期/使用量`.
2. Confirm `API設定: 登録済み`.
3. Open `...` -> `同期する`.
4. Confirm the sync result dialog reports fetched and inserted counts.
5. Confirm newly fetched liked posts appear in `未分類`.
6. Assign a tag to one fetched post.
7. Confirm the post moves to `分類` and the tag count increases.
8. Search by post text, author, username, or summary in `分類`.

## Failure Signals

- Missing credentials should show the dummy-data message instead of calling X.
- Monthly stop limits should prevent sync before calling X.
- A `401`, `403`, `429`, `5xx`, or network error should appear in the sync result dialog instead of crashing the app.
- Android `logcat` should not contain `FATAL EXCEPTION`, `AndroidRuntime`, or app `ANR` entries for `com.lyco256.llm`.

## Commands Used For Local Verification

```powershell
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
.\gradlew.bat testDebugUnitTest
```
