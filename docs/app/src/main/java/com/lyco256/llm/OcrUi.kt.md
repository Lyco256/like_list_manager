
# `OcrUi.kt`

UI helpers for the tweet options menu, summary dialog, and full-screen OCR viewer.

## Responsibilities

- Renders the three-dot tweet options menu and exposes OCR, summary, and local-delete actions with test tags, including per-clip button tags supplied by the caller.
- Shows the summary edit dialog with a plain text field and save/cancel buttons.
- Shows OCR in a full-screen modal with a Fit image viewer, asset-ID pages, page indicator, pinch zoom/pan, polygon highlight, separate detection/saving progress states, inline error text, redetect, and save/close buttons.

`OcrSessionDialog` uses `OcrSessionController` for every OCR entry point. Each open creates a new unsaved session initialized from `clip.ocrText`; non-empty saved text skips automatic detection, while empty saved text starts one automatic request. Detection and manual edits update only the session draft. Redetect replaces the draft and structured post result only on success, and a failure keeps both. Save invokes the existing Repository OCR path with a completion callback and dismisses only after success; save failure keeps the full-screen viewer, draft, and previous polygons visible. Dismissal invalidates pending callbacks, and saving disables dismiss, edit, redetect, and duplicate save.

`OcrImagePage` keeps the local image path, asset ID, and optional source dimensions together. Structured results are selected by asset ID, never by list index. Only the current page renders an `AsyncImage`, so opening a multi-image post does not make every original bitmap resident at once.

`OcrPolygonOverlay` computes the Fit image rectangle from the recognition image dimensions and viewport, applies the same transform as the image, clips out-of-bounds polygons, clears only valid polygon paths from the gray sheet, and draws the original four-point shape as a restrained boundary. Pages without a structured result or a valid polygon remain normally lit.
