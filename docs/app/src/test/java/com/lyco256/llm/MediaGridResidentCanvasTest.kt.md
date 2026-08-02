# `MediaGridResidentCanvasTest.kt`

resident CanvasのContentScale.Crop相当の純粋計算を検証します。正方形、横長・縦長source、wide/tall destination、無効寸法、中央配置、source範囲内を対象にします。
## 2026-08-02 Morph handoff unlock optimization

- Pure tests verify one-shot generation/mode ACK deduplication and retry after a rejected non-blocking send, alongside the existing resident geometry/crop contracts.
