package com.lyco256.llm

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
internal fun TweetOptionsMenuButton(
    hasOcrAction: Boolean,
    onOcrAction: () -> Unit,
    onDeleteAction: () -> Unit,
    buttonTestTag: String = "tweet_options_button",
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(buttonTestTag),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "投稿オプション",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (hasOcrAction) {
                DropdownMenuItem(
                    text = { Text("画像認識") },
                    onClick = {
                        expanded = false
                        onOcrAction()
                    },
                    modifier = Modifier.testTag("tweet_options_ocr"),
                )
            }
            DropdownMenuItem(
                text = { Text("ローカル削除") },
                onClick = {
                    expanded = false
                    onDeleteAction()
                },
                modifier = Modifier.testTag("tweet_options_local_delete"),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OcrTextDialog(
    previewPaths: List<String>,
    text: String,
    isProcessing: Boolean,
    errorMessage: String?,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { previewPaths.size.coerceAtLeast(1) })
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("ocr_dialog"),
        title = { Text("画像認識") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (previewPaths.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        if (previewPaths.size == 1) {
                            AsyncImage(
                                model = previewPaths.first(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(180.dp),
                            )
                        } else {
                            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                                AsyncImage(
                                    model = previewPaths[page],
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxWidth().height(180.dp),
                                )
                            }
                        }
                    }
                    if (previewPaths.size > 1) {
                        Text(
                            "${pagerState.currentPage + 1} / ${previewPaths.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isProcessing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("画像認識中")
                    }
                }
                if (errorMessage != null) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.fillMaxWidth().testTag("ocr_result_text"),
                    enabled = !isProcessing,
                    label = { Text("OCR結果") },
                    minLines = 4,
                    maxLines = 10,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isProcessing,
                modifier = Modifier.testTag("ocr_confirm"),
            ) {
                Text("使用")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("ocr_cancel"),
            ) {
                Text("キャンセル")
            }
        },
    )
}
