package com.lyco256.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TweetOptionsMenuButton(
    hasOcrAction: Boolean,
    onOcrAction: () -> Unit,
    onSummaryAction: () -> Unit,
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
                contentDescription = "ツイートオプション",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (hasOcrAction) {
                DropdownMenuItem(
                    text = { Text("文字起こし") },
                    onClick = {
                        expanded = false
                        onOcrAction()
                    },
                    modifier = Modifier.testTag("tweet_options_ocr"),
                )
            }
            DropdownMenuItem(
                text = { Text("概要設定") },
                onClick = {
                    expanded = false
                    onSummaryAction()
                },
                modifier = Modifier.testTag("tweet_options_summary"),
            )
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

@Composable
internal fun SummarySettingsDialog(
    summary: String,
    onSummaryChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("summary_dialog"),
        title = { Text("概要設定") },
        text = {
            OutlinedTextField(
                value = summary,
                onValueChange = onSummaryChange,
                modifier = Modifier.fillMaxWidth().testTag("summary_text"),
                label = { Text("概要") },
                minLines = 3,
                maxLines = 8,
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("summary_cancel")) {
                Text("キャンセル")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("summary_save")) {
                Text("保存")
            }
        },
    )
}

@Composable
internal fun OcrSessionDialog(
    clip: ClipWithDetails,
    sessionKey: Long,
    previewAssets: List<OcrImagePage>,
    onDetect: OcrStructuredDetectHandler,
    onSave: OcrSaveResultHandler,
    onDismiss: () -> Unit,
) {
    var renderedState by remember(clip.clip.id, sessionKey) {
        mutableStateOf<OcrSessionState?>(null)
    }
    val controller = remember(clip.clip.id, sessionKey) {
        OcrSessionController(clip) { renderedState = it }
    }
    val state = renderedState ?: controller.state

    DisposableEffect(controller) {
        onDispose { controller.invalidate() }
    }
    LaunchedEffect(controller) {
        controller.startAutomaticDetection(onDetect)
    }

    OcrTextDialog(
        sessionKey = sessionKey,
        previewAssets = previewAssets,
        text = state.draftText,
        structuredResult = state.structuredResult,
        structuredResultGeneration = state.structuredResultGeneration,
        isProcessing = state.isDetecting,
        isSaving = state.isSaving,
        errorMessage = state.errorMessage,
        onTextChange = controller::editDraft,
        onRegionTextChange = controller::editRegion,
        onRedetect = { controller.redetect(onDetect) },
        onConfirm = { controller.save(onSave, onDismiss) },
        onDismiss = {
            if (controller.dismiss()) {
                onDismiss()
            }
        },
    )
}

