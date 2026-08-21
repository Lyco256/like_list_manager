
# `OcrUi.kt`

UI helpers for the tweet options menu and the OCR / summary dialogs.

## Responsibilities

- Renders the three-dot tweet options menu and exposes OCR, summary, and local-delete actions with test tags, including per-clip button tags supplied by the caller.
- Shows the summary edit dialog with a plain text field and save/cancel buttons.
- Shows the OCR dialog with image previews, a horizontal pager for multiple images, separate detection/saving progress states, error text, direct redetect, and save/cancel buttons.

`OcrSessionDialog` uses `OcrSessionController` for every OCR entry point. Each open creates a new unsaved session initialized from `clip.ocrText`; non-empty saved text skips automatic detection, while empty saved text starts one automatic request. Detection and manual edits update only the session draft. Redetect replaces the draft and structured post result only on success, and a failure keeps both. Save invokes the existing Repository OCR path with a completion callback and dismisses only after success; save failure keeps the dialog and draft visible. Dismissal invalidates pending callbacks, and saving disables dismiss, edit, redetect, and duplicate save.
