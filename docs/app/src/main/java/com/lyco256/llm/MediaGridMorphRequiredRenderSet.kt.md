# MediaGridMorphRequiredRenderSet.kt

## 2026-08-05

`MediaGridMorphRequiredRenderSet` is the one-plan readiness/protection set. It includes only cells and headers whose progress-0-to-1 swept geometry intersects the viewport. The same set supplies required source/target asset IDs, text titles, protected assets, render-model filtering, completeness counts, and optional offscreen counts. It never copies bitmaps or resident image objects.