@Composable
internal fun OcrTextDialog(
    sessionKey: Long = 0L,
    previewAssets: List<OcrImagePage>,
    text: String,
    structuredResult: com.lyco256.llm.data.OcrPostRecognitionResult?,
    structuredResultGeneration: Long = 0L,
    isProcessing: Boolean,
    isSaving: Boolean = false,
    errorMessage: String?,
    onTextChange: (String) -> Unit,
    onRegionTextChange: (OcrRegionKey, String) -> Unit = { _, _ -> },
    onRedetect: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var currentAssetId by remember(sessionKey, previewAssets) {
        mutableStateOf(previewAssets.firstOrNull()?.assetId)
    }
    val pageTransforms = remember(sessionKey, previewAssets) {
        mutableStateMapOf<Long, OcrViewerTransform>()
    }
    var previousStructuredAssetIds by remember(sessionKey, previewAssets) {
        mutableStateOf<List<Long>?>(null)
    }
    var selectedRegionKey by remember(sessionKey, previewAssets) {
        mutableStateOf<OcrRegionKey?>(null)
    }
    val currentPage = previewAssets.firstOrNull { it.assetId == currentAssetId }
    val currentPageIndex = previewAssets.indexOfFirst { it.assetId == currentAssetId }
    val selectedRegion = selectedRegionKey
        ?.takeIf { it.assetId == currentPage?.assetId }
        ?.let { key -> structuredResult?.assetFor(key.assetId)?.recognition?.regions?.getOrNull(key.regionIndex) }

    fun clearRegionSelection() {
        selectedRegionKey = null
        keyboardController?.hide()
    }

    LaunchedEffect(previewAssets.map(OcrImagePage::assetId)) {
        currentAssetId = correctedOcrPageAssetId(
            currentAssetId = currentAssetId,
            previewAssetIds = previewAssets.map(OcrImagePage::assetId),
            previousStructuredAssetIds = previousStructuredAssetIds,
            newStructuredAssetIds = structuredResult?.assets?.map { it.assetId },
        )
    }
    LaunchedEffect(structuredResult) {
        val newStructuredAssetIds = structuredResult?.assets?.map { it.assetId }
        currentAssetId = correctedOcrPageAssetId(
            currentAssetId = currentAssetId,
            previewAssetIds = previewAssets.map(OcrImagePage::assetId),
            previousStructuredAssetIds = previousStructuredAssetIds,
            newStructuredAssetIds = newStructuredAssetIds,
        )
        previousStructuredAssetIds = newStructuredAssetIds
    }
    LaunchedEffect(structuredResultGeneration) {
        clearRegionSelection()
    }
    LaunchedEffect(isProcessing) {
        if (isProcessing) clearRegionSelection()
    }
    LaunchedEffect(selectedRegionKey, selectedRegion) {
        if (selectedRegionKey != null && selectedRegion == null) clearRegionSelection()
    }

    fun requestPage(delta: Int) {
        if (previewAssets.isEmpty()) return
        val index = (currentPageIndex + delta).coerceIn(0, previewAssets.lastIndex)
        currentAssetId = previewAssets[index].assetId
        clearRegionSelection()
    }

    BackHandler { onDismiss() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .systemBarsPadding()
                .imePadding()
                .testTag("ocr_full_screen"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    enabled = !isSaving,
                    modifier = Modifier.testTag("ocr_cancel"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "閉じる")
                }
                Text("文字起こし", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        clearRegionSelection()
                        onRedetect()
                    },
                    enabled = !isProcessing && !isSaving,
                    modifier = Modifier.testTag("ocr_redetect"),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("再検出")
                }
                TextButton(
                    onClick = onConfirm,
                    enabled = !isProcessing && !isSaving,
                    modifier = Modifier.testTag("ocr_confirm"),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("保存")
                }
            }

            if (errorMessage != null) {
                Text(
                    errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .testTag("ocr_image_viewer"),
            ) {
                if (currentPage == null) {
                    Text(
                        "表示できる画像がありません",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    OcrImagePageViewer(
                        page = currentPage,
                        pageIndex = currentPageIndex,
                        pageCount = previewAssets.size,
                        recognition = structuredResult?.assetFor(currentPage.assetId),
                        selectedRegionIndex = selectedRegionKey
                            ?.takeIf { it.assetId == currentPage.assetId }
                            ?.regionIndex,
                        transform = pageTransforms[currentPage.assetId] ?: OcrViewerTransform(),
                        onTransformChanged = { pageTransforms[currentPage.assetId] = it },
                        onPageChange = ::requestPage,
                        onRegionTap = { regionIndex ->
                            if (!isProcessing && !isSaving) {
                                if (regionIndex == null) {
                                    clearRegionSelection()
                                } else {
                                    selectedRegionKey = OcrRegionKey(currentPage.assetId, regionIndex)
                                }
                            }
                        },
                    )
                }
                if (isProcessing) {
                    Row(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("ocr_detecting"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.size(8.dp))
                        Text("文字起こし中", color = Color.White)
                    }
                }
                if (previewAssets.size > 1 && currentPageIndex >= 0) {
                    Text(
                        "${currentPageIndex + 1} / ${previewAssets.size}",
                        modifier = Modifier.align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("ocr_page_indicator"),
                        color = Color.White,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isSaving) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("ocr_saving"),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("保存中")
                    }
                }
                val selectedKey = selectedRegionKey
                if (selectedKey != null && selectedRegion != null) {
                    OutlinedTextField(
                        value = selectedRegion.text,
                        onValueChange = { onRegionTextChange(selectedKey, it) },
                        modifier = Modifier.fillMaxWidth().testTag("ocr_region_text"),
                        enabled = !isProcessing && !isSaving,
                        label = { Text("選択領域") },
                        minLines = 2,
                        maxLines = 6,
                    )
                    TextButton(
                        onClick = ::clearRegionSelection,
                        enabled = !isSaving,
                        modifier = Modifier.testTag("ocr_region_done"),
                    ) {
                        Text("領域編集を終了")
                    }
                } else {
                    if (structuredResult != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().testTag("ocr_result_text"),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("OCR全文", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = text,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 3,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = text,
                            onValueChange = onTextChange,
                            modifier = Modifier.fillMaxWidth().testTag("ocr_result_text"),
                            enabled = !isProcessing && !isSaving,
                            label = { Text("OCR結果") },
                            minLines = 3,
                            maxLines = 8,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrImagePageViewer(
    page: OcrImagePage,
    pageIndex: Int,
    pageCount: Int,
    recognition: OcrAssetRecognitionResult?,
    selectedRegionIndex: Int?,
    transform: OcrViewerTransform,
    onTransformChanged: (OcrViewerTransform) -> Unit,
    onPageChange: (Int) -> Unit,
    onRegionTap: (Int?) -> Unit,
) {
    val maxZoom = 5f
    val sourceWidth = recognition?.recognition?.imageWidth ?: page.imageWidth ?: 1
    val sourceHeight = recognition?.recognition?.imageHeight ?: page.imageHeight ?: 1
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val edgeTolerance = with(LocalDensity.current) { 12.dp.toPx() }
    val latestTransform by rememberUpdatedState(transform)
    val latestRecognition by rememberUpdatedState(recognition)
    val latestOnRegionTap by rememberUpdatedState(onRegionTap)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF171717))
            .pointerInput(page.assetId, pageIndex, pageCount, sourceWidth, sourceHeight) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    var liveTransform = latestTransform
                    val gestureStartScale = latestTransform.scale
                    var gestureDeltaX = 0f
                    var gestureHadZoom = false
                    var gestureHadMultiplePointers = false
                    var movedBeyondTouchSlop = false
                    var lastPosition = firstDown.position
                    val previousPositions = mutableMapOf(firstDown.id to firstDown.position)
                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            val upPosition = event.changes
                                .firstOrNull { it.id == firstDown.id }
                                ?.position
                                ?: lastPosition
                            if (OcrViewerGeometry.isPolygonTapGesture(
                                    gestureHadMultiplePointers = gestureHadMultiplePointers,
                                    gestureHadZoom = gestureHadZoom,
                                    movedBeyondTouchSlop = movedBeyondTouchSlop,
                                )
                            ) {
                                val hit = latestRecognition?.let { assetRecognition ->
                                    OcrViewerGeometry.hitTestRegionIndex(
                                        regions = assetRecognition.recognition.regions,
                                        sourceWidth = sourceWidth,
                                        sourceHeight = sourceHeight,
                                        viewportWidth = size.width.toFloat(),
                                        viewportHeight = size.height.toFloat(),
                                        transform = latestTransform,
                                        point = com.lyco256.llm.data.OcrPoint(upPosition.x, upPosition.y),
                                        edgeTolerance = edgeTolerance,
                                    )
                                }
                                latestOnRegionTap(hit)
                            }
                            finished = true
                            continue
                        }
                        if (pressed.size > 1) {
                            gestureHadMultiplePointers = true
                            gestureHadZoom = true
                        }
                        val previous = pressed.map { change -> previousPositions[change.id] ?: change.position }
                        val currentCentroid = Offset(
                            pressed.map { it.position.x }.average().toFloat(),
                            pressed.map { it.position.y }.average().toFloat(),
                        )
                        val previousCentroid = Offset(
                            previous.map { it.x }.average().toFloat(),
                            previous.map { it.y }.average().toFloat(),
                        )
                        val pan = currentCentroid - previousCentroid
                        val currentDistance = pressed.map { (it.position - currentCentroid).getDistance() }.average().toFloat()
                        val previousDistance = previous.map { (it - previousCentroid).getDistance() }.average().toFloat()
                        val zoom = if (pressed.size > 1 && previousDistance > 0.01f) {
                            (currentDistance / previousDistance).coerceIn(0.5f, 2f)
                        } else {
                            1f
                        }
                        if (pressed.size == 1) {
                            gestureDeltaX += pan.x
                            lastPosition = pressed.first().position
                            if ((lastPosition - firstDown.position).getDistance() > touchSlop) {
                                movedBeyondTouchSlop = true
                            }
                        }
                        val nextScale = (liveTransform.scale * zoom).coerceIn(1f, maxZoom)
                        if (liveTransform.scale > 1.001f || nextScale > 1.001f) {
                            val nextOffset = Offset(
                                x = currentCentroid.x + (liveTransform.offsetX - currentCentroid.x) * (nextScale / liveTransform.scale) + pan.x,
                                y = currentCentroid.y + (liveTransform.offsetY - currentCentroid.y) * (nextScale / liveTransform.scale) + pan.y,
                            )
                            liveTransform = clampOcrViewerTransform(
                                OcrViewerTransform(nextScale, nextOffset.x, nextOffset.y),
                                sourceWidth,
                                sourceHeight,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                            onTransformChanged(liveTransform)
                        }
                        pressed.forEach { previousPositions[it.id] = it.position }
                        previousPositions.keys.retainAll(pressed.map { it.id }.toSet())
                        event.changes.forEach { it.consume() }
                    }
                    OcrViewerGeometry.pageSwipeDirection(
                        pageCount = pageCount,
                        gestureStartScale = gestureStartScale,
                        gestureHadZoom = gestureHadZoom,
                        gestureDeltaX = gestureDeltaX,
                        viewportWidth = size.width.toFloat(),
                    )?.let(onPageChange)
                }
            },
    ) {
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offsetX
                translationY = transform.offsetY
            },
        ) {
            OcrRawBitmapImage(page)
            if (recognition != null) {
                OcrPolygonOverlay(
                    recognition = recognition.recognition,
                    selectedRegionIndex = selectedRegionIndex,
                )
            }
        }
    }
}

