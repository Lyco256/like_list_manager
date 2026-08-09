# MediaGridMorphRequiredRenderSet.kt

## 2026-08-10 one-build sharing

Selected planから一回だけ構築し、completeness、render model、Asset保護、readiness reportへ同じinstanceを渡す。required cell planとheader indexも同時に保持し、後段でidentity用HashSetを作り直さない。

## 2026-08-05

`MediaGridMorphRequiredRenderSet` is the one-plan readiness/protection set. It includes only cells and headers whose progress-0-to-1 swept geometry intersects the viewport. The same set supplies required source/target asset IDs, text titles, protected assets, render-model filtering, completeness counts, and optional offscreen counts. It never copies bitmaps or resident image objects.
