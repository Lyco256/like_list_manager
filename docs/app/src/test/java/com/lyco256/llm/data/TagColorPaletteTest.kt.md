# `TagColorPaletteTest.kt`

## Overview

Unit tests for the tag color palette constants and legacy color normalization.

## Covered Checks

- The palette order stays stable and contains the expected 12 visible choices.
- `gray` still normalizes to `standard`.
- Older aliases like `lime`, `teal`, and `indigo` still map to the modern colors.
