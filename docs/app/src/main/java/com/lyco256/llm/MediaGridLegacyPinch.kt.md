# `MediaGridLegacyPinch.kt`

Release-path fallback for Phase 1. It delegates only pointer arbitration and release-time fallback to the existing gesture input with no Morph controller or prepared pair. The initial two-pointer distance is fixed, at most one adjacent column-count step is requested, and no Morph overlay or translation is rendered.
