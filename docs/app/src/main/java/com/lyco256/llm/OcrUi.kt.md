
# `OcrUi.kt`

UI helpers for the tweet options menu, summary dialog, and full-screen OCR viewer.

## Responsibilities

- Renders the three-dot tweet options menu and exposes OCR, summary, and local-delete actions with test tags, including per-clip button tags supplied by the caller.
- Shows the summary edit dialog with a plain text field and save/cancel buttons.
- Shows OCR in a full-screen modal with a Fit image viewer, asset-ID pages, page indicator, pinch zoom/pan, polygon highlight and selection, a single selected-region editor, separate detection/saving progress states, inline error text, redetect, and save/close buttons.
- Shows a compact two-choice `認識モード` segmented control with `高速` and `高精度`; it exposes no internal model names, keeps the selection only in the current session, and disables both choices during detection or saving.

`OcrSessionDialog` uses `OcrSessionController` for every OCR entry point. Each open creates a new unsaved session initialized from `clip.ocrText`; non-empty saved text skips automatic detection, while empty saved text starts one automatic request. Detection and manual edits update only the session draft. Redetect replaces the draft and structured post result only on success, and a failure keeps both. Save invokes the existing Repository OCR path with a completion callback and dismisses only after success; save failure keeps the full-screen viewer, draft, and previous polygons visible. Dismissal invalidates pending callbacks, and saving disables dismiss, edit, redetect, and duplicate save.

`OcrImagePage` keeps the local image path, asset ID, and optional source dimensions together. Structured results are selected by asset ID, never by list index. Only the current page decodes and draws a raw `BitmapFactory` bitmap, so OCR and display use the same pixel orientation without a separate EXIF auto-rotation path, and opening a multi-image post does not decode every original bitmap at once. The bitmap is held only by the current page composable and is released with the composable state.

The viewer carries a session key into its page and transform state, so a newly opened OCR session starts at the first page and Fit scale. When a subsequent structured result removes the currently displayed asset that existed in the previous result, the page is corrected to the first still-valid preview asset; an initial partial result does not force the user away from the current preview page.

`OcrPolygonOverlay` computes the Fit image rectangle from the recognition image dimensions and viewport, applies the same transform as the image, clips out-of-bounds polygons, clears only valid polygon paths from the gray sheet, and draws the original four-point shape as a restrained boundary. Pages without a structured result or a valid polygon remain normally lit.

When a valid polygon is tapped without a competing pan, pinch, or page swipe, the viewer selects its `assetId` plus region index. Containing polygons are prioritized by smallest displayed area; otherwise a displayed polygon within 12dp is selected by nearest edge distance. Selection removes the gray sheet and emphasizes only the selected polygon without changing zoom/pan. The lower panel switches to one field containing the region's current text; ending selection returns to the read-only reconstructed post text. Page changes, outside taps, redetection, and structured-result generation changes clear the selection.

The structured `OCR全文` is rendered from the post-level region ranges. A tap resolves the text offset through `regionKeyAtTextOffset()` and uses the same `selectedRegionKey` path as a polygon tap. It changes to another asset page when necessary, highlights the valid polygon, opens the existing single-region field, and requests the viewer to reveal the polygon with the current transform preserved where possible. Separator text, polygonless regions, and invalid polygons do not start selection.