@Composable
private fun OcrRawBitmapImage(page: OcrImagePage) {
    var bitmap by remember(page.localPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(page.localPath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(page.localPath) }.getOrNull()
        }
    }
    val imageBitmap = bitmap?.asImageBitmap()
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().testTag("ocr_image_asset_${page.assetId}"),
    ) {
        val image = imageBitmap ?: return@Canvas
        val imageRect = OcrViewerGeometry.fitImageRect(image.width, image.height, size.width, size.height)
            ?: return@Canvas
        drawImage(
            image = image,
            dstOffset = IntOffset(imageRect.left.toInt(), imageRect.top.toInt()),
            dstSize = IntSize(imageRect.width.toInt(), imageRect.height.toInt()),
        )
    }
}

@Composable
private fun OcrPolygonOverlay(
    recognition: OcrRecognitionResult,
    selectedRegionIndex: Int?,
) {
    androidx.compose.foundation.Canvas(
        modifier = Modifier.fillMaxSize().graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
        }.testTag(if (selectedRegionIndex == null) "ocr_polygon_overlay" else "ocr_polygon_overlay_selected"),
    ) {
        val imageRect = OcrViewerGeometry.fitImageRect(
            recognition.imageWidth,
            recognition.imageHeight,
            size.width,
            size.height,
        ) ?: return@Canvas
        val polygons = recognition.regions.mapIndexedNotNull { index, region ->
            region.polygon?.let {
                OcrViewerGeometry.mapPolygonToViewport(
                    it,
                    recognition.imageWidth,
                    recognition.imageHeight,
                    size.width,
                    size.height,
                )?.let { points -> index to points }
            }
        }
        if (polygons.isEmpty()) return@Canvas
        clipRect(imageRect.left, imageRect.top, imageRect.right, imageRect.bottom) {
            if (selectedRegionIndex == null) {
                drawRect(
                    color = Color.Black.copy(alpha = 0.48f),
                    topLeft = Offset(imageRect.left, imageRect.top),
                    size = androidx.compose.ui.geometry.Size(imageRect.width, imageRect.height),
                )
            }
            polygons.forEach { (index, points) ->
                val path = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }
                if (selectedRegionIndex == null) {
                    drawPath(path, color = Color.Transparent, blendMode = BlendMode.Clear)
                    drawPath(path, color = Color.White.copy(alpha = 0.82f), style = Stroke(width = 1.5f))
                } else if (index == selectedRegionIndex) {
                    drawPath(path, color = Color(0xFFFFD54F).copy(alpha = 0.24f))
                    drawPath(path, color = Color(0xFFFFD54F), style = Stroke(width = 3f))
                } else {
                    drawPath(path, color = Color.White.copy(alpha = 0.28f), style = Stroke(width = 1f))
                }
            }
        }
    }
}

private fun clampOcrViewerTransform(
    requested: OcrViewerTransform,
    sourceWidth: Int,
    sourceHeight: Int,
    viewportWidth: Float,
    viewportHeight: Float,
): OcrViewerTransform {
    val scale = requested.scale.coerceIn(1f, 5f)
    val fitRect = OcrViewerGeometry.fitImageRect(sourceWidth, sourceHeight, viewportWidth, viewportHeight)
        ?: return OcrViewerTransform(scale)
    val transformed = OcrViewerGeometry.transformRect(fitRect, viewportWidth, viewportHeight, OcrViewerTransform(scale))
    val minX: Float
    val maxX: Float
    if (transformed.width <= viewportWidth) {
        minX = 0f
        maxX = 0f
    } else {
        minX = viewportWidth - transformed.right
        maxX = -transformed.left
    }
    val minY: Float
    val maxY: Float
    if (transformed.height <= viewportHeight) {
        minY = 0f
        maxY = 0f
    } else {
        minY = viewportHeight - transformed.bottom
        maxY = -transformed.top
    }
    return requested.copy(
        scale = scale,
        offsetX = requested.offsetX.coerceIn(minX, maxX),
        offsetY = requested.offsetY.coerceIn(minY, maxY),
    )
}
