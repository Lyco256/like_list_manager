package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TagColorPaletteTest {

    @Test
    fun paletteContainsExpectedTwelveColorsInStableOrder() {
        assertEquals(12, TagColorPalette.size)
        assertEquals(
            listOf(
                "standard",
                "red",
                "orange",
                "yellow",
                "green",
                "cyan",
                "blue",
                "purple",
                "pink",
                "white",
                "brown",
                "skin",
            ),
            TagColorPalette.map { it.id },
        )
    }

    @Test
    fun grayLegacyAliasResolvesToStandard() {
        assertEquals("standard", normalizedTagColorId("gray"))
    }

    @Test
    fun removedLegacyColorsStillMapToModernChoices() {
        assertEquals("green", normalizedTagColorId("lime"))
        assertEquals("cyan", normalizedTagColorId("teal"))
        assertEquals("blue", normalizedTagColorId("indigo"))
    }
}
