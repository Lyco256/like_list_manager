package com.lyco256.llm.data

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class TagColorSpec(
    val id: String,
    val label: String,
    val baseColor: Color,
    val darkerColor: Color,
    val lighterColor: Color,
    val selectedContentColor: Color,
) {
    fun gradient(): Brush = Brush.linearGradient(listOf(lighterColor, baseColor, darkerColor))
}

enum class TagColorId(val id: String) {
    STANDARD("standard"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    CYAN("cyan"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    WHITE("white"),
    BROWN("brown"),
    SKIN("skin"),
    ;

    companion object {
        val orderedValues: List<TagColorId> = entries

        fun fromId(value: String?): TagColorId = entries.firstOrNull { it.id == value } ?: STANDARD
    }
}

private fun color(hex: Long): Color = Color(hex)

private val paletteSpecs = listOf(
    TagColorSpec(
        id = TagColorId.STANDARD.id,
        label = "スタンダード",
        baseColor = color(0xFFB8C0CC),
        darkerColor = color(0xFF9EA8B6),
        lighterColor = color(0xFFD5DAE2),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.RED.id,
        label = "レッド",
        baseColor = color(0xFFFF7A86),
        darkerColor = color(0xFFF45C6B),
        lighterColor = color(0xFFFF9CA6),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.ORANGE.id,
        label = "オレンジ",
        baseColor = color(0xFFFFB15C),
        darkerColor = color(0xFFFF9A3D),
        lighterColor = color(0xFFFFCA8A),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.YELLOW.id,
        label = "イエロー",
        baseColor = color(0xFFFFDB6D),
        darkerColor = color(0xFFFFC93E),
        lighterColor = color(0xFFFFE69A),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.GREEN.id,
        label = "グリーン",
        baseColor = color(0xFF6DE3A0),
        darkerColor = color(0xFF35C97C),
        lighterColor = color(0xFF97EDBC),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.CYAN.id,
        label = "シアン",
        baseColor = color(0xFF6ADAF4),
        darkerColor = color(0xFF38C7EA),
        lighterColor = color(0xFF99E8FA),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.BLUE.id,
        label = "ブルー",
        baseColor = color(0xFF6CA8FF),
        darkerColor = color(0xFF3E84F5),
        lighterColor = color(0xFF95C0FF),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.PURPLE.id,
        label = "パープル",
        baseColor = color(0xFFC58CFF),
        darkerColor = color(0xFF9E5DF2),
        lighterColor = color(0xFFD9B2FF),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.PINK.id,
        label = "ピンク",
        baseColor = color(0xFFFF86C7),
        darkerColor = color(0xFFFF5FAF),
        lighterColor = color(0xFFFFABD9),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.WHITE.id,
        label = "ホワイト",
        baseColor = color(0xFFFFFFFF),
        darkerColor = color(0xFFE5E7EB),
        lighterColor = color(0xFFFFFFFF),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.BROWN.id,
        label = "ブラウン",
        baseColor = color(0xFFC58A5A),
        darkerColor = color(0xFFA86A3A),
        lighterColor = color(0xFFD7A072),
        selectedContentColor = color(0xFF111827),
    ),
    TagColorSpec(
        id = TagColorId.SKIN.id,
        label = "スキン",
        baseColor = color(0xFFF1C39D),
        darkerColor = color(0xFFDFA77E),
        lighterColor = color(0xFFF7D3B7),
        selectedContentColor = color(0xFF111827),
    ),
)

private val specsById = paletteSpecs.associateBy { it.id }
private val legacyAliases = mapOf(
    "gray" to TagColorId.STANDARD.id,
    "lime" to TagColorId.GREEN.id,
    "teal" to TagColorId.CYAN.id,
    "indigo" to TagColorId.BLUE.id,
)

val TagColorPalette: List<TagColorSpec> = paletteSpecs

fun tagColorSpec(colorId: String?): TagColorSpec = specsById[normalizedTagColorId(colorId)] ?: specsById.getValue(TagColorId.STANDARD.id)

fun normalizedTagColorId(colorId: String?): String = legacyAliases[colorId] ?: TagColorId.fromId(colorId).id

fun tagColor(colorId: String?): Color = tagColorSpec(colorId).baseColor

fun tagSelectedContentColor(colorId: String?): Color = tagColorSpec(colorId).selectedContentColor

fun tagGradient(colorId: String?): Brush = tagColorSpec(colorId).gradient()
