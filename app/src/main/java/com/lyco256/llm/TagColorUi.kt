package com.lyco256.llm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lyco256.llm.data.TagColorPalette

@Composable
fun TagColorPalettePicker(
    selectedColorId: String,
    onColorSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.Text("色", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        val swatchSize = 28.dp
        val rows = TagColorPalette.chunked(6)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEachIndexed { rowIndex, rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("color_palette_row_$rowIndex"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    rowColors.forEach { color ->
                        val selected = color.id == selectedColorId
                        Surface(
                            shape = CircleShape,
                            color = Color.Transparent,
                            border = if (selected) BorderStroke(1.dp, color.darkerColor) else null,
                            modifier = Modifier
                                .size(swatchSize)
                                .testTag("color_palette_${color.id}")
                                .semantics { contentDescription = color.label }
                                .clickable { onColorSelected(color.id) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Circle,
                                    contentDescription = null,
                                    tint = color.baseColor,
                                    modifier = Modifier.size(22.dp),
                                )
                                if (selected) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(color.selectedContentColor.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = color.selectedContentColor,
                                            modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
