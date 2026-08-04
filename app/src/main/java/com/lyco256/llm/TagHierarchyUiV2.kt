package com.lyco256.llm

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Input
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.MediaGridClipSource
import com.lyco256.llm.data.MediaGridPreviewNotifier
import com.lyco256.llm.data.MediaGridPreviewRecoveryGate
import com.lyco256.llm.data.buildMediaGridImageRequest
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagGroupNode
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagLeafNode
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagTreeNode
import com.lyco256.llm.data.tagColor
import com.lyco256.llm.data.tagColorSpec
import com.lyco256.llm.data.tagGradient
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val ClassifiedMediaGridMinColumnCount = 2
internal const val ClassifiedMediaGridMaxColumnCount = 12
internal const val ClassifiedMediaGridDefaultColumnCount = 4
private const val ClassifiedMediaGridPinchScaleStep = 1.12f
private const val ClassifiedMediaGridToolbarZIndex = 1f

internal data class VisibleTagRow(
    val node: TagTreeNode,
    val depth: Int,
    val parentGroupId: Long?,
    val indexInParent: Int,
)

internal sealed interface TagListItem {
    val key: String

    data class Row(
        val row: VisibleTagRow,
        val indexInParentWithoutDragged: Int,
    ) : TagListItem {
        override val key: String = row.node.ref().saveableKey()
    }

    data class Placeholder(
        val parentGroupId: Long?,
        val index: Int,
        val depth: Int,
        val heightPx: Float,
    ) : TagListItem {
        override val key: String = "drag-placeholder:${parentGroupId ?: "root"}:$index"
    }
}

internal data class DragState(
    val node: TagNodeRef,
    val label: String,
    val isGroup: Boolean,
    val sourceParentId: Long?,
    val sourceIndexInParent: Int,
    val targetParentId: Long?,
    val placeholderIndex: Int,
    val visualParentId: Long? = targetParentId,
    val visualPlaceholderIndex: Int = placeholderIndex,
    val pointerYInRoot: Float,
    val grabOffsetY: Float,
    val itemLeftX: Float,
    val itemWidth: Float,
    val itemHeight: Float,
    val lastDragCenterY: Float,
    val targetIsGroupDrop: Boolean = false,
    val reorderLocked: Boolean = false,
)

private data class ScrollAnchor(
    val stableId: Any?,
    val index: Int,
    val offset: Int,
)

@Composable
fun EnhancedClipListScreen(
    title: String,
    clips: List<ClipWithDetails>,
    hierarchy: TagHierarchy,
    emptyText: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    requireTagConfirmation: Boolean = false,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onAuthorClick: (ClipEntity) -> Unit = {},
) {
    val pendingTagIds = remember { mutableStateMapOf<Long, Set<Long>>() }
    val itemKeys = remember(clips) { clips.map { it.clip.id } }
    PreserveScrollAnchor(listState, title, itemKeys)
    Column(modifier.fillMaxSize().padding(12.dp)) {
        if (clips.isEmpty()) {
            HierarchyEmptyState(emptyText)
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().testTag("clip_list"),
                ) {
                    items(clips, key = { it.clip.id }) { clip ->
                        val selectedTagIds = if (requireTagConfirmation) {
                            pendingTagIds[clip.clip.id] ?: clip.tags.map { it.id }.toSet()
                        } else {
                            clip.tags.map { it.id }.toSet()
                        }
                        EnhancedTweetCard(
                            clip = clip,
                            hierarchy = hierarchy,
                            selectedTagIds = selectedTagIds,
                            requireTagConfirmation = requireTagConfirmation,
                            onTagSelectionChange = { tagIds ->
                                if (requireTagConfirmation) {
                                    pendingTagIds[clip.clip.id] = tagIds
                                } else {
                                    onTagsChange(clip.clip, tagIds)
                                }
                            },
                            onTagConfirmation = {
                                onTagsChange(clip.clip, selectedTagIds)
                                pendingTagIds.remove(clip.clip.id)
                            },
                            onSummaryChange = onSummaryChange,
                            onOcrSave = onOcrSave,
                            onOcrDetect = onOcrDetect,
                            onDelete = onDelete,
                            onAuthorClick = onAuthorClick,
                        )
                    }
                }
                LazyListScrollbar(listState)
                ScrollToTopButton(listState, hasItems = clips.isNotEmpty())
            }
        }
    }
}

@Composable
internal fun EnhancedClassifiedScreen(
    uiState: MainUiState,
    mediaGridState: ClassifiedMediaGridState,
    listState: LazyListState,
    displayMode: ClassifiedDisplayMode,
    mediaGridColumnCount: Int,
    onMediaGridColumnCountChange: (Int) -> Unit,
    onMediaGridCellClick: (Long) -> Unit = {},
    onMediaGridBulkTagsChange: (Set<Long>, Set<Long>, Set<Long>, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    onToggleDisplayMode: () -> Unit,
    modifier: Modifier = Modifier,
    onApplyFilters: (TweetFilterState) -> Unit,
    onApplySort: (ClassifiedSortState) -> Unit,
    onClearAllFilters: () -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onAuthorClick: (ClipEntity) -> Unit,
) {
    val state = rememberLazyGridState()
    val dataKey = mediaGridState.dataKey ?: MediaGridDataKey(
        mediaGridState.sourceRevision,
        uiState.tagHierarchy.structuralRevision,
        effectiveMediaGridFilter(uiState.filters),
        effectiveMediaGridSort(uiState.sort),
    )
    val frame = remember(mediaGridState.entries, dataKey, mediaGridColumnCount) {
        buildMediaGridFrameData(mediaGridState.entries, dataKey.sort, mediaGridColumnCount, dataKey)
    }
    EnhancedClassifiedScreen(
        uiState = uiState,
        mediaGridState = mediaGridState.copy(status = MediaGridLoadStatus.Ready),
        mediaGridSessionState = MediaGridSessionUiState(
            sessionKey = mediaGridSessionKey(dataKey),
            frame = frame,
            columnCount = mediaGridColumnCount,
            requestedColumnCount = mediaGridColumnCount,
            controllerState = MediaGridControllerUiState(MediaGridStartupState.Ready),
            showInitialProgress = false,
        ),
        listState = listState,
        mediaGridLazyState = state,
        displayMode = displayMode,
        mediaGridColumnCount = mediaGridColumnCount,
        onMediaGridColumnCountChange = onMediaGridColumnCountChange,
        onMediaGridCellClick = onMediaGridCellClick,
        onMediaGridBulkTagsChange = onMediaGridBulkTagsChange,
        onToggleDisplayMode = onToggleDisplayMode,
        modifier = modifier,
        onApplyFilters = onApplyFilters,
        onApplySort = onApplySort,
        onClearAllFilters = onClearAllFilters,
        onTagsChange = onTagsChange,
        onSummaryChange = onSummaryChange,
        onOcrSave = onOcrSave,
        onOcrDetect = onOcrDetect,
        onDelete = onDelete,
        onAuthorClick = onAuthorClick,
    )
}

@Composable
internal fun EnhancedClassifiedScreen(
    uiState: MainUiState,
    mediaGridState: ClassifiedMediaGridState,
    mediaGridSessionState: MediaGridSessionUiState,
    listState: LazyListState,
    mediaGridLazyState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onMediaGridAnchorCheckpoint: (MediaGridSessionKey, ClassifiedMediaGridScrollAnchor) -> Unit = { _, _ -> },
    displayMode: ClassifiedDisplayMode,
    mediaGridColumnCount: Int,
    onMediaGridColumnCountChange: (Int) -> Unit,
    onMediaGridCellClick: (Long) -> Unit = {},
    onMediaGridBulkTagsChange: (Set<Long>, Set<Long>, Set<Long>, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    onToggleDisplayMode: () -> Unit,
    modifier: Modifier = Modifier,
    onApplyFilters: (TweetFilterState) -> Unit,
    onApplySort: (ClassifiedSortState) -> Unit,
    onClearAllFilters: () -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onAuthorClick: (ClipEntity) -> Unit,
) {
    val context = LocalContext.current
    val appContainer = (context.applicationContext as LikeListManagerApp).container
    var filterDialogOpen by remember { mutableStateOf(false) }
    var sortDialogOpen by remember { mutableStateOf(false) }
    var clearConfirmationOpen by remember { mutableStateOf(false) }
    var selectedMediaGridClipIds by remember { mutableStateOf(emptySet<Long>()) }
    var mediaGridSelectionMode by remember { mutableStateOf(false) }
    var bulkTagDialogOpen by remember { mutableStateOf(false) }
    val itemKeys = remember(displayMode, uiState.clips, uiState.filters, uiState.sort, uiState.tagHierarchy) {
        if (displayMode == ClassifiedDisplayMode.Card) uiState.classified.map { it.clip.id } else emptyList()
    }
    var pendingPinchAnchor by remember { mutableStateOf<ClassifiedMediaGridScrollAnchor?>(null) }
    var pinchCompletionGeneration by remember { mutableStateOf(0) }
    var morphCheckpointSuppressed by remember(mediaGridSessionState.sessionKey) { mutableStateOf(false) }
    val latestSessionKey by rememberUpdatedState(mediaGridSessionState.sessionKey)
    val latestFrame by rememberUpdatedState(mediaGridSessionState.frame)
    val latestCheckpoint by rememberUpdatedState(onMediaGridAnchorCheckpoint)
    var sessionRestoreCheckpointSuppressed by remember(mediaGridSessionState.sessionKey) { mutableStateOf(false) }
    var legacyPinchCheckpointSuppressed by remember(mediaGridSessionState.sessionKey) { mutableStateOf(false) }
    val suppressScrollCheckpoint =
        sessionRestoreCheckpointSuppressed || legacyPinchCheckpointSuppressed || morphCheckpointSuppressed
    val latestSuppressScrollCheckpoint by rememberUpdatedState(suppressScrollCheckpoint)
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    if (displayMode == ClassifiedDisplayMode.MediaGrid && lifecycleOwner != null) {
        DisposableEffect(lifecycleOwner, mediaGridSessionState.sessionKey, displayMode) {
            val outgoingSessionKey = mediaGridSessionState.sessionKey
            val outgoingFrame = mediaGridSessionState.frame
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    val key = latestSessionKey
                    val frame = latestFrame
                    if (!latestSuppressScrollCheckpoint && key != null && frame != null) {
                        captureClassifiedMediaGridScrollAnchor(mediaGridLazyState, frame.assetIdByItemKey)
                            ?.let { latestCheckpoint(key, it) }
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                if (
                    !latestSuppressScrollCheckpoint &&
                    displayMode == ClassifiedDisplayMode.MediaGrid &&
                    outgoingSessionKey != null &&
                    outgoingFrame != null
                ) {
                    captureClassifiedMediaGridScrollAnchor(mediaGridLazyState, outgoingFrame.assetIdByItemKey)
                        ?.let { latestCheckpoint(outgoingSessionKey, it) }
                }
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
    LaunchedEffect(mediaGridLazyState, mediaGridSessionState.sessionKey, displayMode) {
        var checkpointState = MediaGridScrollCheckpointState()
        snapshotFlow { mediaGridLazyState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                val transition = mediaGridScrollCheckpointTransition(checkpointState, inProgress)
                checkpointState = transition.state
                if (transition.shouldCheckpoint) {
                    val key = latestSessionKey
                    val frame = latestFrame
                    if (!suppressScrollCheckpoint && displayMode == ClassifiedDisplayMode.MediaGrid && key != null && frame != null) {
                        captureClassifiedMediaGridScrollAnchor(mediaGridLazyState, frame.assetIdByItemKey)
                            ?.let { latestCheckpoint(key, it) }
                    }
                }
            }
    }
    var previousMediaGridSessionKey by remember { mutableStateOf<MediaGridSessionKey?>(null) }
    LaunchedEffect(mediaGridSessionState.sessionKey) {
        val key = mediaGridSessionState.sessionKey ?: return@LaunchedEffect
        if (key == previousMediaGridSessionKey) return@LaunchedEffect
        sessionRestoreCheckpointSuppressed = true
        val saved = mediaGridSessionState.anchor
        val frame = mediaGridSessionState.frame
        if (saved == null || frame == null || frame.items.isEmpty()) {
            if (frame != null && frame.items.isNotEmpty()) mediaGridLazyState.scrollToItem(0)
        } else {
            val targetIndex = frame.items.indexOfFirst { it.key == saved.key }
                .takeIf { it >= 0 }
                ?: saved.index.coerceIn(0, frame.items.lastIndex)
            mediaGridLazyState.scrollToItem(targetIndex, saved.offset)
        }
        withFrameNanos { }
        frame?.let { value ->
            captureClassifiedMediaGridScrollAnchor(mediaGridLazyState, value.assetIdByItemKey)
                ?.let { latestCheckpoint(key, it) }
        }
        sessionRestoreCheckpointSuppressed = false
        previousMediaGridSessionKey = key
    }
    val selectableMediaGridClipIds = remember(mediaGridState.entries) {
        mediaGridState.entries.map { it.clipId }.toSet()
    }
    val multiAssetMediaGridClipIds = remember(mediaGridState.entries) {
        mediaGridState.entries.groupingBy { it.clipId }.eachCount().filterValues { it >= 2 }.keys
    }
    val selectedVisibleMediaGridClipIds = selectedMediaGridClipIds.intersect(selectableMediaGridClipIds)
    LaunchedEffect(mediaGridState.status, selectableMediaGridClipIds) {
        if (mediaGridState.status == MediaGridLoadStatus.Ready) {
            selectedMediaGridClipIds = selectedVisibleMediaGridClipIds
        }
    }
    LaunchedEffect(mediaGridState.sourceRevision, mediaGridState.entries, uiState.sort, displayMode) {
        pendingPinchAnchor = null
    }
    if (displayMode == ClassifiedDisplayMode.Card) PreserveScrollAnchor(listState, "classified", itemKeys)
    LaunchedEffect(mediaGridSessionState.sessionKey, mediaGridSessionState.columnCount, mediaGridSessionState.frame, pinchCompletionGeneration) {
        val anchor = pendingPinchAnchor ?: mediaGridSessionState.anchor ?: return@LaunchedEffect
        val frame = mediaGridSessionState.frame ?: return@LaunchedEffect
        if (frame.items.isEmpty()) return@LaunchedEffect
        legacyPinchCheckpointSuppressed = true
        val targetIndex = frame.items.indexOfFirst { it.key == anchor.key }
            .takeIf { it >= 0 }
            ?: anchor.index.coerceIn(0, frame.items.lastIndex)
        var target: androidx.compose.foundation.lazy.grid.LazyGridItemInfo? = null
        repeat(3) {
            if (target == null) {
                withFrameNanos { }
                mediaGridLazyState.scrollToItem(targetIndex, anchor.offset)
                withFrameNanos { }
                target = mediaGridLazyState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
            }
        }
        target?.let { laidOutTarget ->
            val viewportCenter = (mediaGridLazyState.layoutInfo.viewportStartOffset + mediaGridLazyState.layoutInfo.viewportEndOffset) / 2f
            mediaGridLazyState.scrollBy(laidOutTarget.offset.y + laidOutTarget.size.height / 2f - viewportCenter - anchor.centerOffset)
        }
        pendingPinchAnchor = null
        withFrameNanos { }
        captureClassifiedMediaGridScrollAnchor(mediaGridLazyState, frame.assetIdByItemKey)
            ?.let { latestSessionKey?.let { key -> latestCheckpoint(key, it) } }
        legacyPinchCheckpointSuppressed = false
    }
    BackHandler(enabled = displayMode == ClassifiedDisplayMode.MediaGrid && mediaGridSelectionMode && !bulkTagDialogOpen) {
        mediaGridSelectionMode = false
        selectedMediaGridClipIds = emptySet()
    }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .zIndex(ClassifiedMediaGridToolbarZIndex),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp),
            ) {
        if (displayMode == ClassifiedDisplayMode.MediaGrid && mediaGridSelectionMode) {
            MediaGridSelectionToolbar(
                selectedCount = selectedVisibleMediaGridClipIds.size,
                allSelected = selectedVisibleMediaGridClipIds == selectableMediaGridClipIds,
                onToggleAll = {
                    selectedMediaGridClipIds = if (selectedVisibleMediaGridClipIds == selectableMediaGridClipIds) {
                        emptySet()
                    } else {
                        selectableMediaGridClipIds
                    }
                },
                onEditTags = { if (selectedVisibleMediaGridClipIds.isNotEmpty()) bulkTagDialogOpen = true },
                editTagsEnabled = selectedVisibleMediaGridClipIds.isNotEmpty(),
                onClose = {
                    mediaGridSelectionMode = false
                    selectedMediaGridClipIds = emptySet()
                },
            )
        } else {
            TagFilterSummaryRow(
                uiState = uiState,
                hierarchy = uiState.tagHierarchy,
                displayMode = displayMode,
                matchingClipCount = when (mediaGridState.status) {
                    MediaGridLoadStatus.Calculating -> null
                    MediaGridLoadStatus.Ready -> mediaGridState.matchingClipCount
                },
                onOpen = { filterDialogOpen = true },
                onOpenSort = { sortDialogOpen = true },
                onToggleDisplayMode = onToggleDisplayMode,
                onClear = { clearConfirmationOpen = true },
                interactionEnabled = !morphCheckpointSuppressed,
            )
        }
        Spacer(Modifier.height(10.dp))
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
        if (displayMode == ClassifiedDisplayMode.MediaGrid) {
            when {
                mediaGridSessionState.frame?.items?.isEmpty() == true && mediaGridState.status == MediaGridLoadStatus.Ready && mediaGridState.isEmptyByFilter -> HierarchyEmptyState("条件に合うツイートはありません")
                mediaGridSessionState.frame?.items?.isEmpty() == true && mediaGridState.status == MediaGridLoadStatus.Ready && mediaGridState.hasMatchingClipButNoMedia -> HierarchyEmptyState("この条件に一致する画像・動画サムネイルはありません")
                mediaGridSessionState.frame == null -> Box(
                    Modifier.fillMaxSize().testTag("classified_media_grid_progress"),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.CircularProgressIndicator() }
                else -> ClassifiedMediaGridContent(
                    frame = mediaGridSessionState.frame!!,
                    sort = uiState.sort,
                    columnCount = mediaGridSessionState.columnCount,
                    state = mediaGridLazyState,
                    controller = mediaGridSessionState.controller,
                    retainedImageStore = mediaGridSessionState.retainedImageStore,
                    controllerState = mediaGridSessionState.controllerState,
                    showProgress = mediaGridSessionState.showInitialProgress,
                    onMediaGridAnchorCheckpoint = onMediaGridAnchorCheckpoint,
                    onMorphCheckpointSuppressed = { morphCheckpointSuppressed = it },
                    onMediaGridColumnCountChange = onMediaGridColumnCountChange,
                    onPinchFinished = { anchor, nextColumnCount ->
                        val changed = nextColumnCount != mediaGridColumnCount
                        pendingPinchAnchor = anchor
                        pinchCompletionGeneration++
                        if (changed) {
                            onMediaGridColumnCountChange(nextColumnCount)
                        }
                    },
                    onCellClick = onMediaGridCellClick,
                    selectedClipIds = selectedVisibleMediaGridClipIds,
                    selectionMode = mediaGridSelectionMode,
                    multiAssetClipIds = multiAssetMediaGridClipIds,
                    residentCanvasMode = MediaGridResidentCanvasMode.Enabled,
                    onToggleSelection = { clipId ->
                        mediaGridSelectionMode = true
                        selectedMediaGridClipIds = selectedMediaGridClipIds.toMutableSet().apply {
                            if (!add(clipId)) remove(clipId)
                        }
                    },
                )
            }
        } else if (uiState.classified.isEmpty()) {
            HierarchyEmptyState("条件に合うツイートはありません")
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize().testTag("clip_list"),
                ) {
                    items(uiState.classified, key = { it.clip.id }) { clip ->
                        EnhancedTweetCard(
                            clip = clip,
                            hierarchy = uiState.tagHierarchy,
                            selectedTagIds = clip.tags.map { it.id }.toSet(),
                            onTagSelectionChange = { onTagsChange(clip.clip, it) },
                            onSummaryChange = onSummaryChange,
                            onOcrSave = onOcrSave,
                            onOcrDetect = onOcrDetect,
                            onDelete = onDelete,
                            onAuthorClick = onAuthorClick,
                        )
                    }
                }
                LazyListScrollbar(listState)
                ScrollToTopButton(listState, hasItems = uiState.classified.isNotEmpty())
            }
        }
    }
    }
    if (filterDialogOpen) {
        SearchFilterDialog(
            uiState = uiState,
            hierarchy = uiState.tagHierarchy,
            initialFilters = uiState.filters,
            onApply = onApplyFilters,
            onDismiss = { filterDialogOpen = false },
        )
    }
    if (sortDialogOpen) {
        SortConfigDialog(
            initialSort = uiState.sort,
            onApply = onApplySort,
            onDismiss = { sortDialogOpen = false },
        )
    }
    if (clearConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmationOpen = false },
            title = { Text("すべての条件をクリアしますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        clearConfirmationOpen = false
                        onClearAllFilters()
                    },
                    modifier = Modifier.testTag("filter_clear_confirm"),
                ) { Text("クリア") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmationOpen = false }) { Text("キャンセル") }
            },
        )
    }
    if (bulkTagDialogOpen && selectedVisibleMediaGridClipIds.isNotEmpty()) {
        val initialTagStates = aggregateBulkTagStates(
            selectedVisibleMediaGridClipIds.map { (mediaGridState.tagIdsByClip[it] ?: LongArray(0)).toSet() },
            uiState.tagHierarchy.tags.map { it.tag.id },
        )
        MediaGridBulkTagDialog(
            hierarchy = uiState.tagHierarchy,
            selectedClipCount = selectedVisibleMediaGridClipIds.size,
            initialTagStates = initialTagStates,
            onApply = { pending, complete ->
                onMediaGridBulkTagsChange(
                    selectedVisibleMediaGridClipIds,
                    pending.filterValues { it == BulkTagPending.ADD_ALL }.keys,
                    pending.filterValues { it == BulkTagPending.REMOVE_ALL }.keys,
                ) { error ->
                    complete(error)
                    if (error == null) {
                        bulkTagDialogOpen = false
                    }
                }
            },
            onDismiss = { bulkTagDialogOpen = false },
        )
    }
}

@Composable
fun MediaGridTweetDialog(
    state: MediaGridTweetDialogState,
    hierarchy: TagHierarchy,
    onDismiss: () -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onAuthorClick: (ClipEntity) -> Unit,
) {
    if (state == MediaGridTweetDialogState.Closed) return
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.9f)
                .testTag("media_grid_tweet_dialog"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("ツイート", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("media_grid_tweet_dialog_close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "閉じる")
                    }
                }
                when (state) {
                    MediaGridTweetDialogState.Closed -> Unit
                    MediaGridTweetDialogState.Loading -> Box(
                        Modifier.fillMaxSize().testTag("media_grid_tweet_dialog_loading"),
                        contentAlignment = Alignment.Center,
                    ) { Text("読み込み中…") }
                    MediaGridTweetDialogState.NotFound -> Column(
                        Modifier.fillMaxSize().testTag("media_grid_tweet_dialog_error").padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("ツイートを読み込めませんでした")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onDismiss) { Text("閉じる") }
                    }
                    is MediaGridTweetDialogState.Loaded -> Box(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                    ) {
                        EnhancedTweetCard(
                            clip = state.clip,
                            hierarchy = hierarchy,
                            selectedTagIds = state.clip.tags.map { it.id }.toSet(),
                            onTagSelectionChange = { onTagsChange(state.clip.clip, it) },
                            onSummaryChange = onSummaryChange,
                            onOcrSave = onOcrSave,
                            onOcrDetect = onOcrDetect,
                            onDelete = {
                                onDismiss()
                                onDelete(it)
                            },
                            onAuthorClick = onAuthorClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EnhancedTagListScreen(
    hierarchy: TagHierarchy,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onCreateTag: (String, Long?, String) -> Unit,
    onCreateGroup: (String, Long?, String) -> Unit,
    onRenameTag: (TagEntity, String, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onMove: (TagNodeRef, Long?) -> Unit,
    onMoveToIndex: (TagNodeRef, Long?, Int) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var createRequest by remember { mutableStateOf<Pair<TagNodeType, Long?>?>(null) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val rowBounds = remember { mutableStateMapOf<TagNodeRef, Rect>() }
    val visibleGroups = expanded.filterValues { it }.keys.toSet()
    val visibleRows = remember(hierarchy, visibleGroups) {
        hierarchy.visibleRows(visibleGroups)
    }
    var dragState by remember { mutableStateOf<DragState?>(null) }
    var settlingDragState by remember { mutableStateOf<DragState?>(null) }
    var listBounds by remember { mutableStateOf<Rect?>(null) }
    var dragLayerBounds by remember { mutableStateOf<Rect?>(null) }
    val haptics = LocalHapticFeedback.current
    val layoutDragState = dragState ?: settlingDragState
    val displayItems = remember(visibleRows, layoutDragState) {
        buildTagListItems(visibleRows, layoutDragState)
    }
    val currentVisibleRows by rememberUpdatedState(visibleRows)
    val currentDisplayItems by rememberUpdatedState(displayItems)
    val currentHierarchy by rememberUpdatedState(hierarchy)
    val currentListBounds by rememberUpdatedState(listBounds)
    val itemKeys = remember(displayItems) { displayItems.map { it.key } }
    PreserveScrollAnchor(listState, "tag_management", itemKeys)

    LaunchedEffect(visibleRows) {
        val visibleRefs = visibleRows.map { it.node.ref() }.toSet()
        rowBounds.keys.toList().filterNot { it in visibleRefs }.forEach { rowBounds.remove(it) }
    }

    LaunchedEffect(dragState?.targetParentId, dragState?.placeholderIndex) {
        if (dragState?.reorderLocked == true) {
            delay(64)
            dragState = dragState?.copy(reorderLocked = false)
        }
    }

    LaunchedEffect(dragState != null) {
        while (dragState != null) {
            val current = dragState ?: break
            val bounds = currentListBounds
            var consumedScroll = 0f
            if (bounds != null) {
                val scrollAmount = calculateAutoScrollDelta(
                    pointerY = current.pointerYInRoot,
                    listBounds = bounds,
                    canScrollBackward = listState.canScrollBackward,
                    canScrollForward = listState.canScrollForward,
                )
                if (scrollAmount != 0f) consumedScroll = listState.scrollBy(scrollAmount)
                if (consumedScroll != 0f) withFrameNanos { }
            }
            val updated = updateDragStateAfterMove(
                state = current,
                visibleRows = currentVisibleRows,
                displayItems = currentDisplayItems,
                rowBounds = rowBounds,
                hierarchy = currentHierarchy,
                listBounds = currentListBounds,
                allowStaticCrossing = consumedScroll != 0f,
            )
            if (updated != current) dragState = updated
            delay(16)
        }
    }

    LaunchedEffect(settlingDragState, visibleRows) {
        val state = settlingDragState ?: return@LaunchedEffect
        if (isMoveReflected(visibleRows, state)) {
            settlingDragState = null
        } else {
            delay(350)
            settlingDragState = null
        }
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { createRequest = TagNodeType.GROUP to null },
                modifier = Modifier.testTag("create_root_group"),
            ) { Text("グループ追加") }
            Button(
                onClick = { createRequest = TagNodeType.TAG to null },
                modifier = Modifier.testTag("create_root_tag"),
            ) { Text("タグ追加") }
        }
        Spacer(Modifier.height(8.dp))
        Text("長押ししてドラッグすると、グループへの移動と並び替えができます", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { dragLayerBounds = it.boundsInRoot() }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            val layerBounds = dragLayerBounds ?: return@detectDragGesturesAfterLongPress
                            val pointerRoot = Offset(layerBounds.left + offset.x, layerBounds.top + offset.y)
                            val start = dragStartCandidate(pointerRoot, currentDisplayItems, rowBounds) ?: return@detectDragGesturesAfterLongPress
                            val bounds = start.bounds
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragState = DragState(
                                node = start.item.row.node.ref(),
                                label = start.item.row.node.name,
                                isGroup = start.item.row.node is TagGroupNode,
                                sourceParentId = start.item.row.parentGroupId,
                                sourceIndexInParent = start.item.indexInParentWithoutDragged,
                                targetParentId = start.item.row.parentGroupId,
                                placeholderIndex = start.item.indexInParentWithoutDragged,
                                pointerYInRoot = pointerRoot.y,
                                grabOffsetY = (pointerRoot.y - bounds.top).coerceIn(0f, bounds.height),
                                itemLeftX = bounds.left,
                                itemWidth = bounds.width,
                                itemHeight = bounds.height,
                                lastDragCenterY = pointerRoot.y - (pointerRoot.y - bounds.top).coerceIn(0f, bounds.height) + bounds.height / 2,
                            )
                        },
                        onDragCancel = {
                            val currentDrag = dragState
                            if (currentDrag != null) {
                                settlingDragState = currentDrag
                                applyDrop(currentDrag, currentHierarchy, onMoveToIndex)
                            }
                            dragState = null
                        },
                        onDragEnd = {
                            val currentDrag = dragState
                            if (currentDrag != null) {
                                settlingDragState = currentDrag
                                applyDrop(currentDrag, currentHierarchy, onMoveToIndex)
                            }
                            dragState = null
                        },
                    ) { change, dragAmount ->
                        change.consume()
                        dragState = dragState?.let { current ->
                            updateDragStateAfterMove(
                                state = current.copy(pointerYInRoot = current.pointerYInRoot + dragAmount.y),
                                visibleRows = currentVisibleRows,
                                displayItems = currentDisplayItems,
                                rowBounds = rowBounds,
                                hierarchy = currentHierarchy,
                                listBounds = currentListBounds,
                                allowStaticCrossing = false,
                            )
                        }
                    }
                },
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("tag_list")
                    .onGloballyPositioned { listBounds = it.boundsInRoot() },
            ) {
                items(displayItems, key = { it.key }) { item ->
                    when (item) {
                        is TagListItem.Placeholder -> TagPlaceholderSpacer(item, Modifier.animateItem())
                        is TagListItem.Row -> {
                            val row = item.row
                            TagManagementRow(
                                modifier = Modifier.animateItem(),
                                row = row,
                                hierarchy = hierarchy,
                                expanded = expanded,
                                isGroupDropTarget = dragState?.let { state ->
                                    row.node is TagGroupNode &&
                                        state.targetIsGroupDrop &&
                                        state.targetParentId == row.node.id
                                } == true,
                                onBounds = { rect -> rowBounds[row.node.ref()] = rect },
                                onToggleExpanded = {
                                    if (row.node is TagGroupNode) expanded[row.node.id] = expanded[row.node.id] != true
                                },
                                onMove = onMove,
                                onCreate = { type, parent -> createRequest = type to parent },
                                onRenameTag = onRenameTag,
                                onRenameGroup = onRenameGroup,
                                onDeleteTag = onDeleteTag,
                                onDeleteGroup = onDeleteGroup,
                                onAddAll = onAddAll,
                            )
                        }
                    }
                }
            }
            dragState?.let { state ->
                TagDragPreview(state, dragLayerBounds)
            }
            LazyListScrollbar(listState)
            ScrollToTopButton(listState, hasItems = displayItems.isNotEmpty())
        }
    }

    createRequest?.let { (type, parentId) ->
        CreateNodeDialog(type, parentId, onDismiss = { createRequest = null }, onCreate = { name, colorId ->
            if (type == TagNodeType.TAG) onCreateTag(name, parentId, colorId) else onCreateGroup(name, parentId, colorId)
            createRequest = null
        })
    }
}

@Composable
private fun EnhancedTweetCard(
    clip: ClipWithDetails,
    hierarchy: TagHierarchy,
    selectedTagIds: Set<Long>,
    requireTagConfirmation: Boolean = false,
    onTagSelectionChange: (Set<Long>) -> Unit,
    onTagConfirmation: () -> Unit = {},
    onSummaryChange: (ClipEntity, String) -> Unit,
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
    onAuthorClick: (ClipEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var summaryDialogOpen by remember { mutableStateOf(false) }
    var summaryDraft by remember(clip.clip.id, clip.clip.summary) { mutableStateOf(clip.clip.summary) }
    var ocrDialogOpen by remember { mutableStateOf(false) }
    var ocrRedetectWarningOpen by remember { mutableStateOf(false) }
    var ocrText by remember(clip.clip.id, clip.clip.ocrText) { mutableStateOf(clip.clip.ocrText) }
    var ocrError by remember { mutableStateOf<String?>(null) }
    var ocrProcessing by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    val selectionPath = remember { mutableStateListOf<Long>() }
    var selectionOpen by remember { mutableStateOf(false) }
    var likePopupOpen by remember(clip.clip.id) { mutableStateOf(false) }
    val ocrPreviewPaths = remember(clip.assets) {
        clip.assets
            .filter { it.type == "photo" || it.type == "video_thumbnail" }
            .mapNotNull { it.localPath }
    }
    val hasOcrAction = remember(clip.assets) {
        clip.assets.any { (it.type == "photo" || it.type == "video_thumbnail") && it.localPath != null }
    }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.fillMaxWidth().testTag("clip_card_${clip.clip.id}"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onAuthorClick(clip.clip) }
                        .testTag("clip_author_${clip.clip.id}")
                        .padding(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(clip.clip.authorName, fontWeight = FontWeight.SemiBold)
                        clip.clip.likeCount?.let { likeCount ->
                            Spacer(Modifier.width(8.dp))
                            Box {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { likePopupOpen = true }
                                        .testTag("clip_like_count_${clip.clip.id}")
                                        .padding(horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(formatLikeCount(likeCount), style = MaterialTheme.typography.bodySmall)
                                    if (clip.clip.hasProvisionalLikeCount()) {
                                        Icon(
                                            Icons.Filled.ErrorOutline,
                                            contentDescription = "一時的ないいね数",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = likePopupOpen,
                                    onDismissRequest = { likePopupOpen = false },
                                    modifier = Modifier.testTag("clip_like_popup_${clip.clip.id}"),
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("いいね数: ${String.format(Locale.JAPAN, "%,d", likeCount)}")
                                                Text("取得日時: ${formatLikeFetchedAt(clip.clip.likeCountFetchedAt)}", style = MaterialTheme.typography.bodySmall)
                                                if (clip.clip.likeCountFetchError != null) {
                                                    Text("取得エラー: ${clip.clip.likeCountFetchError}", style = MaterialTheme.typography.bodySmall)
                                                } else if (clip.clip.hasProvisionalLikeCount()) {
                                                    Text("一時的に取得した値の可能性があります", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        },
                                        onClick = { likePopupOpen = false },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "@${clip.clip.authorUsername}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TweetOptionsMenuButton(
                    hasOcrAction = hasOcrAction,
                    onOcrAction = {
                        ocrDialogOpen = true
                        ocrText = clip.clip.ocrText
                        if (clip.clip.ocrText.isBlank()) {
                            ocrProcessing = true
                            ocrError = null
                            onOcrDetect(
                                clip,
                                { result ->
                                    ocrText = result
                                    ocrProcessing = false
                                },
                                { message ->
                                    ocrError = message
                                    ocrProcessing = false
                                },
                            )
                        } else {
                            ocrProcessing = false
                            ocrError = null
                        }
                    },
                    onSummaryAction = {
                        summaryDraft = clip.clip.summary
                        summaryDialogOpen = true
                    },
                    onDeleteAction = { deleteOpen = true },
                    buttonTestTag = "tweet_options_button_${clip.clip.id}",
                )
                IconButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clip.clip.postUrl)))
                    },
                    modifier = Modifier.testTag("clip_open_x_${clip.clip.id}"),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_x_logo),
                        contentDescription = "Xで開く",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                clip.clip.text.withoutTrailingMediaUrl(hasAssets = clip.assets.isNotEmpty()),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            if (clip.assets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                EnhancedMediaGrid(clip.assets)
            }
            if (clip.clip.summary.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    clip.clip.summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            TagHierarchySelector(
                hierarchy = hierarchy,
                selectedTagIds = selectedTagIds,
                onToggleTag = { tagId ->
                    val selected = selectedTagIds.toMutableSet()
                    if (!selected.add(tagId)) selected.remove(tagId)
                    onTagSelectionChange(selected)
                },
                onOpenGroup = { groupId ->
                    selectionPath.clear()
                    selectionPath.add(groupId)
                    selectionOpen = true
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (requireTagConfirmation) {
                    Button(
                        onClick = onTagConfirmation,
                        enabled = hierarchy.tags.isNotEmpty() && selectedTagIds.isNotEmpty(),
                        modifier = Modifier.testTag("classify_${clip.clip.id}"),
                    ) {
                        Text("タグを付ける")
                    }
                }
            }
        }
    }
    if (summaryDialogOpen) {
        SummarySettingsDialog(
            summary = summaryDraft,
            onSummaryChange = { summaryDraft = it },
            onConfirm = {
                onSummaryChange(clip.clip, summaryDraft)
                summaryDialogOpen = false
            },
            onDismiss = { summaryDialogOpen = false },
        )
    }
    if (selectionOpen) {
        TagSelectionDialog(
            hierarchy = hierarchy,
            selectedTagIds = selectedTagIds,
            onToggleTag = { tagId ->
                val selected = selectedTagIds.toMutableSet()
                if (!selected.add(tagId)) selected.remove(tagId)
                onTagSelectionChange(selected)
            },
            onDismiss = { selectionOpen = false },
            initialPath = selectionPath.toList(),
        )
    }
    if (ocrDialogOpen) {
        OcrTextDialog(
            previewPaths = ocrPreviewPaths,
            text = ocrText,
            isProcessing = ocrProcessing,
            errorMessage = ocrError,
            onTextChange = { ocrText = it },
            onRedetect = { ocrRedetectWarningOpen = true },
            onConfirm = {
                onOcrSave(clip.clip, ocrText)
                ocrDialogOpen = false
            },
            onDismiss = {
                ocrDialogOpen = false
                ocrProcessing = false
            },
        )
    }
    if (ocrRedetectWarningOpen) {
        OcrRedetectConfirmDialog(
            onConfirm = {
                ocrRedetectWarningOpen = false
                ocrDialogOpen = true
                ocrProcessing = true
                ocrError = null
                onOcrDetect(
                    clip,
                    { result ->
                        ocrText = result
                        ocrProcessing = false
                    },
                    { message ->
                        ocrError = message
                        ocrProcessing = false
                    },
                )
            },
            onDismiss = {
                ocrRedetectWarningOpen = false
            },
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            modifier = Modifier.testTag("clip_local_delete_dialog_${clip.clip.id}"),
            title = { Text("ローカル削除") },
            text = {
                Text("このツイートをアプリ内の一覧から削除します")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(clip.clip)
                        deleteOpen = false
                    },
                    modifier = Modifier.testTag("clip_local_delete_confirm_${clip.clip.id}"),
                ) {
                    Text("削除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteOpen = false },
                    modifier = Modifier.testTag("clip_local_delete_cancel_${clip.clip.id}"),
                ) {
                    Text("キャンセル")
                }
            },
        )
    }
}


@Composable
private fun TagHierarchySelector(
    hierarchy: TagHierarchy,
    selectedTagIds: Set<Long>,
    mixedTagIds: Set<Long> = emptySet(),
    onToggleTag: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
) {
    val roots = hierarchy.children(null)
    if (roots.isEmpty()) {
        Text("タグリストでタグを追加すると、ここから選べます", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(roots, key = { it.ref().saveableKey() }) { node ->
                TagHierarchyChip(
                    node = node,
                    hierarchy = hierarchy,
                    selectedTagIds = selectedTagIds,
                    mixedTagIds = mixedTagIds,
                    onToggleTag = onToggleTag,
                    onOpenGroup = onOpenGroup,
                )
            }
        }
    }
}

@Composable
private fun TagSelectionDialog(
    hierarchy: TagHierarchy,
    selectedTagIds: Set<Long>,
    mixedTagIds: Set<Long> = emptySet(),
    onToggleTag: (Long) -> Unit,
    onDismiss: () -> Unit,
    initialPath: List<Long> = emptyList(),
) {
    val path = remember(initialPath) { mutableStateListOf<Long>().apply { addAll(initialPath) } }
    val currentParentId = path.lastOrNull()
    val currentGroup = currentParentId?.let { hierarchy.groups.firstOrNull { group -> group.id == it } }
    val children = hierarchy.children(currentParentId)
    BackHandler(enabled = true) {
        if (path.size <= 1) {
            onDismiss()
        } else {
            path.removeAt(path.lastIndex)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Surface(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.5f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (path.size <= 1) onDismiss() else path.removeAt(path.lastIndex)
                    }) {
                        Icon(if (path.size <= 1) Icons.Filled.Close else Icons.Filled.ArrowBack, contentDescription = null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("タグを選択", style = MaterialTheme.typography.titleLarge)
                        Text(currentGroup?.name ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (children.isEmpty()) {
                    HierarchyEmptyState("この階層にはタグがありません")
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(144.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            gridItems(children, key = { it.ref().saveableKey() }) { node ->
                                TagHierarchyChip(
                                    node = node,
                                    hierarchy = hierarchy,
                                    selectedTagIds = selectedTagIds,
                                    mixedTagIds = mixedTagIds,
                                    onToggleTag = onToggleTag,
                                    onOpenGroup = { groupId -> path.add(groupId) },
                                    fillMaxWidth = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagFilterSummaryRow(
    uiState: MainUiState,
    hierarchy: TagHierarchy,
    displayMode: ClassifiedDisplayMode,
    matchingClipCount: Int?,
    onOpen: () -> Unit,
    onOpenSort: () -> Unit,
    onToggleDisplayMode: () -> Unit,
    onClear: () -> Unit,
    interactionEnabled: Boolean,
) {
    val filters = uiState.filters
    Row(
        Modifier.fillMaxWidth().zIndex(ClassifiedMediaGridToolbarZIndex),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "一致件数:${if (displayMode == ClassifiedDisplayMode.MediaGrid) matchingClipCount?.toString() ?: "計算中" else uiState.classified.size}件",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    filterConditionSummary(filters, hierarchy, uiState.authorOptions),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "｜",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    sortConditionSummary(uiState.sort),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        FilledTonalButton(
            onClick = { if (interactionEnabled) onOpen() },
            enabled = interactionEnabled,
            modifier = Modifier.width(36.dp).height(32.dp).testTag("filter_open"),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Filled.FilterList,
                contentDescription = "絞り込み",
                modifier = Modifier.size(18.dp),
            )
        }
        FilledTonalButton(
            onClick = { if (interactionEnabled) onOpenSort() },
            enabled = interactionEnabled,
            modifier = Modifier.width(36.dp).height(32.dp).testTag("sort_open"),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                Icons.Filled.Sort,
                contentDescription = "並べ替え",
                modifier = Modifier.size(18.dp),
            )
        }
        FilledTonalButton(
            onClick = { if (interactionEnabled) onToggleDisplayMode() },
            enabled = interactionEnabled,
            modifier = Modifier.width(36.dp).height(32.dp).testTag("classified_display_toggle"),
            contentPadding = PaddingValues(0.dp),
        ) {
            Icon(
                imageVector = if (displayMode == ClassifiedDisplayMode.Card) Icons.Filled.GridView else Icons.Filled.ViewList,
                contentDescription = "表示切替",
                modifier = Modifier.size(18.dp),
            )
        }
        TextButton(
            onClick = { if (interactionEnabled) onClear() },
            enabled = interactionEnabled && filters.hasActiveFilters,
            modifier = Modifier.width(44.dp).height(32.dp).testTag("filter_clear"),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("クリア", style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

internal fun filterConditionSummary(
    filters: TweetFilterState,
    hierarchy: TagHierarchy,
    authors: List<TweetAuthorOption>,
): String {
    val conditions = mutableListOf(if (filters.taggedOnly) "対象:タグ付きのみ" else "対象:全ツイート")
    if (filters.query.isNotBlank()) {
        val kind = if (filters.searchMode == SearchMode.Regex) "正規表現" else "文字列"
        val targets = SearchTarget.entries.filter { it in filters.searchTargets }.joinToString("・") { it.label }
        conditions += "$kind:\"${filters.query}\"(対象:$targets)"
    }
    if (filters.startDate != null || filters.endDate != null) {
        conditions += "期間:${filters.startDate?.summaryText() ?: "..."}~${filters.endDate?.summaryText() ?: "..."}"
    }
    if (filters.selectedAuthors.isNotEmpty()) {
        val namesByKey = authors.associate { it.key to "@${it.username}" }
        val names = authors.map { it.key }.filter { it in filters.selectedAuthors }.mapNotNull(namesByKey::get) +
            filters.selectedAuthors.filterNot { it in namesByKey }.map { "@${it.username}" }
        conditions += "ユーザー:${names.joinToString(",")}"
    }
    val tagParts = listOf(
        TagFilterState.INCLUDED to "含む",
        TagFilterState.REQUIRED to "必須",
        TagFilterState.EXCLUDED to "排除",
    ).mapNotNull { (state, label) ->
        val names = filters.tagFilters.filterValues { it == state }.keys
            .map { hierarchy.nodeFor(it)?.name ?: "不明" }
        names.takeIf { it.isNotEmpty() }?.let { "$label[${it.joinToString(",")}]" }
    }
    if (tagParts.isNotEmpty()) conditions += "タグ:${tagParts.joinToString(",")}"
    if (conditions.size == 1 && filters.taggedOnly) conditions += "条件なし"
    return conditions.joinToString("、")
}

private fun LocalDate.summaryText(): String = "$year/${monthValue}/${dayOfMonth}"

private enum class DateFilterEndpoint { Start, End }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchFilterDialog(
    uiState: MainUiState,
    hierarchy: TagHierarchy,
    initialFilters: TweetFilterState,
    onApply: (TweetFilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    var filters by remember(initialFilters) { mutableStateOf(initialFilters) }
    var dateEndpoint by remember { mutableStateOf<DateFilterEndpoint?>(null) }
    var authorDialogOpen by remember { mutableStateOf(false) }
    var discardConfirmationOpen by remember { mutableStateOf(false) }
    var clearConfirmationOpen by remember { mutableStateOf(false) }
    val path = remember { mutableStateListOf<Long>() }
    val currentParentId = path.lastOrNull()
    val currentGroup = currentParentId?.let { hierarchy.groups.firstOrNull { group -> group.id == it } }
    val children = hierarchy.children(currentParentId)
    val matchingCount = remember(uiState.clips, hierarchy, filters) {
        filterClipsForSearch(uiState.clips, hierarchy, filters).size
    }
    val hasChanges = filters != initialFilters
    fun requestDismiss() {
        if (hasChanges) discardConfirmationOpen = true else onDismiss()
    }
    fun toggleTarget(target: SearchTarget) {
        val current = filters.searchTargets
        val next = if (target in current && current.size > 1) current - target else current + target
        filters = filters.copy(searchTargets = next)
    }
    fun cycleTag(ref: TagNodeRef) {
        val current = filters.tagFilters[ref] ?: TagFilterState.NONE
        val next = when (current) {
            TagFilterState.NONE -> TagFilterState.INCLUDED
            TagFilterState.INCLUDED -> if (ref.type == TagNodeType.GROUP) TagFilterState.EXCLUDED else TagFilterState.REQUIRED
            TagFilterState.REQUIRED -> TagFilterState.EXCLUDED
            TagFilterState.EXCLUDED -> TagFilterState.NONE
        }
        filters = filters.copy(tagFilters = filters.tagFilters.toMutableMap().apply {
            if (next == TagFilterState.NONE) remove(ref) else put(ref, next)
        })
    }
    BackHandler { requestDismiss() }
    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("filter_dialog"), shape = RoundedCornerShape(0.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("絞り込み", style = MaterialTheme.typography.titleLarge)
                        Text("一致件数:${matchingCount}件", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { clearConfirmationOpen = true },
                        modifier = Modifier.testTag("filter_clear_all_open"),
                    ) { Text("全クリア") }
                }
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp)
                        .testTag("filter_options_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("タグのみ")
                                Text("OFFで未分類ツイートも対象", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = filters.taggedOnly,
                                onCheckedChange = { filters = filters.copy(taggedOnly = it) },
                                modifier = Modifier.testTag("filter_tagged_only"),
                            )
                        }
                    }
                    item { Divider() }
                    item {
                        Text("文字列検索", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = filters.query,
                            onValueChange = { filters = filters.copy(query = it) },
                            modifier = Modifier.fillMaxWidth().testTag("filter_query"),
                            singleLine = true,
                            isError = filters.regexError != null,
                            label = { Text("検索") },
                            supportingText = { Text(filters.regexError ?: "タグ名は検索対象に含めません") },
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = filters.searchMode == mode,
                                    onClick = { filters = filters.copy(searchMode = mode) },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                    }
                    item {
                        Text("検索対象", style = MaterialTheme.typography.titleSmall)
                    }
                    items(SearchTarget.entries.chunked(2), key = { row -> row.joinToString { it.name } }) { targets ->
                        Row(Modifier.fillMaxWidth()) {
                            targets.forEach { target ->
                                Row(
                                    Modifier.weight(1f).clickable { toggleTarget(target) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(checked = target in filters.searchTargets, onCheckedChange = { toggleTarget(target) })
                                    Text(target.label)
                                }
                            }
                        }
                    }
                    item { Divider() }
                    item {
                        Text("期間", style = MaterialTheme.typography.titleSmall)
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = filters.startDate != null,
                                onClick = { dateEndpoint = DateFilterEndpoint.Start },
                                modifier = Modifier.testTag("filter_start_date"),
                                label = { Text("開始 ${filters.startDate ?: "未指定"}") },
                            )
                            FilterChip(
                                selected = filters.endDate != null,
                                onClick = { dateEndpoint = DateFilterEndpoint.End },
                                modifier = Modifier.testTag("filter_end_date"),
                                label = { Text("終了 ${filters.endDate ?: "未指定"}") },
                            )
                        }
                    }
                    item {
                        TextButton(
                            onClick = { filters = filters.copy(startDate = null, endDate = null) },
                            modifier = Modifier.testTag("filter_date_clear"),
                        ) {
                            Text("日付クリア")
                        }
                    }
                    item { Divider() }
                    item {
                        Text("ユーザー", style = MaterialTheme.typography.titleSmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { authorDialogOpen = true },
                                modifier = Modifier.testTag("filter_author_open"),
                            ) {
                                Text("ユーザーを選択")
                            }
                            if (filters.selectedAuthors.isNotEmpty()) {
                                Text("${filters.selectedAuthors.size}件選択中", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (filters.selectedAuthors.isNotEmpty()) {
                        item {
                            val selected = uiState.authorOptions.filter { it.key in filters.selectedAuthors }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(selected, key = { "${it.key.authorId}:${it.key.username}" }) { author ->
                                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                        Row(
                                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Column {
                                                Text(author.displayName, fontWeight = FontWeight.SemiBold)
                                                Text("@${author.username}", style = MaterialTheme.typography.bodySmall)
                                            }
                                            Text("${author.count}件", style = MaterialTheme.typography.bodySmall)
                                            IconButton(
                                                onClick = { filters = filters.copy(selectedAuthors = filters.selectedAuthors - author.key) },
                                                modifier = Modifier.size(32.dp),
                                            ) { Icon(Icons.Filled.Close, contentDescription = "${author.displayName}を削除") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Divider() }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (path.isNotEmpty()) {
                                IconButton(onClick = { path.removeAt(path.lastIndex) }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "上のグループへ戻る")
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text("タグ条件", style = MaterialTheme.typography.titleSmall)
                                Text(currentGroup?.name ?: "ルート", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = { filters = filters.copy(tagFilters = emptyMap()) },
                                enabled = filters.tagFilters.isNotEmpty(),
                                modifier = Modifier.testTag("filter_tag_clear"),
                            ) { Text("タグ条件クリア") }
                        }
                        Text("含: 緑 / 必: 青 / 除: オレンジ。グループは含む・排除のみです。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (children.isEmpty()) {
                        item { HierarchyEmptyState("この階層には条件を設定できる項目がありません") }
                    } else {
                        item {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(144.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                gridItems(children, key = { it.ref().saveableKey() }) { node ->
                                    val ref = node.ref()
                                    val state = filters.tagFilters[ref] ?: TagFilterState.NONE
                                    val selected = state != TagFilterState.NONE
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        FilterChip(
                                            selected = selected,
                                            onClick = { cycleTag(ref) },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("filter_tag_condition_${ref.type.name.lowercase()}_${ref.id}"),
                                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = tagFilterColor(state)),
                                            label = {
                                                Column(horizontalAlignment = Alignment.Start) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (node is TagGroupNode) {
                                                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                                                            Spacer(Modifier.width(6.dp))
                                                        } else {
                                                            Icon(
                                                                Icons.Filled.LocalOffer,
                                                                contentDescription = null,
                                                                tint = tagColor((node as TagLeafNode).tag.colorId),
                                                                modifier = Modifier.size(18.dp),
                                                            )
                                                            Spacer(Modifier.width(6.dp))
                                                        }
                                                        Text("${state.shortLabel()}${node.name}")
                                                    }
                                                    Text(
                                                        "${node.count} 件",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                        )
                                        if (node is TagGroupNode) {
                                            IconButton(onClick = { path.add(node.id) }) {
                                                Icon(
                                                    Icons.Filled.ArrowBack,
                                                    contentDescription = null,
                                                    modifier = Modifier.graphicsLayer(rotationZ = 180f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Divider()
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = ::requestDismiss, modifier = Modifier.weight(1f).testTag("filter_cancel")) { Text("キャンセル") }
                    Button(
                        onClick = {
                            onApply(filters)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("filter_apply"),
                    ) { Text("適用") }
                }
            }
        }
    }
    dateEndpoint?.let { endpoint ->
        val selectedDate = if (endpoint == DateFilterEndpoint.Start) filters.startDate else filters.endDate
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = (selectedDate ?: LocalDate.now()).toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { dateEndpoint = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val picked = pickerState.selectedDateMillis?.toLocalDateFromPicker()
                        if (endpoint == DateFilterEndpoint.Start) {
                            filters = filters.copy(startDate = picked)
                        } else {
                            filters = filters.copy(endDate = picked)
                        }
                        dateEndpoint = null
                    },
                    modifier = Modifier.testTag("filter_date_picker_apply"),
                ) { Text("適用") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (endpoint == DateFilterEndpoint.Start) {
                            filters = filters.copy(startDate = null)
                        } else {
                            filters = filters.copy(endDate = null)
                        }
                        dateEndpoint = null
                    },
                    modifier = Modifier.testTag("filter_date_picker_clear"),
                ) { Text("解除") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
    if (authorDialogOpen) {
        AuthorFilterDialog(
            authors = uiState.authorOptions,
            selectedAuthors = filters.selectedAuthors,
            onToggle = { key ->
                filters = filters.copy(
                    selectedAuthors = if (key in filters.selectedAuthors) filters.selectedAuthors - key else filters.selectedAuthors + key,
                )
            },
            onClear = { filters = filters.copy(selectedAuthors = emptySet()) },
            onDismiss = { authorDialogOpen = false },
        )
    }
    if (discardConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { discardConfirmationOpen = false },
            title = { Text("変更を破棄しますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        discardConfirmationOpen = false
                        onDismiss()
                    },
                    modifier = Modifier.testTag("filter_discard_confirm"),
                ) { Text("破棄") }
            },
            dismissButton = {
                TextButton(
                    onClick = { discardConfirmationOpen = false },
                    modifier = Modifier.testTag("filter_discard_cancel"),
                ) { Text("戻る") }
            },
        )
    }
    if (clearConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmationOpen = false },
            title = { Text("すべての条件をクリアしますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        filters = TweetFilterState()
                        clearConfirmationOpen = false
                    },
                    modifier = Modifier.testTag("filter_clear_all_confirm"),
                ) { Text("クリア") }
            },
            dismissButton = {
                TextButton(
                    onClick = { clearConfirmationOpen = false },
                    modifier = Modifier.testTag("filter_clear_all_cancel"),
                ) { Text("キャンセル") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortConfigDialog(
    initialSort: ClassifiedSortState,
    onApply: (ClassifiedSortState) -> Unit,
    onDismiss: () -> Unit,
) {
    var sort by remember(initialSort) { mutableStateOf(initialSort) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize().testTag("sort_dialog"), shape = RoundedCornerShape(0.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("並べ替え", style = MaterialTheme.typography.titleLarge)
                        Text(sortConditionSummary(sort), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = { sort = ClassifiedSortState() },
                        modifier = Modifier.testTag("sort_clear_all_open"),
                    ) { Text("初期化") }
                }
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .padding(top = 8.dp)
                        .testTag("sort_options_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("タグ順")
                                Text("ONでタグ表示順を使います", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = sort.tagEnabled,
                                onCheckedChange = { sort = sort.copy(tagEnabled = it) },
                                modifier = Modifier.testTag("sort_tag_toggle"),
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !sort.tagDescending,
                                onClick = { sort = sort.copy(tagDescending = false) },
                                enabled = sort.tagEnabled,
                                modifier = Modifier.testTag("sort_tag_direction_top"),
                                label = { Text("上から") },
                            )
                            FilterChip(
                                selected = sort.tagDescending,
                                onClick = { sort = sort.copy(tagDescending = true) },
                                enabled = sort.tagEnabled,
                                modifier = Modifier.testTag("sort_tag_direction_bottom"),
                                label = { Text("下から") },
                            )
                        }
                    }
                    item { Divider() }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("ユーザー順")
                                Text("ONでユーザーごとの件数順を使います", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = sort.userEnabled,
                                onCheckedChange = { sort = sort.copy(userEnabled = it) },
                                modifier = Modifier.testTag("sort_user_toggle"),
                            )
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !sort.userDescending,
                                onClick = { sort = sort.copy(userDescending = false) },
                                enabled = sort.userEnabled,
                                modifier = Modifier.testTag("sort_user_direction_few"),
                                label = { Text("件数少ない順") },
                            )
                            FilterChip(
                                selected = sort.userDescending,
                                onClick = { sort = sort.copy(userDescending = true) },
                                enabled = sort.userEnabled,
                                modifier = Modifier.testTag("sort_user_direction_many"),
                                label = { Text("件数多い順") },
                            )
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("優先順位")
                                Text("タグ順とユーザー順を両方ONにした時の順番", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val enabled = sort.tagEnabled && sort.userEnabled
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = sort.priority == ClassifiedSortPriority.TagFirst,
                                    onClick = { sort = sort.copy(priority = ClassifiedSortPriority.TagFirst) },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("sort_priority_tag"),
                                    label = { Text("タグ優先") },
                                )
                                FilterChip(
                                    selected = sort.priority == ClassifiedSortPriority.UserFirst,
                                    onClick = { sort = sort.copy(priority = ClassifiedSortPriority.UserFirst) },
                                    enabled = enabled,
                                    modifier = Modifier.testTag("sort_priority_user"),
                                    label = { Text("ユーザー優先") },
                                )
                            }
                        }
                    }
                    item { Divider() }
                    item {
                        Text("基本順序", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = sort.baseOrder == ClassifiedSortBase.Default,
                                onClick = { sort = sort.copy(baseOrder = ClassifiedSortBase.Default) },
                                modifier = Modifier.testTag("sort_base_saved"),
                                label = { Text("保存順") },
                            )
                            FilterChip(
                                selected = sort.baseOrder == ClassifiedSortBase.LikeCount,
                                onClick = { sort = sort.copy(baseOrder = ClassifiedSortBase.LikeCount) },
                                modifier = Modifier.testTag("sort_base_like"),
                                label = { Text("いいね順") },
                            )
                            FilterChip(
                                selected = sort.baseOrder == ClassifiedSortBase.PostTime,
                                onClick = { sort = sort.copy(baseOrder = ClassifiedSortBase.PostTime) },
                                modifier = Modifier.testTag("sort_base_date"),
                                label = { Text("投稿時間順") },
                            )
                        }
                    }
                    if (sort.baseOrder == ClassifiedSortBase.LikeCount) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !sort.likeCountDescending,
                                    onClick = { sort = sort.copy(likeCountDescending = false) },
                                    modifier = Modifier.testTag("sort_like_direction_low"),
                                    label = { Text("少ない順") },
                                )
                                FilterChip(
                                    selected = sort.likeCountDescending,
                                    onClick = { sort = sort.copy(likeCountDescending = true) },
                                    modifier = Modifier.testTag("sort_like_direction_high"),
                                    label = { Text("多い順") },
                                )
                            }
                        }
                    }
                    if (sort.baseOrder == ClassifiedSortBase.PostTime) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !sort.postTimeDescending,
                                    onClick = { sort = sort.copy(postTimeDescending = false) },
                                    modifier = Modifier.testTag("sort_time_direction_old"),
                                    label = { Text("古い順") },
                                )
                                FilterChip(
                                    selected = sort.postTimeDescending,
                                    onClick = { sort = sort.copy(postTimeDescending = true) },
                                    modifier = Modifier.testTag("sort_time_direction_new"),
                                    label = { Text("新しい順") },
                                )
                            }
                        }
                    }
                }
                Divider()
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f).testTag("sort_cancel")) { Text("キャンセル") }
                    Button(
                        onClick = {
                            onApply(sort)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f).testTag("sort_apply"),
                    ) { Text("適用") }
                }
            }
        }
    }
}

@Composable
private fun AuthorFilterDialog(
    authors: List<TweetAuthorOption>,
    selectedAuthors: Set<TweetAuthorKey>,
    onToggle: (TweetAuthorKey) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sortByCount by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val filtered = remember(authors, query, sortByCount) {
        val clean = query.trim()
        val matching = if (clean.isBlank()) authors else authors.filter {
            it.displayName.contains(clean, ignoreCase = true) || it.username.contains(clean, ignoreCase = true)
        }
        if (sortByCount) matching.withIndex().sortedWith(compareByDescending<IndexedValue<TweetAuthorOption>> { it.value.count }.thenBy { it.index }).map { it.value } else matching
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ユーザーを選択") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) { Icon(Icons.Filled.Sort, contentDescription = "並び替え") }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("現在の順序") },
                                leadingIcon = { if (!sortByCount) Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = { sortByCount = false; sortMenuOpen = false },
                            )
                            DropdownMenuItem(
                                text = { Text("件数順") },
                                leadingIcon = { if (sortByCount) Icon(Icons.Filled.Check, contentDescription = null) },
                                onClick = { sortByCount = true; sortMenuOpen = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("表示名/@ユーザー名を検索") },
                )
                Box(Modifier.heightIn(max = 420.dp)) {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(filtered, key = { "${it.key.authorId}:${it.key.username}" }) { author ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("filter_author_option_${author.key.authorId ?: "none"}_${author.key.username}")
                                    .clickable { onToggle(author.key) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = author.key in selectedAuthors, onCheckedChange = { onToggle(author.key) })
                                Column(Modifier.weight(1f)) {
                                    Text(author.displayName, fontWeight = FontWeight.SemiBold)
                                    Text("@${author.username} / ${author.count}件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    LazyListScrollbar(listState)
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss, modifier = Modifier.testTag("filter_author_confirm")) { Text("決定") } },
        dismissButton = { TextButton(onClick = onClear, modifier = Modifier.testTag("filter_author_clear")) { Text("クリア") } },
    )
}

private fun tagFilterColor(state: TagFilterState): Color = when (state) {
    TagFilterState.NONE -> Color.Transparent
    TagFilterState.INCLUDED -> Color(0xFF2E7D32)
    TagFilterState.REQUIRED -> Color(0xFF1565C0)
    TagFilterState.EXCLUDED -> Color(0xFFE65100)
}

private fun LocalDate.toPickerMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateFromPicker(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun TagHierarchyChip(
    node: TagTreeNode,
    hierarchy: TagHierarchy,
    selectedTagIds: Set<Long>,
    mixedTagIds: Set<Long> = emptySet(),
    onToggleTag: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    fillMaxWidth: Boolean = false,
) {
    val modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    val shape = RoundedCornerShape(8.dp)
    if (node is TagGroupNode) {
        val selectedCount = hierarchy.descendantTagIdsByGroup[node.id].orEmpty().count { it in selectedTagIds || it in mixedTagIds }
        val spec = tagColorSpec(node.group.colorId)
        Surface(
            modifier = modifier
                .testTag("tag_group_chip_${node.id}")
                .heightIn(min = 32.dp)
                .then(if (selectedCount > 0) Modifier.background(tagGradient(node.group.colorId), shape) else Modifier)
                .clip(shape)
                .clickable { onOpenGroup(node.id) },
            shape = shape,
            color = Color.Transparent,
            contentColor = if (selectedCount > 0) spec.selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, tagGradient(node.group.colorId)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (selectedCount > 0) spec.selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    node.name,
                    style = if (node.name.length > 14) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                )
                if (selectedCount > 0) {
                    Badge { Text("$selectedCount") }
                }
            }
        }
    } else {
        val tag = (node as TagLeafNode).tag
        val selected = tag.id in selectedTagIds
        val mixed = tag.id in mixedTagIds
        val spec = tagColorSpec(tag.colorId)
        Surface(
            modifier = modifier
                .testTag("tag_chip_${tag.id}")
                .semantics { if (mixed) stateDescription = "一部付与" }
                .heightIn(min = 32.dp)
                .then(if (selected) Modifier.background(tagGradient(tag.colorId), shape) else if (mixed) Modifier.background(
                    tagColor(tag.colorId).copy(alpha = 0.35f), shape,
                ) else Modifier)
                .clip(shape)
                .clickable { onToggleTag(tag.id) },
            shape = shape,
            color = Color.Transparent,
            contentColor = if (selected) spec.selectedContentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, tagGradient(tag.colorId)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(tagGradient(tag.colorId)),
                )
                Text(
                    node.name,
                    style = if (node.name.length > 14) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun TagManagementRow(
    modifier: Modifier = Modifier,
    row: VisibleTagRow,
    hierarchy: TagHierarchy,
    expanded: MutableMap<Long, Boolean>,
    isGroupDropTarget: Boolean,
    onBounds: (Rect) -> Unit,
    onToggleExpanded: () -> Unit,
    onMove: (TagNodeRef, Long?) -> Unit,
    onCreate: (TagNodeType, Long?) -> Unit,
    onRenameTag: (TagEntity, String, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var renameOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var deleteOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var addAllOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var createMenuOpen by remember(row.node.ref()) { mutableStateOf(false) }
    val rowColor = when {
        isGroupDropTarget -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Column(modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = rowColor),
            modifier = Modifier
                .testTag("tag_row_${row.node.ref().type.name.lowercase()}_${row.node.id}")
                .padding(start = (row.depth * 14).dp)
                .fillMaxWidth()
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .graphicsLayer { },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (row.node is TagGroupNode) {
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier.testTag("tag_expand_group_${row.node.id}").size(32.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = "展開",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).graphicsLayer(rotationZ = if (expanded[row.node.id] == true) 90f else 0f),
                        )
                    }
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = if (expanded[row.node.id] == true) tagColor(row.node.group.colorId) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                } else {
                    val tag = (row.node as TagLeafNode).tag
                    Icon(
                        Icons.Filled.LocalOffer,
                        contentDescription = null,
                        tint = tagColor(tag.colorId),
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.node.name,
                            fontWeight = FontWeight.SemiBold,
                            style = if (row.node.name.length > 16) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${row.node.count}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (row.node is TagGroupNode) "グループ" else "タグ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (row.node is TagGroupNode) {
                        Box {
                            IconButton(
                                onClick = { createMenuOpen = true },
                                modifier = Modifier.size(32.dp).testTag("tag_add_open_${row.node.id}"),
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = "子要素を追加",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            DropdownMenu(
                                expanded = createMenuOpen,
                                onDismissRequest = { createMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("グループを追加") },
                                    onClick = {
                                        createMenuOpen = false
                                        onCreate(TagNodeType.GROUP, row.node.id)
                                    },
                                    modifier = Modifier.testTag("tag_add_create_group_${row.node.id}"),
                                )
                                DropdownMenuItem(
                                    text = { Text("タグを追加") },
                                    onClick = {
                                        createMenuOpen = false
                                        onCreate(TagNodeType.TAG, row.node.id)
                                    },
                                    modifier = Modifier.testTag("tag_add_create_tag_${row.node.id}"),
                                )
                            }
                        }
                    } else {
                        IconButton(
                            onClick = { addAllOpen = true },
                            modifier = Modifier.size(32.dp).testTag("tag_add_all_open_${row.node.id}"),
                        ) {
                            Icon(
                                Icons.Outlined.Input,
                                contentDescription = "別タグへ一括追加",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { renameOpen = true },
                        modifier = Modifier.size(32.dp).testTag("tag_rename_open_${row.node.ref().type.name.lowercase()}_${row.node.id}"),
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "編集",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    IconButton(
                        onClick = { deleteOpen = true },
                        modifier = Modifier.size(32.dp).testTag("tag_delete_open_${row.node.ref().type.name.lowercase()}_${row.node.id}"),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "削除",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
    if (renameOpen) {
        RenameNodeDialog(
            row.node.name,
            initialColorId = if (row.node is TagGroupNode) row.node.group.colorId else (row.node as TagLeafNode).tag.colorId,
            onDismiss = { renameOpen = false },
        ) { name, colorId ->
            when (row.node) {
                is TagGroupNode -> onRenameGroup(row.node.group, name, colorId)
                is TagLeafNode -> onRenameTag(row.node.tag, name, colorId)
            }
            renameOpen = false
        }
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(if (row.node is TagGroupNode) "グループを削除" else "タグを削除") },
            text = {
                Text(
                    if (row.node is TagGroupNode) "空のグループ「${row.node.name}」を削除します。" else "「${row.node.name}」の割り当ても外れます。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (row.node is TagGroupNode) onDeleteGroup(row.node.group) else onDeleteTag((row.node as TagLeafNode).tag)
                        deleteOpen = false
                    },
                    modifier = Modifier.testTag("tag_delete_confirm_${row.node.ref().type.name.lowercase()}_${row.node.id}"),
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteOpen = false },
                    modifier = Modifier.testTag("tag_delete_cancel_${row.node.ref().type.name.lowercase()}_${row.node.id}"),
                ) { Text("閉じる") }
            },
        )
    }
    if (addAllOpen && row.node is TagLeafNode) {
        AddAllTagsDialog(
            source = row.node.tag,
            targets = hierarchy.tags.map { it.tag }.filter { it.id != row.node.id },
            onDismiss = { addAllOpen = false },
            onAddAll = { onAddAll(row.node.tag, it); addAllOpen = false },
        )
    }
}

@Composable
private fun TagPlaceholderSpacer(
    item: TagListItem.Placeholder,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Spacer(
        modifier
            .padding(start = (item.depth * 14).dp)
            .fillMaxWidth()
            .height(with(density) { item.heightPx.toDp() }),
    )
}

@Composable
private fun TagDragPreview(state: DragState, dragLayerBounds: Rect?) {
    val density = LocalDensity.current
    val layerLeft = dragLayerBounds?.left ?: 0f
    val layerTop = dragLayerBounds?.top ?: 0f
    val offset = IntOffset(
        (state.itemLeftX - layerLeft).roundToInt(),
        (state.pointerYInRoot - state.grabOffsetY - layerTop).roundToInt(),
    )
    Box(
        Modifier
            .offset { offset }
            .width(with(density) { state.itemWidth.toDp() })
            .height(with(density) { state.itemHeight.toDp() })
            .graphicsLayer(alpha = 0.82f)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (state.isGroup) Icons.Filled.Folder else Icons.Filled.LocalOffer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(state.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun TagHierarchy.visibleRows(expandedGroups: Set<Long>): List<VisibleTagRow> = buildList {
    fun visit(parentGroupId: Long?, depth: Int) {
        children(parentGroupId).forEachIndexed { index, node ->
            add(VisibleTagRow(node = node, depth = depth, parentGroupId = parentGroupId, indexInParent = index))
            if (node is TagGroupNode && node.id in expandedGroups) {
                visit(node.id, depth + 1)
            }
        }
    }
    visit(null, 0)
}

private fun TagHierarchy.nodeFor(ref: TagNodeRef): TagTreeNode? = when (ref.type) {
    TagNodeType.GROUP -> groups.firstOrNull { it.id == ref.id }?.let { TagGroupNode(it, groupCounts[it.id] ?: 0) }
    TagNodeType.TAG -> tags.firstOrNull { it.tag.id == ref.id }?.let { TagLeafNode(it.tag, it.count) }
}

private fun TagNodeRef.saveableKey(): String = "${type.name}:$id"

internal fun buildTagListItems(
    visibleRows: List<VisibleTagRow>,
    dragState: DragState?,
): List<TagListItem> {
    if (dragState == null) {
        val counts = mutableMapOf<Long?, Int>()
        return visibleRows.map { row ->
            val index = counts.getOrDefault(row.parentGroupId, 0)
            counts[row.parentGroupId] = index + 1
            TagListItem.Row(row, index)
        }
    }

    if (visibleRows.none { it.node.ref() == dragState.node }) return buildTagListItems(visibleRows, null)
    val rowsWithoutDragged = visibleRows.withoutDraggedSubtree(dragState.node)
    val counts = mutableMapOf<Long?, Int>()
    val items = rowsWithoutDragged.map { row ->
        val index = counts.getOrDefault(row.parentGroupId, 0)
        counts[row.parentGroupId] = index + 1
        TagListItem.Row(row, index)
    }.toMutableList<TagListItem>()
    val visualParentId = dragState.visualParentId
    val parentCount = counts.getOrDefault(visualParentId, 0)
    val visualIndex = dragState.visualPlaceholderIndex.coerceIn(0, parentCount)
    val placeholder = TagListItem.Placeholder(
        parentGroupId = visualParentId,
        index = visualIndex,
        depth = placeholderDepth(rowsWithoutDragged, visualParentId),
        heightPx = dragState.itemHeight,
    )
    items.add(placeholderInsertIndex(items, visualParentId, visualIndex), placeholder)
    return items
}

private fun List<VisibleTagRow>.withoutDraggedSubtree(node: TagNodeRef): List<VisibleTagRow> {
    val start = indexOfFirst { it.node.ref() == node }
    if (start < 0) return this
    val draggedDepth = this[start].depth
    var endExclusive = start + 1
    while (endExclusive < size && this[endExclusive].depth > draggedDepth) {
        endExclusive += 1
    }
    return filterIndexed { index, _ -> index !in start until endExclusive }
}

private fun placeholderDepth(rows: List<VisibleTagRow>, parentGroupId: Long?): Int {
    if (parentGroupId == null) return 0
    return rows.firstOrNull { it.node.ref() == TagNodeRef(TagNodeType.GROUP, parentGroupId) }?.let { it.depth + 1 } ?: 0
}

private fun placeholderInsertIndex(
    items: List<TagListItem>,
    parentGroupId: Long?,
    placeholderIndex: Int,
): Int {
    val rows = items.filterIsInstance<TagListItem.Row>()
    rows.firstOrNull {
        it.row.parentGroupId == parentGroupId && it.indexInParentWithoutDragged >= placeholderIndex
    }?.let { return items.indexOf(it) }

    val previous = rows.lastOrNull {
        it.row.parentGroupId == parentGroupId && it.indexInParentWithoutDragged < placeholderIndex
    }
    if (previous != null) {
        var insertIndex = items.indexOf(previous) + 1
        while (insertIndex < items.size) {
            val nextItem = items[insertIndex] as? TagListItem.Row ?: break
            val nextRow = nextItem.row
            if (nextRow.depth <= previous.row.depth) break
            insertIndex += 1
        }
        return insertIndex
    }

    if (parentGroupId != null) {
        val parent = rows.firstOrNull { it.row.node.ref() == TagNodeRef(TagNodeType.GROUP, parentGroupId) }
        if (parent != null) return items.indexOf(parent) + 1
    }
    return items.size
}

private data class DragStartCandidate(
    val item: TagListItem.Row,
    val bounds: Rect,
)

private fun dragStartCandidate(
    pointerRoot: Offset,
    displayItems: List<TagListItem>,
    rowBounds: Map<TagNodeRef, Rect>,
): DragStartCandidate? =
    displayItems.filterIsInstance<TagListItem.Row>()
        .firstNotNullOfOrNull { item ->
            val bounds = rowBounds[item.row.node.ref()] ?: return@firstNotNullOfOrNull null
            if (pointerRoot.x in bounds.left..bounds.right && pointerRoot.y in bounds.top..bounds.bottom) {
                DragStartCandidate(item, bounds)
            } else {
                null
            }
        }

private fun isMoveReflected(visibleRows: List<VisibleTagRow>, state: DragState): Boolean {
    val row = visibleRows.firstOrNull { it.node.ref() == state.node } ?: return false
    if (row.parentGroupId != state.targetParentId) return false
    val index = visibleRows.count {
        it.parentGroupId == state.targetParentId &&
            it.indexInParent < row.indexInParent &&
            it.node.ref() != state.node
    }
    return index == state.placeholderIndex
}

internal fun updateDragStateAfterMove(
    state: DragState,
    visibleRows: List<VisibleTagRow>,
    displayItems: List<TagListItem>,
    rowBounds: Map<TagNodeRef, Rect>,
    hierarchy: TagHierarchy,
    listBounds: Rect?,
    allowStaticCrossing: Boolean,
): DragState {
    if (state.reorderLocked) return state
    outOfBoundsSlot(state, visibleRows, listBounds)?.let { slot ->
        return state.withUpdatedCenter().copy(
            targetParentId = slot.parentGroupId,
            placeholderIndex = slot.index,
            visualParentId = slot.parentGroupId,
            visualPlaceholderIndex = slot.index,
            targetIsGroupDrop = false,
            reorderLocked = slot.index != state.placeholderIndex,
        )
    }
    groupDropTargetUnderPointer(state, displayItems, rowBounds, hierarchy)?.let { groupId ->
        val count = destinationCountWithoutDragged(hierarchy, groupId, state.node)
        return state.withUpdatedCenter().copy(
            targetParentId = groupId,
            placeholderIndex = count,
            targetIsGroupDrop = true,
        )
    }

    val directSlot = slotUnderPointer(state, displayItems, rowBounds)
    if (directSlot != null && directSlot.parentGroupId != state.targetParentId) {
        return state.withUpdatedCenter().copy(
            targetParentId = directSlot.parentGroupId,
            placeholderIndex = directSlot.index,
            visualParentId = directSlot.parentGroupId,
            visualPlaceholderIndex = directSlot.index,
            targetIsGroupDrop = false,
            reorderLocked = true,
        )
    }

    return movePlaceholderByNeighborCenter(state, visibleRows, displayItems, rowBounds, allowStaticCrossing)
}

private data class DropSlot(val parentGroupId: Long?, val index: Int)

private fun outOfBoundsSlot(
    state: DragState,
    visibleRows: List<VisibleTagRow>,
    listBounds: Rect?,
): DropSlot? {
    if (listBounds == null) return null
    val parent = state.targetParentId
    val maxIndex = visibleRows.withoutDraggedSubtree(state.node).count { it.parentGroupId == parent }
    return when {
        state.pointerYInRoot < listBounds.top -> DropSlot(parent, 0)
        state.pointerYInRoot > listBounds.bottom -> DropSlot(parent, maxIndex)
        else -> null
    }
}

private fun groupDropTargetUnderPointer(
    state: DragState,
    displayItems: List<TagListItem>,
    rowBounds: Map<TagNodeRef, Rect>,
    hierarchy: TagHierarchy,
): Long? {
    val dragCenterY = state.dragCenterY()
    return displayItems.filterIsInstance<TagListItem.Row>().firstNotNullOfOrNull { item ->
        val row = item.row
        val group = row.node as? TagGroupNode ?: return@firstNotNullOfOrNull null
        val bounds = rowBounds[row.node.ref()] ?: return@firstNotNullOfOrNull null
        if (dragCenterY !in bounds.top..bounds.bottom) return@firstNotNullOfOrNull null
        val edgeZone = minOf(22f, bounds.height * 0.28f)
        val inCenter = dragCenterY in (bounds.top + edgeZone)..(bounds.bottom - edgeZone)
        if (inCenter && canDropIntoGroup(state, group.id, hierarchy)) group.id else null
    }
}

private fun slotUnderPointer(
    state: DragState,
    displayItems: List<TagListItem>,
    rowBounds: Map<TagNodeRef, Rect>,
): DropSlot? {
    val dragCenterY = state.dragCenterY()
    return displayItems.filterIsInstance<TagListItem.Row>().firstNotNullOfOrNull { item ->
        val bounds = rowBounds[item.row.node.ref()] ?: return@firstNotNullOfOrNull null
        if (dragCenterY !in bounds.top..bounds.bottom) return@firstNotNullOfOrNull null
        val index = if (dragCenterY < bounds.center.y) {
            item.indexInParentWithoutDragged
        } else {
            item.indexInParentWithoutDragged + 1
        }
        DropSlot(item.row.parentGroupId, index)
    }
}

private fun movePlaceholderByNeighborCenter(
    state: DragState,
    visibleRows: List<VisibleTagRow>,
    displayItems: List<TagListItem>,
    rowBounds: Map<TagNodeRef, Rect>,
    allowStaticCrossing: Boolean,
): DragState {
    val rowsInParent = displayItems.filterIsInstance<TagListItem.Row>()
        .filter { it.row.parentGroupId == state.targetParentId }
    val dragCenterY = state.pointerYInRoot - state.grabOffsetY + state.itemHeight / 2
    val previousDragCenterY = state.lastDragCenterY
    val upper = rowsInParent.firstOrNull { it.indexInParentWithoutDragged == state.placeholderIndex - 1 }
    val lower = rowsInParent.firstOrNull { it.indexInParentWithoutDragged == state.placeholderIndex }
    val maxIndex = visibleRows.withoutDraggedSubtree(state.node).count { it.parentGroupId == state.targetParentId }
    val lowerCenter = lower?.let { rowBounds[it.row.node.ref()]?.center?.y }
    val upperCenter = upper?.let { rowBounds[it.row.node.ref()]?.center?.y }
    return when {
        lowerCenter != null && (previousDragCenterY <= lowerCenter || allowStaticCrossing) && dragCenterY > lowerCenter -> {
            state.copy(
                placeholderIndex = (state.placeholderIndex + 1).coerceAtMost(maxIndex),
                visualParentId = state.targetParentId,
                visualPlaceholderIndex = (state.placeholderIndex + 1).coerceAtMost(maxIndex),
                lastDragCenterY = dragCenterY,
                targetIsGroupDrop = false,
                reorderLocked = true,
            )
        }
        upperCenter != null && (previousDragCenterY >= upperCenter || allowStaticCrossing) && dragCenterY < upperCenter -> {
            state.copy(
                placeholderIndex = (state.placeholderIndex - 1).coerceAtLeast(0),
                visualParentId = state.targetParentId,
                visualPlaceholderIndex = (state.placeholderIndex - 1).coerceAtLeast(0),
                lastDragCenterY = dragCenterY,
                targetIsGroupDrop = false,
                reorderLocked = true,
            )
        }
        else -> state.withUpdatedCenter()
    }
}

private fun DragState.dragCenterY(): Float = pointerYInRoot - grabOffsetY + itemHeight / 2

private fun DragState.withUpdatedCenter(): DragState = copy(lastDragCenterY = dragCenterY())

private fun destinationCountWithoutDragged(hierarchy: TagHierarchy, parentGroupId: Long?, draggedNode: TagNodeRef): Int =
    hierarchy.children(parentGroupId).count { it.ref() != draggedNode }

private fun canDropIntoGroup(state: DragState, groupId: Long, hierarchy: TagHierarchy): Boolean {
    if (!state.isGroup) return true
    if (state.node.id == groupId) return false
    val groupsById = hierarchy.groups.associateBy { it.id }
    var current: Long? = groupId
    while (current != null) {
        if (current == state.node.id) return false
        current = groupsById[current]?.parentGroupId
    }
    return true
}

internal fun calculateAutoScrollDelta(
    pointerY: Float,
    listBounds: Rect,
    canScrollBackward: Boolean = true,
    canScrollForward: Boolean = true,
): Float {
    val edgeSize = 96f
    val maxScroll = 34f
    return when {
        pointerY < listBounds.top + edgeSize && canScrollBackward -> {
            val distance = (listBounds.top + edgeSize - pointerY).coerceAtLeast(0f)
            -(8f + (distance / edgeSize).coerceAtMost(1.8f) * maxScroll)
        }
        pointerY > listBounds.bottom - edgeSize && canScrollForward -> {
            val distance = (pointerY - (listBounds.bottom - edgeSize)).coerceAtLeast(0f)
            8f + (distance / edgeSize).coerceAtMost(1.8f) * maxScroll
        }
        else -> 0f
    }
}

private fun applyDrop(
    state: DragState,
    hierarchy: TagHierarchy,
    onMoveToIndex: (TagNodeRef, Long?, Int) -> Unit,
) {
    if (state.isGroup && state.targetParentId != null && !canDropIntoGroup(state, state.targetParentId, hierarchy)) return
    val maxIndex = destinationCountWithoutDragged(hierarchy, state.targetParentId, state.node)
    val index = state.placeholderIndex
    if (index !in 0..maxIndex) return
    if (state.sourceParentId == state.targetParentId && state.sourceIndexInParent == index) return
    onMoveToIndex(state.node, state.targetParentId, index)
}

@Composable
private fun HierarchyEmptyState(text: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PreserveScrollAnchor(
    listState: LazyListState,
    stateKey: String,
    itemKeys: List<Any>,
) {
    var anchor by remember(stateKey) { mutableStateOf(ScrollAnchor(null, 0, 0)) }
    LaunchedEffect(listState, stateKey) {
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            first?.let { ScrollAnchor(it.key, it.index, listState.firstVisibleItemScrollOffset) }
        }.collect { next ->
            if (next != null) anchor = next
        }
    }
    LaunchedEffect(stateKey, itemKeys) {
        if (itemKeys.isEmpty()) return@LaunchedEffect
        val targetIndex = anchor.stableId?.let { itemKeys.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?: anchor.index.coerceIn(0, itemKeys.lastIndex)
        if (targetIndex != listState.firstVisibleItemIndex) {
            listState.scrollToItem(targetIndex, anchor.offset)
        }
    }
}

@Composable
private fun BoxScope.LazyListScrollbar(listState: LazyListState) {
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    if (totalItems <= 0 || visibleItems.isEmpty() || viewportHeight <= 0) return
    if (!listState.canScrollBackward && !listState.canScrollForward) return

    val visibleCount = visibleItems.size.coerceAtLeast(1)
    val maxThumbHeightPx = viewportHeight.toFloat()
    val minThumbHeightPx = minOf(24f, maxThumbHeightPx)
    val thumbHeightPx = (viewportHeight * visibleCount.toFloat() / totalItems)
        .coerceIn(minThumbHeightPx, maxThumbHeightPx)
    val scrollableItems = (totalItems - visibleCount).coerceAtLeast(1)
    val firstIndex = listState.firstVisibleItemIndex.coerceIn(0, scrollableItems)
    val thumbOffsetPx = ((viewportHeight - thumbHeightPx) * firstIndex / scrollableItems)
        .coerceIn(0f, viewportHeight - thumbHeightPx)
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)

    Box(
        Modifier
            .fillMaxHeight()
            .width(6.dp)
            .padding(end = 1.dp)
            .align(Alignment.CenterEnd),
    ) {
        Box(
            Modifier
                .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                .align(Alignment.TopCenter)
                .width(3.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

@Composable
private fun BoxScope.ScrollToTopButton(listState: LazyListState, hasItems: Boolean) {
    if (!hasItems || listState.firstVisibleItemIndex < 2) return
    val scope = rememberCoroutineScope()
    FloatingActionButton(
        onClick = { scope.launch { listState.scrollToItem(0) } },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(16.dp)
            .size(48.dp)
            .testTag("scroll_to_top"),
        shape = CircleShape,
        containerColor = Color.White,
    ) {
        Text("↑", color = Color.Black, fontWeight = FontWeight.Bold)
    }
}

internal fun formatLikeCount(count: Long): String = when {
    count < 10_000 -> String.format(Locale.JAPAN, "%,d", count)
    else -> {
        val tenths = count / 1_000
        if (tenths % 10L == 0L) "${tenths / 10}万" else "${tenths / 10}.${tenths % 10}万"
    }
}

private fun ClipEntity.hasProvisionalLikeCount(): Boolean {
    if (likeCount == null || likeCountFetchedAt == null) return false
    val created = runCatching { Instant.parse(xCreatedAt) }.getOrNull() ?: return false
    val fetched = runCatching { Instant.parse(likeCountFetchedAt) }.getOrNull() ?: return false
    return fetched.isBefore(created.plusSeconds(7L * 24 * 60 * 60))
}

private fun formatLikeFetchedAt(value: String?): String {
    val instant = value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return "不明"
    return DateTimeFormatter.ofPattern("yyyy/M/d H:mm").format(instant.atZone(ZoneId.systemDefault()))
}

internal fun String.withoutTrailingMediaUrl(hasAssets: Boolean): String {
    if (!hasAssets) return this
    return replace(Regex("""(?:\s+https://t\.co/[A-Za-z0-9_]+)+\s*$"""), "").trimEnd()
}

@Composable
fun EnhancedMediaGrid(assets: List<AssetEntity>) {
    val shown = assets.mapNotNull { asset ->
        val url = asset.localPath ?: asset.previewUrl ?: asset.remoteUrl
        url?.let { DisplayAsset(asset, it) }
    }.take(4)
    val savedPhotos = shown.filter { it.asset.type == "photo" && it.asset.localPath != null }
    var initialViewerPage by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (shown.size) {
            1 -> EnhancedMediaCell(
                displayAsset = shown[0],
                viewerIndex = savedPhotos.viewerIndexFor(shown[0]),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(shown[0].asset.displayAspectRatio()),
                onOpenViewer = { initialViewerPage = it },
            )
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEach {
                    EnhancedMediaCell(
                        displayAsset = it,
                        viewerIndex = savedPhotos.viewerIndexFor(it),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(1f).aspectRatio(1f),
                        onOpenViewer = { index -> initialViewerPage = index },
                    )
                }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.take(2).forEach {
                        EnhancedMediaCell(
                            displayAsset = it,
                            viewerIndex = savedPhotos.viewerIndexFor(it),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            onOpenViewer = { index -> initialViewerPage = index },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.drop(2).forEach {
                        EnhancedMediaCell(
                            displayAsset = it,
                            viewerIndex = savedPhotos.viewerIndexFor(it),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            onOpenViewer = { index -> initialViewerPage = index },
                        )
                    }
                    if (shown.size == 3) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    initialViewerPage?.let { initialPage ->
        if (savedPhotos.isNotEmpty()) {
            FullScreenImageViewer(
                photos = savedPhotos,
                initialPage = initialPage.coerceIn(0, savedPhotos.lastIndex),
                onDismiss = { initialViewerPage = null },
            )
        }
    }
}

data class MediaGridEntry(
    val entryId: Long,
    val clipId: Long,
    val assetId: Long,
    val mediaKey: String,
    val mediaIndex: Int,
    val type: String,
    val displayUrl: String?,
    val downloadState: String,
    val localPath: String?,
    val xCreatedAt: String,
    val likeCount: Long?,
    val previewUrl: String? = null,
    val remoteUrl: String? = null,
)

internal data class MediaGridBuildResult(
    val entries: List<MediaGridEntry>,
    val matchingMediaCount: Int,
    val hasMedia: Boolean,
)

internal fun buildMediaGridResult(clips: List<MediaGridClipSource>): MediaGridBuildResult {
    val total = clips.sumOf { it.mediaAssetCount }
    val result = ArrayList<MediaGridEntry>(total)
    var matchingMediaCount = 0
    clips.forEach { clip ->
        var index = 0
        clip.assets.forEach { asset ->
            if (asset.assetType == "photo" || asset.assetType == "video_thumbnail") {
                matchingMediaCount++
                val displayUrl = asset.localPath ?: asset.previewUrl ?: asset.remoteUrl
                result += MediaGridEntry(
                    entryId = asset.assetId,
                    clipId = clip.clip.id,
                    assetId = asset.assetId,
                    mediaKey = asset.mediaKey,
                    mediaIndex = index,
                    type = asset.assetType,
                    displayUrl = displayUrl,
                    previewUrl = asset.previewUrl,
                    remoteUrl = asset.remoteUrl,
                    downloadState = asset.downloadState,
                    localPath = asset.localPath,
                    xCreatedAt = clip.clip.xCreatedAt,
                    likeCount = clip.clip.likeCount,
                )
                index++
            }
        }
    }
    return MediaGridBuildResult(result, matchingMediaCount, matchingMediaCount > 0)
}

internal fun buildMediaGridEntries(clips: List<MediaGridClipSource>): List<MediaGridEntry> {
    return buildMediaGridResult(clips).entries
}

internal sealed interface ClassifiedMediaGridItem {
    val key: String
}

internal data class MediaGridHeaderItem(
    override val key: String,
    val label: String,
    val safeKey: String,
) : ClassifiedMediaGridItem

internal data class MediaGridCellItem(
    override val key: String,
    val entry: MediaGridEntry,
    val sourceIndex: Int,
) : ClassifiedMediaGridItem

internal data class MediaGridRenderKey(
    val dataKey: MediaGridDataKey,
    val columnCount: Int,
)

internal data class MediaGridOrdinalIndex(
    val assetIdByMediaOrdinal: LongArray,
    val itemIndexByMediaOrdinal: IntArray,
    val mediaOrdinalByItemIndex: IntArray,
    val mediaOrdinalByAssetId: Map<Long, Int>,
    val itemIndexByAssetId: Map<Long, Int>,
)

internal data class MediaGridFrameData(
    val key: MediaGridRenderKey,
    val items: List<ClassifiedMediaGridItem>,
    val itemByKey: Map<String, ClassifiedMediaGridItem>,
    val mediaCellIndices: IntArray,
    val assetIdByItemKey: Map<String, Long> = emptyMap(),
    val ordinalIndex: MediaGridOrdinalIndex = MediaGridOrdinalIndex(
        LongArray(0), IntArray(0), IntArray(0), emptyMap(), emptyMap(),
    ),
)

internal data class MediaGridViewportSignature(
    val renderKey: MediaGridRenderKey,
    val firstVisibleItemIndex: Int,
    val lastVisibleItemIndex: Int,
    val firstVisibleMediaOrdinal: Int,
    val lastVisibleMediaOrdinal: Int,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val cellSizePx: Int,
    val columnCount: Int,
    val visibleItemGeometry: List<MediaGridViewportItemGeometry> = emptyList(),
)

internal data class MediaGridViewportItemGeometry(
    val key: String,
    val offsetX: Int,
    val offsetY: Int,
    val width: Int,
    val height: Int,
)

internal fun buildMediaGridViewportSignature(
    layout: androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo,
    frame: MediaGridFrameData,
    columnCount: Int,
): MediaGridViewportSignature {
    var firstItemIndex = Int.MAX_VALUE
    var lastItemIndex = Int.MIN_VALUE
    var firstMediaOrdinal = Int.MAX_VALUE
    var lastMediaOrdinal = Int.MIN_VALUE
    var cellSizePx = 0
    val visibleItemGeometry = ArrayList<MediaGridViewportItemGeometry>(layout.visibleItemsInfo.size)
    val mediaOrdinalByItemIndex = frame.ordinalIndex.mediaOrdinalByItemIndex
    for (info in layout.visibleItemsInfo) {
        val itemIndex = info.index
        if (itemIndex < firstItemIndex) firstItemIndex = itemIndex
        if (itemIndex > lastItemIndex) lastItemIndex = itemIndex
        val mediaOrdinal = mediaOrdinalByItemIndex.getOrNull(itemIndex) ?: -1
        if (mediaOrdinal >= 0) {
            if (mediaOrdinal < firstMediaOrdinal) firstMediaOrdinal = mediaOrdinal
            if (mediaOrdinal > lastMediaOrdinal) lastMediaOrdinal = mediaOrdinal
            if (cellSizePx == 0) cellSizePx = info.size.width
        }
        visibleItemGeometry += MediaGridViewportItemGeometry(
            key = info.key.toString(),
            offsetX = info.offset.x,
            offsetY = info.offset.y,
            width = info.size.width,
            height = info.size.height,
        )
    }
    return MediaGridViewportSignature(
        renderKey = frame.key,
        firstVisibleItemIndex = if (firstItemIndex == Int.MAX_VALUE) -1 else firstItemIndex,
        lastVisibleItemIndex = if (lastItemIndex == Int.MIN_VALUE) -1 else lastItemIndex,
        firstVisibleMediaOrdinal = if (firstMediaOrdinal == Int.MAX_VALUE) -1 else firstMediaOrdinal,
        lastVisibleMediaOrdinal = if (lastMediaOrdinal == Int.MIN_VALUE) -1 else lastMediaOrdinal,
        viewportWidthPx = layout.viewportSize.width,
        viewportHeightPx = (layout.viewportEndOffset - layout.viewportStartOffset).coerceAtLeast(0),
        cellSizePx = cellSizePx,
        columnCount = columnCount,
        visibleItemGeometry = visibleItemGeometry,
    )
}

internal fun MediaGridViewportSignature.toAnchor(): MediaGridViewportAnchor = MediaGridViewportAnchor(
    renderKey = renderKey,
    firstVisibleItemIndex = firstVisibleItemIndex,
    lastVisibleItemIndex = lastVisibleItemIndex,
    firstVisibleMediaOrdinal = firstVisibleMediaOrdinal,
    lastVisibleMediaOrdinal = lastVisibleMediaOrdinal,
    viewportWidthPx = viewportWidthPx,
    viewportHeightPx = viewportHeightPx,
    cellSizePx = cellSizePx,
    columnCount = columnCount,
)

internal fun mediaGridFrameMatches(frameKey: MediaGridRenderKey?, currentKey: MediaGridRenderKey): Boolean =
    frameKey == currentKey

internal fun buildMediaGridFrameData(
    entries: List<MediaGridEntry>,
    sort: ClassifiedSortState,
    columnCount: Int,
    dataKey: MediaGridDataKey,
): MediaGridFrameData {
    val items = buildClassifiedMediaGridItems(entries, sort, columnCount)
    val itemByKey = HashMap<String, ClassifiedMediaGridItem>(items.size)
    val assetIdByItemKey = HashMap<String, Long>(entries.size)
    val mediaIndices = IntArray(entries.size)
    val assetIds = LongArray(entries.size)
    val itemIndices = IntArray(entries.size)
    val mediaOrdinalByItemIndex = IntArray(items.size) { -1 }
    val mediaOrdinalByAssetId = HashMap<Long, Int>(entries.size)
    val itemIndexByAssetId = HashMap<Long, Int>(entries.size)
    var mediaCount = 0
    items.forEachIndexed { index, item ->
        itemByKey[item.key] = item
        if (item is MediaGridCellItem) {
            mediaIndices[mediaCount] = index
            assetIds[mediaCount] = item.entry.assetId
            itemIndices[mediaCount] = index
            mediaOrdinalByItemIndex[index] = mediaCount
            assetIdByItemKey[item.key] = item.entry.assetId
            if (!mediaOrdinalByAssetId.containsKey(item.entry.assetId)) {
                mediaOrdinalByAssetId[item.entry.assetId] = mediaCount
                itemIndexByAssetId[item.entry.assetId] = index
            }
            mediaCount++
        }
    }
    val ordinalIndex = MediaGridOrdinalIndex(
        assetIdByMediaOrdinal = assetIds.copyOf(mediaCount),
        itemIndexByMediaOrdinal = itemIndices.copyOf(mediaCount),
        mediaOrdinalByItemIndex = mediaOrdinalByItemIndex,
        mediaOrdinalByAssetId = Collections.unmodifiableMap(mediaOrdinalByAssetId),
        itemIndexByAssetId = Collections.unmodifiableMap(itemIndexByAssetId),
    )
    return MediaGridFrameData(
        key = MediaGridRenderKey(dataKey, columnCount),
        items = items,
        itemByKey = itemByKey,
        mediaCellIndices = ordinalIndex.itemIndexByMediaOrdinal,
        assetIdByItemKey = Collections.unmodifiableMap(assetIdByItemKey),
        ordinalIndex = ordinalIndex,
    )
}

internal data class ClassifiedMediaGridScrollAnchor(
    val key: String,
    val index: Int,
    val offset: Int,
    val centerOffset: Float,
)

internal data class MediaGridScrollCheckpointState(val observedScrollStart: Boolean = false)

internal data class MediaGridScrollCheckpointTransition(
    val state: MediaGridScrollCheckpointState,
    val shouldCheckpoint: Boolean,
)

internal fun mediaGridScrollCheckpointTransition(
    state: MediaGridScrollCheckpointState,
    isScrollInProgress: Boolean,
): MediaGridScrollCheckpointTransition = when {
    isScrollInProgress -> MediaGridScrollCheckpointTransition(
        state = state.copy(observedScrollStart = true),
        shouldCheckpoint = false,
    )
    state.observedScrollStart -> MediaGridScrollCheckpointTransition(
        state = MediaGridScrollCheckpointState(),
        shouldCheckpoint = true,
    )
    else -> MediaGridScrollCheckpointTransition(state, shouldCheckpoint = false)
}

internal fun buildClassifiedMediaGridItems(
    entries: List<MediaGridEntry>,
    sort: ClassifiedSortState,
    columnCount: Int,
): List<ClassifiedMediaGridItem> {
    if (sort.baseOrder == ClassifiedSortBase.Default) {
        return entries.mapIndexed { sourceIndex, entry ->
            MediaGridCellItem(key = mediaGridCellKey(entry), entry = entry, sourceIndex = sourceIndex)
        }
    }

    val items = ArrayList<ClassifiedMediaGridItem>(entries.size * 2)
    var previousBucketKey: String? = null
    entries.forEachIndexed { sourceIndex, entry ->
        val bucket = mediaGridMorphBucketSpec(
            entry.xCreatedAt,
            entry.likeCount,
            sort.baseOrder,
            columnCount,
        ) ?: return@forEachIndexed
        if (bucket.key != previousBucketKey) {
            items += MediaGridHeaderItem(
                key = "media_grid_header_${bucket.safeKey}",
                label = bucket.label,
                safeKey = bucket.safeKey,
            )
            previousBucketKey = bucket.key
        }
        items += MediaGridCellItem(key = mediaGridCellKey(entry), entry = entry, sourceIndex = sourceIndex)
    }
    return items
}

internal fun classifiedMediaGridColumnCountForScale(
    currentColumnCount: Int,
    scale: Float,
): Int {
    var next = currentColumnCount.coerceIn(ClassifiedMediaGridMinColumnCount, ClassifiedMediaGridMaxColumnCount)
    if (!scale.isFinite() || scale <= 0f) return next

    return when {
        scale >= ClassifiedMediaGridPinchScaleStep ->
            (next + 1).coerceAtMost(ClassifiedMediaGridMaxColumnCount)
        scale <= 1f / ClassifiedMediaGridPinchScaleStep ->
            (next - 1).coerceAtLeast(ClassifiedMediaGridMinColumnCount)
        else -> next
    }
}

private fun mediaGridCellKey(entry: MediaGridEntry): String = "media_grid_item_${entry.assetId}"

@Composable
private fun MediaGridSelectionToolbar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onEditTags: () -> Unit,
    editTagsEnabled: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(ClassifiedMediaGridToolbarZIndex)
            .testTag("media_grid_selection_toolbar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("media_grid_selection_close")) {
                Icon(Icons.Filled.Close, contentDescription = "選択を終了")
            }
            Text(
                text = "${selectedCount}件選択中",
                modifier = Modifier.weight(1f).testTag("media_grid_selection_count"),
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onToggleAll, modifier = Modifier.testTag("media_grid_select_all")) {
                Text(if (allSelected) "全解除" else "全選択")
            }
            Button(
                onClick = onEditTags,
                enabled = editTagsEnabled,
                modifier = Modifier.testTag("media_grid_bulk_tag_open"),
            ) {
                Text("タグ編集")
            }
    }
}

internal enum class BulkTagAggregate { NONE, ALL, MIXED }
internal enum class BulkTagPending { KEEP, ADD_ALL, REMOVE_ALL }

internal fun aggregateBulkTagStates(
    clipTagIds: List<Set<Long>>,
    tagIds: List<Long>,
): Map<Long, BulkTagAggregate> {
    val total = clipTagIds.size
    return tagIds.associateWith { tagId ->
        val count = clipTagIds.count { tagId in it }
        when { count == 0 -> BulkTagAggregate.NONE; count == total -> BulkTagAggregate.ALL; else -> BulkTagAggregate.MIXED }
    }
}

internal fun bulkTagPendingAfterToggle(
    initial: BulkTagAggregate,
    current: BulkTagPending,
): BulkTagPending = when (current) {
    BulkTagPending.REMOVE_ALL -> BulkTagPending.ADD_ALL
    BulkTagPending.ADD_ALL -> BulkTagPending.REMOVE_ALL
    BulkTagPending.KEEP -> when (initial) {
        BulkTagAggregate.ALL, BulkTagAggregate.MIXED -> BulkTagPending.REMOVE_ALL
        BulkTagAggregate.NONE -> BulkTagPending.ADD_ALL
    }
}

@Composable
private fun MediaGridBulkTagDialog(
    hierarchy: TagHierarchy,
    selectedClipCount: Int,
    initialTagStates: Map<Long, BulkTagAggregate>,
    onApply: (Map<Long, BulkTagPending>, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var pending by remember(initialTagStates) { mutableStateOf(initialTagStates.mapValues { BulkTagPending.KEEP }) }
    var openGroupId by remember { mutableStateOf<Long?>(null) }
    var discardConfirmationOpen by remember { mutableStateOf(false) }
    var applyConfirmationOpen by remember { mutableStateOf(false) }
    var bulkTagDialogError by remember { mutableStateOf<String?>(null) }
    val hasPendingChanges = pending.values.any { it != BulkTagPending.KEEP }
    val effectiveSelected = initialTagStates.filter { (id, state) ->
        when (pending[id] ?: BulkTagPending.KEEP) { BulkTagPending.ADD_ALL -> true; BulkTagPending.REMOVE_ALL -> false; else -> state == BulkTagAggregate.ALL }
    }.keys
    val mixed = initialTagStates.filter { (id, state) -> state == BulkTagAggregate.MIXED && pending[id] == BulkTagPending.KEEP }.keys
    fun requestDismiss() {
        if (hasPendingChanges) discardConfirmationOpen = true else onDismiss()
    }
    BackHandler(onBack = ::requestDismiss)
    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.72f).testTag("media_grid_bulk_tag_dialog"),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("一括タグ編集", style = MaterialTheme.typography.titleLarge)
                        Text("${selectedClipCount}件のツイートへ同じタグを適用します", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = ::requestDismiss, modifier = Modifier.testTag("media_grid_bulk_tag_close")) {
                        Icon(Icons.Filled.Close, contentDescription = "閉じる")
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    TagHierarchySelector(
                        hierarchy = hierarchy,
                        selectedTagIds = effectiveSelected,
                        mixedTagIds = mixed,
                        onToggleTag = { tagId ->
                            pending = pending.toMutableMap().apply {
                                put(tagId, bulkTagPendingAfterToggle(initialTagStates[tagId] ?: BulkTagAggregate.NONE, this[tagId] ?: BulkTagPending.KEEP))
                            }
                        },
                        onOpenGroup = { openGroupId = it },
                    )
                }
                bulkTagDialogError?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("media_grid_bulk_tag_error"))
                }
                Divider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        onClick = ::requestDismiss,
                        modifier = Modifier.weight(1f).testTag("media_grid_bulk_tag_cancel"),
                    ) { Text("キャンセル") }
                    Button(
                        onClick = { if (hasPendingChanges) applyConfirmationOpen = true },
                        enabled = hasPendingChanges,
                        modifier = Modifier.weight(1f).testTag("media_grid_bulk_tag_apply"),
                    ) { Text("適用") }
                }
            }
        }
    }
    openGroupId?.let { groupId ->
        TagSelectionDialog(
            hierarchy = hierarchy,
            selectedTagIds = effectiveSelected,
            mixedTagIds = mixed,
            onToggleTag = { tagId ->
                pending = pending.toMutableMap().apply {
                    put(tagId, bulkTagPendingAfterToggle(initialTagStates[tagId] ?: BulkTagAggregate.NONE, this[tagId] ?: BulkTagPending.KEEP))
                }
            },
            onDismiss = { openGroupId = null },
            initialPath = listOf(groupId),
        )
    }
    if (discardConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { discardConfirmationOpen = false },
            title = { Text("変更を破棄しますか？") },
            confirmButton = {
                Button(
                    onClick = {
                        discardConfirmationOpen = false
                        onDismiss()
                    },
                    modifier = Modifier.testTag("media_grid_bulk_tag_discard_confirm"),
                ) { Text("破棄") }
            },
            dismissButton = {
                TextButton(
                    onClick = { discardConfirmationOpen = false },
                    modifier = Modifier.testTag("media_grid_bulk_tag_discard_cancel"),
                ) { Text("戻る") }
            },
        )
    }
    if (applyConfirmationOpen) {
        val namesById = hierarchy.tags.associate { it.tag.id to it.tag.name }
        val addNames = pending.filterValues { it == BulkTagPending.ADD_ALL }.keys.mapNotNull(namesById::get)
        val removeNames = pending.filterValues { it == BulkTagPending.REMOVE_ALL }.keys.mapNotNull(namesById::get)
        AlertDialog(
            onDismissRequest = { applyConfirmationOpen = false },
            title = { Text("一括タグ変更を適用しますか？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${selectedClipCount}件のツイート")
                    if (addNames.isNotEmpty()) Text("全件追加: ${addNames.joinToString("、")}")
                    if (removeNames.isNotEmpty()) Text("全件削除: ${removeNames.joinToString("、")}")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        applyConfirmationOpen = false
                        onApply(pending.filterValues { it != BulkTagPending.KEEP }) { error -> bulkTagDialogError = error }
                    },
                    modifier = Modifier.testTag("media_grid_bulk_tag_apply_confirm"),
                ) { Text("適用") }
            },
            dismissButton = {
                TextButton(onClick = { applyConfirmationOpen = false }, modifier = Modifier.testTag("media_grid_bulk_tag_apply_cancel")) {
                    Text("戻る")
                }
            },
        )
    }
}

@Composable
internal fun MediaGridFramePublicationRunner(target: MediaGridFramePublicationTarget) {
    LaunchedEffect(target) {
        while (true) {
            target.framePublicationDemand.first { it }
            withFrameNanos { }
            target.publishOneReadyImageForFrame()
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ClassifiedMediaGridContent(
    frame: MediaGridFrameData,
    sort: ClassifiedSortState,
    columnCount: Int,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    controller: MediaGridSteadyLoadController?,
    retainedImageStore: MediaGridRetainedImageStore?,
    controllerState: MediaGridControllerUiState,
    showProgress: Boolean,
    onMediaGridAnchorCheckpoint: (MediaGridSessionKey, ClassifiedMediaGridScrollAnchor) -> Unit,
    onMorphCheckpointSuppressed: (Boolean) -> Unit,
    onMediaGridColumnCountChange: (Int) -> Unit,
    onPinchFinished: (ClassifiedMediaGridScrollAnchor?, Int) -> Unit,
    selectionMode: Boolean,
    multiAssetClipIds: Set<Long>,
    onCellClick: (Long) -> Unit,
    selectedClipIds: Set<Long>,
    onToggleSelection: (Long) -> Unit,
    residentCanvasMode: MediaGridResidentCanvasMode = MediaGridResidentCanvasMode.Disabled,
) {
    val context = LocalContext.current
    val appContainer = (context.applicationContext as LikeListManagerApp).container
    val fallbackController = if (controller == null) {
        val recoveryGate = remember(frame.key) { MediaGridPreviewRecoveryGate() }
        remember(frame.key, appContainer.mediaGridImageLoader) {
            MediaGridSteadyLoadController(
                context = context,
                frame = frame,
                preparer = appContainer.mediaGridImagePreparer,
                imageLoader = appContainer.mediaGridImageLoader,
                onPreviewCandidateError = { assetId, candidate ->
                    if (recoveryGate.claim(candidate.sourceIdentity)) {
                        appContainer.repository.recoverMediaGridCandidate(assetId, candidate)
                    }
                },
                retainedImageStore = retainedImageStore,
                ownerToken = retainedImageStore?.newOwnerToken() ?: 0L,
            ).also { it.start() }
        }
    } else null
    val effectiveController = controller ?: fallbackController
    val fallbackControllerState by (fallbackController?.uiState ?: kotlinx.coroutines.flow.flowOf(controllerState)).collectAsState(initial = controllerState)
    val effectiveControllerState = if (controller == null) {
        fallbackControllerState
    } else controllerState
    val residentDrawIndexVersion = if (residentCanvasMode != MediaGridResidentCanvasMode.Disabled && retainedImageStore != null) {
        val version by retainedImageStore.drawIndexVersionFlow.collectAsState()
        version
    } else 0L
    val residentCanvasAdapter = if (residentCanvasMode != MediaGridResidentCanvasMode.Disabled && retainedImageStore != null) {
        remember(retainedImageStore) { MediaGridResidentCanvasImageAdapter() }
    } else null
    val residentPreparedIndex = if (
        residentCanvasMode != MediaGridResidentCanvasMode.Disabled &&
            retainedImageStore != null &&
            residentCanvasAdapter != null
    ) {
        remember(frame.key, retainedImageStore, residentCanvasAdapter, residentDrawIndexVersion) {
            buildMediaGridResidentCanvasPreparedIndex(
                drawIndex = retainedImageStore.drawIndexSnapshot(),
                adapter = residentCanvasAdapter,
            )
        }
    } else null
    val morphPreparationCache = remember(state) { MediaGridMorphPreparationCache() }
    val morphPairVersion by morphPreparationCache.publishedVersion.collectAsState()
    val morphPointerInProgress = remember(state) { MutableStateFlow(false) }
    val fallbackMorphHeaderHeightPx = with(LocalDensity.current) { 40.dp.toPx() }
    val morphViewportSignature = buildMediaGridViewportSignature(state.layoutInfo, frame, columnCount)
    val morphIdentity = MediaGridMorphInteractionIdentity(
        sourceRevision = frame.key.dataKey.sourceRevision,
        frameKey = frame.key,
        currentColumnCount = columnCount,
        viewportSignature = morphViewportSignature,
    )
    val morphEnabled = !selectionMode && !showProgress
    val morphHost = rememberMediaGridMorphProductionHostState(
        state = state,
        sessionKey = null,
        retainedImageStore = retainedImageStore,
        enabled = morphEnabled,
    )
    val morphController = morphHost?.controller
    val morphPreparedPairsSnapshot: () -> Map<MediaGridMorphDirection, MediaGridMorphPreparedPair> = {
        val preparedIndex = residentPreparedIndex
        if (preparedIndex == null) {
            emptyMap()
        } else {
            morphPreparationCache.snapshot().filterValues { pair ->
                pair.matchesIdentity(morphIdentity) &&
                    isMediaGridMorphProductionReady(pair, preparedIndex)
            }
        }
    }
    val morphPairsForTextResources = remember(morphPairVersion, morphIdentity) {
        morphPreparationCache.snapshot().filterValues { it.matchesIdentity(morphIdentity) }
    }
    val morphTextResourceIndex = rememberMediaGridMorphTextResourceIndex(
        pairs = morphPairsForTextResources,
        viewportWidthPx = morphIdentity.viewportSignature.viewportWidthPx,
        additionalTitles = frame.items.filterIsInstance<MediaGridHeaderItem>().map { it.label },
    )
    val morphInteractionLocked = morphController?.interactionLocked?.value == true
    val morphSnapshot = morphController?.snapshotState?.value
    val morphActivePlan = morphSnapshot?.plan
    val morphRowRenderModel = morphSnapshot?.activeRenderModel
    val morphVisualActive = morphController?.drawMode?.value == MediaGridMorphDrawMode.Morph && morphRowRenderModel != null
    val morphProgress = morphController?.progress ?: remember { mutableStateOf(0f) }
    val morphDrawObserver = if (BuildConfig.TEST_HARNESS) {
        { event: MediaGridMorphDrawObservation -> MediaGridMorphTestTrace.recordDraw(event) }
    } else {
        null
    }
    val singleSurfaceMode = remember(morphController) {
        androidx.compose.runtime.derivedStateOf {
            when (morphController?.snapshotState?.value?.drawMode) {
                MediaGridMorphDrawMode.Morph -> MediaGridSingleSurfaceMode.Morph
                MediaGridMorphDrawMode.RevealCurrent -> MediaGridSingleSurfaceMode.RevealCurrent
                MediaGridMorphDrawMode.RevealTarget -> MediaGridSingleSurfaceMode.RevealTarget
                else -> MediaGridSingleSurfaceMode.Normal
            }
        }
    }
    LaunchedEffect(morphController, morphIdentity) {
        morphController?.updateIdentity(morphIdentity)
    }
    if (morphHost != null && morphController != null) {
        MediaGridMorphProductionHandoffEffects(
            host = morphHost,
            frame = frame,
            sessionKey = null,
            identity = morphIdentity,
            state = state,
            onColumnCountChange = onMediaGridColumnCountChange,
            onAnchorCheckpoint = onMediaGridAnchorCheckpoint,
            onCheckpointSuppressed = onMorphCheckpointSuppressed,
        )
    }
    if (fallbackController != null) DisposableEffect(fallbackController) { onDispose { fallbackController.dispose() } }
    LaunchedEffect(state, frame.key, effectiveController) {
        kotlinx.coroutines.coroutineScope {
            launch {
                snapshotFlow {
                    buildMediaGridViewportSignature(state.layoutInfo, frame, columnCount)
                }.distinctUntilChanged().collect { anchor ->
                    effectiveController?.updateViewport(anchor.toAnchor())
                }
            }
            launch {
                MediaGridPreviewNotifier.previewChanged.collect { assetId ->
                    effectiveController?.invalidate(assetId)
                }
            }
        }
    }
    LaunchedEffect(
        state,
        frame.key,
        columnCount,
        morphPreparationCache,
        morphPointerInProgress,
        residentPreparedIndex?.drawIndexVersion,
    ) {
        combine(
            snapshotFlow {
                buildMediaGridViewportSignature(state.layoutInfo, frame, columnCount)
                    .takeIf {
                        it.firstVisibleMediaOrdinal >= 0 &&
                            it.lastVisibleMediaOrdinal >= it.firstVisibleMediaOrdinal &&
                            it.viewportWidthPx > 0 &&
                            it.viewportHeightPx > 0 &&
                            it.visibleItemGeometry.isNotEmpty()
                    }
            }.distinctUntilChanged(),
            snapshotFlow { state.isScrollInProgress }.distinctUntilChanged(),
            morphPointerInProgress,
        ) { signature, isScrollInProgress, pointerInProgress ->
            signature?.takeUnless { isScrollInProgress || pointerInProgress }
        }.distinctUntilChanged().collectLatest { signature ->
            if (signature == null) return@collectLatest
            val identity = MediaGridMorphPreparationIdentity(
                sourceRevision = frame.key.dataKey.sourceRevision,
                frameKey = frame.key,
                columnCount = columnCount,
                viewportSignature = signature,
            )
            val token = morphPreparationCache.request(
                identity = identity,
                isScrollInProgress = false,
                isPointerInProgress = false,
                preparedIndexVersion = residentPreparedIndex?.drawIndexVersion ?: Long.MIN_VALUE,
            ) ?: return@collectLatest
            val capture = captureMediaGridMorphInput(
                frame = frame,
                layoutInfo = state.layoutInfo,
                columnCount = columnCount,
                fallbackHeaderHeightPx = fallbackMorphHeaderHeightPx,
                preparedIndex = residentPreparedIndex,
            ) ?: return@collectLatest
            val pairs = withContext(Dispatchers.Default) {
                buildMediaGridMorphRowPreparedPairs(capture)
            }
            effectiveController?.requestMorphUrgentAssets(
                pairs.values.flatMap { it.requiredMorphAssetIds().asIterable() }.toLongArray(),
            )
            morphPreparationCache.publish(token, pairs)
        }
    }
    LaunchedEffect(
        morphPairVersion,
        morphIdentity,
        residentPreparedIndex?.drawIndexVersion,
        morphTextResourceIndex.identity,
    ) {
        if (!BuildConfig.TEST_HARNESS) return@LaunchedEffect
        val preparedIndex = residentPreparedIndex ?: return@LaunchedEffect
        val pairs = morphPreparationCache.snapshot()
            .filterValues { it.matchesIdentity(morphIdentity) }
        if (pairs.isEmpty()) return@LaunchedEffect
        val failureReasons = pairs.mapNotNull { (direction, pair) ->
            mediaGridMorphStableIdleFailureReason(pair, preparedIndex, morphTextResourceIndex)
                ?.let { reason -> "$direction:$reason" }
        }
        MediaGridMorphTestTrace.recordIdleReadiness(
            MediaGridMorphIdleReadinessObservation(
                generation = morphPairVersion,
                identity = morphIdentity,
                directionCount = pairs.size,
                readyDirectionCount = pairs.size - failureReasons.size,
                ready = failureReasons.isEmpty() && pairs.isNotEmpty(),
                failureReasons = failureReasons,
            ),
        )
    }
    effectiveController?.let { MediaGridFramePublicationRunner(it) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .testTag("classified_media_grid")
                .then(
                    if (morphController != null || !morphEnabled || retainedImageStore == null) {
                        Modifier.mediaGridMorphGestureInput(
                            mode = MediaGridMorphGestureMode.Production,
                            controller = morphController,
                            identity = morphIdentity,
                            preparedPairsSnapshot = morphPreparedPairsSnapshot,
                            stopScroll = suspend { state.stopScroll() },
                            onFallbackPinchFinished = { _, nextColumnCount ->
                                if (BuildConfig.TEST_HARNESS) MediaGridMorphTestTrace.recordFallback()
                                onPinchFinished(null, nextColumnCount)
                            },
                            pointerInProgress = morphPointerInProgress,
                            prepareClaimBundle = if (morphEnabled && residentPreparedIndex != null) {
                                { candidate ->
                                    val capture = captureMediaGridMorphInput(
                                        frame = frame,
                                        layoutInfo = state.layoutInfo,
                                        columnCount = columnCount,
                                        fallbackHeaderHeightPx = fallbackMorphHeaderHeightPx,
                                        preparedIndex = residentPreparedIndex,
                                    )
                                    prepareMediaGridMorphClaim(
                                        capture = capture,
                                        preparedIndex = residentPreparedIndex,
                                        textResources = morphTextResourceIndex,
                                        candidate = candidate,
                                        requestedDirection = candidate.claimDirection,
                                        latestIdentity = morphIdentity,
                                        sourceViewportAnchor = MediaGridMorphSourceViewportAnchor(
                                            firstVisibleItemIndex = state.firstVisibleItemIndex,
                                            firstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset,
                                        ),
                                    )
                                }
                            } else null,
                            isPairReady = if (residentPreparedIndex != null) {
                                { pair -> isMediaGridMorphProductionReady(pair, residentPreparedIndex) }
                            } else {
                                { false }
                            },
                            claimFailureReason = if (residentPreparedIndex != null) {
                                { pair ->
                                    val completeness = pair?.let {
                                        mediaGridMorphImageCompleteness(it, residentPreparedIndex)
                                    }
                                    when {
                                        completeness == null -> null
                                        completeness.requiredSourceImageCount != completeness.resolvedSourceImageCount ->
                                            MediaGridMorphFailureReason.MissingVisibleSourceImage
                                        completeness.requiredTargetImageCount != completeness.resolvedTargetImageCount ->
                                            MediaGridMorphFailureReason.MissingTargetImage
                                        !completeness.sourceViewportComplete ->
                                            MediaGridMorphFailureReason.SourceViewportMismatch
                                        !completeness.headerTextComplete -> MediaGridMorphFailureReason.MissingHeaderText
                                        !completeness.geometryComplete -> MediaGridMorphFailureReason.RenderModelIncomplete
                                        else -> null
                                    }
                                }
                            } else {
                                { null }
                            },
                        )
                    } else Modifier,
                )
                .then(
                    if (residentPreparedIndex != null) {
                        Modifier.mediaGridSingleSurface(
                            state = state,
                            assetIdByItemKey = frame.assetIdByItemKey,
                            preparedIndex = residentPreparedIndex,
                            mode = singleSurfaceMode,
                            morphModel = morphRowRenderModel,
                            progress = morphProgress,
                            morphDrawObserver = morphDrawObserver,
                            morphSnapshot = morphController?.snapshotState,
                        )
                    } else Modifier,
                ),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            userScrollEnabled = !morphInteractionLocked,
        ) {
            gridItems(
                frame.items,
                key = { it.key },
                contentType = { item -> if (item is MediaGridHeaderItem) "Header" else "MediaCell" },
                span = { item ->
                    when (item) {
                        is MediaGridHeaderItem -> GridItemSpan(maxLineSpan)
                        is MediaGridCellItem -> GridItemSpan(1)
                    }
                },
            ) { item ->
                when (item) {
                    is MediaGridHeaderItem -> ClassifiedMediaGridHeader(item, visualsVisible = !morphVisualActive)
                    is MediaGridCellItem -> ClassifiedMediaGridCell(
                        modifier = Modifier,
                        entry = item.entry,
                        sort = sort,
                        columnCount = columnCount,
                        selectionMode = selectionMode,
                        multiAsset = item.entry.clipId in multiAssetClipIds,
                        selected = item.entry.clipId in selectedClipIds,
                        onClick = onCellClick,
                        onToggleSelection = onToggleSelection,
                        interactionEnabled = !morphInteractionLocked,
                        metadataOverlaysVisible = !morphInteractionLocked && !morphVisualActive,
                        visualsVisible = !morphVisualActive,
                        imageLoader = appContainer.mediaGridImageLoader,
                        loadState = effectiveControllerState.cells[item.entry.assetId] ?: MediaGridCellLoadState(),
                         residentDrawAvailable = retainedImageStore?.hasEligibleDrawHandle(item.entry.assetId) == true && residentPreparedIndex != null,
                    )
                }
            }
            }
        }
        if (showProgress) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("classified_media_grid_progress"),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.CircularProgressIndicator() }
        }
    }
}

internal fun captureClassifiedMediaGridScrollAnchor(
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    assetIdByItemKey: Map<String, Long>,
    preferredCenter: Offset? = null,
): ClassifiedMediaGridScrollAnchor? {
    val layoutInfo = state.layoutInfo
    if (layoutInfo.visibleItemsInfo.isEmpty()) return null
    val viewportCenterY = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val centerX = preferredCenter?.x ?: layoutInfo.viewportSize.width / 2f
    val centerY = preferredCenter?.y ?: viewportCenterY
    var selected: androidx.compose.foundation.lazy.grid.LazyGridItemInfo? = null
    var selectedDistance = Float.POSITIVE_INFINITY
    for (info in layoutInfo.visibleItemsInfo) {
        if (selectedDistance < 0f) continue
        val key = info.key as? String ?: continue
        if (assetIdByItemKey[key] == null) continue
        val containsCenter = preferredCenter != null &&
            centerX >= info.offset.x && centerX <= info.offset.x + info.size.width &&
            centerY >= info.offset.y && centerY <= info.offset.y + info.size.height
        val itemCenterX = info.offset.x + info.size.width / 2f
        val itemCenterY = info.offset.y + info.size.height / 2f
        val dx = itemCenterX - centerX
        val dy = itemCenterY - centerY
        val distance = dx * dx + dy * dy
        if (containsCenter || selected == null || distance < selectedDistance) {
            selected = info
            selectedDistance = distance
            if (containsCenter) selectedDistance = -1f
        }
    }
    val selectedInfo = selected ?: return null
    val selectedKey = selectedInfo.key as? String ?: return null
    return ClassifiedMediaGridScrollAnchor(
        key = selectedKey,
        index = selectedInfo.index,
        offset = selectedInfo.offset.y,
        centerOffset = selectedInfo.offset.y + selectedInfo.size.height / 2f - viewportCenterY,
    )
}

@Composable
private fun ClassifiedMediaGridHeader(item: MediaGridHeaderItem, visualsVisible: Boolean) {
    if (!visualsVisible) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .testTag(item.key),
        )
        return
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(item.key),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = item.label,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("media_grid_header_text_${item.safeKey}"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ClassifiedMediaGridCell(
    modifier: Modifier = Modifier,
    entry: MediaGridEntry,
    sort: ClassifiedSortState,
    columnCount: Int,
    selectionMode: Boolean,
    multiAsset: Boolean,
    selected: Boolean,
    onClick: (Long) -> Unit,
    onToggleSelection: (Long) -> Unit,
    interactionEnabled: Boolean,
    metadataOverlaysVisible: Boolean,
    visualsVisible: Boolean,
    imageLoader: coil.ImageLoader,
    loadState: MediaGridCellLoadState,
    residentDrawAvailable: Boolean,
) {
    val context = LocalContext.current
    val readyCandidate = loadState.readyCandidate
    val imageRequest = remember(readyCandidate, residentDrawAvailable) {
        if (residentDrawAvailable) null else readyCandidate?.let { candidate ->
            buildMediaGridImageRequest(context, candidate)
        }
    }
    val visualState = when (loadState.status) {
        MediaGridCellLoadStatus.Ready -> MediaGridCellVisualState.Image
        MediaGridCellLoadStatus.Failed -> MediaGridCellVisualState.Error
        else -> MediaGridCellVisualState.Placeholder
    }
    val selectionIndicatorSize = mediaGridSelectionIndicatorSize(columnCount)
    val videoIconSize = mediaGridVideoIconSize(columnCount)
    val cardDialogSize = mediaGridCardDialogSize(columnCount)
    val overlayPadding = if (columnCount <= 4) 4.dp else 2.dp
    val hapticFeedback = LocalHapticFeedback.current
    val selectionBackground = mediaGridSelectionBackground(MaterialTheme.colorScheme.primary, multiAsset)
    val selectionCheckColor = if (multiAsset) Color.White else Color.Black
    val cellBackground = if (residentDrawAvailable) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 1f)
    val placeholderStartColor = cellBackground
    val placeholderEndColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .pointerInput(entry.clipId, selectionMode, interactionEnabled) {
                if (!interactionEnabled) return@pointerInput
                detectTapGestures(
                    onLongPress = {
                        handleMediaGridLongPress(
                            selectionMode = selectionMode,
                            haptic = { hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress) },
                            toggleSelection = { onToggleSelection(entry.clipId) },
                        )
                    },
                    onTap = {
                        if (selectionMode) onToggleSelection(entry.clipId) else onClick(entry.clipId)
                    },
                )
            }
            .testTag("media_grid_item_${entry.assetId}")
            .then(if (visualsVisible && residentDrawAvailable) Modifier.testTag("media_grid_resident_image_${entry.assetId}") else Modifier)
            .then(if (visualsVisible) Modifier.background(cellBackground) else Modifier),
    ) {
        if (visualsVisible && residentDrawAvailable) {
            // The LazyGrid draw modifier supplies the image below this cell content.
        } else if (visualsVisible && visualState == MediaGridCellVisualState.Error) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("media_grid_error_${entry.assetId}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "エラー",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else if (visualsVisible) {
            if (visualState == MediaGridCellVisualState.Placeholder) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .mediaGridPlaceholder(
                            visualState = visualState,
                            startColor = placeholderStartColor,
                            endColor = placeholderEndColor,
                        )
                        .testTag("media_grid_placeholder_${entry.assetId}"),
                )
            }
            if (imageRequest != null) AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    imageLoader = imageLoader,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().testTag("media_grid_image_${entry.assetId}"),
                )
        }
        if (visualsVisible && metadataOverlaysVisible && !selectionMode && sort.baseOrder == ClassifiedSortBase.LikeCount && entry.likeCount != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).testTag("media_grid_like_count_${entry.assetId}"),
                shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.68f),
            ) { Text(formatLikeCount(entry.likeCount), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
        }
        if (visualsVisible && metadataOverlaysVisible && entry.type == "video_thumbnail") {
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(overlayPadding).size(videoIconSize).testTag("media_grid_video_badge_${entry.assetId}"),
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = "再生", tint = Color.White, modifier = Modifier.fillMaxSize()) }
        }
        if (visualsVisible && selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(overlayPadding)
                    .size(selectionIndicatorSize)
                    .testTag("media_grid_selection_${entry.assetId}"),
                shape = CircleShape,
                color = Color.Transparent,
                border = BorderStroke(1.5.dp, Color.White),
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(selectionIndicatorSize * 0.68f)
                                .background(selectionBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "選択済み",
                                tint = selectionCheckColor,
                                modifier = Modifier.fillMaxSize().padding(selectionIndicatorSize / 7),
                            )
                        }
                    }
                }
            }
        }
        if (visualsVisible && selectionMode && columnCount <= 6) {
            IconButton(
                onClick = { if (interactionEnabled) onClick(entry.clipId) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(overlayPadding)
                    .size(cardDialogSize)
                    .testTag("media_grid_selection_open_${entry.assetId}"),
            ) {
                Box(
                    modifier = Modifier
                        .size(mediaGridCardDialogVisualSize(columnCount))
                        .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ViewList,
                        contentDescription = "カードを表示",
                        tint = Color.White,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                    )
                }
            }
        }
    }
}

private fun mediaGridSelectionIndicatorSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 28.dp
    4, 5, 6 -> 24.dp
    7, 8, 9 -> 18.dp
    else -> 14.dp
}

internal fun mediaGridSelectionBackground(primary: Color, multiAsset: Boolean): Color = if (!multiAsset) {
    Color(
        red = (primary.red * 0.72f + 0.24f).coerceAtMost(1f),
        green = (primary.green * 0.72f + 0.24f).coerceAtMost(1f),
        blue = 1f,
        alpha = 0.96f,
    )
} else {
    Color(
        red = (primary.red * 0.55f).coerceAtLeast(0f),
        green = (primary.green * 0.55f).coerceAtLeast(0f),
        blue = (primary.blue * 0.82f).coerceAtMost(1f),
        alpha = 0.96f,
    )
}

internal fun handleMediaGridLongPress(
    selectionMode: Boolean,
    haptic: () -> Unit,
    toggleSelection: () -> Unit,
) {
    if (!selectionMode) haptic()
    toggleSelection()
}

private fun mediaGridVideoIconSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 24.dp
    4, 5, 6 -> 20.dp
    7, 8, 9 -> 14.dp
    else -> 10.dp
}

private fun mediaGridCardDialogSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 28.dp
    else -> 24.dp
}

private fun mediaGridCardDialogVisualSize(columnCount: Int) = when (columnCount.coerceIn(2, 12)) {
    2, 3 -> 20.dp
    else -> 18.dp
}

private data class DisplayAsset(
    val asset: AssetEntity,
    val url: String,
)

private fun AssetEntity.displayAspectRatio(): Float {
    val safeWidth = width?.takeIf { it > 0 } ?: return 16f / 10f
    val safeHeight = height?.takeIf { it > 0 } ?: return 16f / 10f
    return (safeWidth.toFloat() / safeHeight.toFloat()).coerceIn(0.35f, 3.2f)
}

@Composable
private fun EnhancedMediaCell(
    displayAsset: DisplayAsset,
    viewerIndex: Int?,
    contentScale: ContentScale,
    modifier: Modifier,
    onOpenViewer: (Int) -> Unit,
) {
    val clickableModifier = if (viewerIndex != null) {
        Modifier.clickable { onOpenViewer(viewerIndex) }
    } else {
        Modifier
    }
    AsyncImage(
        model = displayAsset.url,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
            .testTag("media_asset_${displayAsset.asset.id}")
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(clickableModifier),
    )
}

private fun List<DisplayAsset>.viewerIndexFor(displayAsset: DisplayAsset): Int? =
    indexOfFirst { it.asset.id == displayAsset.asset.id }.takeIf { it >= 0 }

private enum class ViewerDragMode { Horizontal, Vertical }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullScreenImageViewer(
    photos: List<DisplayAsset>,
    initialPage: Int,
    onDismiss: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { photos.size }
    val density = LocalDensity.current
    val closeThresholdPx = remember(density) { with(density) { 120.dp.toPx() } }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var dragTotal by remember { mutableStateOf(Offset.Zero) }
    var dragMode by remember { mutableStateOf<ViewerDragMode?>(null) }
    var closing by remember { mutableStateOf(false) }
    val dragAlpha = (1f - (abs(dragOffsetY) / (closeThresholdPx * 2f))).coerceIn(0.55f, 1f)
    val backgroundAlpha by animateFloatAsState(
        targetValue = if (closing) 0f else dragAlpha,
        label = "image-viewer-background",
    )

    fun closeViewer() {
        if (!closing) closing = true
    }

    LaunchedEffect(closing) {
        if (closing) {
            delay(160)
            onDismiss()
        }
    }
    BackHandler(onBack = ::closeViewer)

    Dialog(
        onDismissRequest = ::closeViewer,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .testTag("image_viewer")
                .background(Color.Black.copy(alpha = backgroundAlpha)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = ::closeViewer,
                    modifier = Modifier.testTag("image_viewer_close"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "閉じる",
                        tint = Color.White,
                    )
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${photos.size}",
                    modifier = Modifier.testTag("image_viewer_position"),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(photos.size) {
                        detectDragGestures(
                            onDragStart = {
                                dragTotal = Offset.Zero
                                dragMode = null
                            },
                            onDragEnd = {
                                if (dragMode == ViewerDragMode.Vertical && abs(dragOffsetY) >= closeThresholdPx) {
                                    closeViewer()
                                } else {
                                    dragOffsetY = 0f
                                }
                                dragTotal = Offset.Zero
                                dragMode = null
                            },
                            onDragCancel = {
                                dragOffsetY = 0f
                                dragTotal = Offset.Zero
                                dragMode = null
                            },
                        ) { change, dragAmount ->
                            dragTotal += dragAmount
                            if (dragMode == null && (abs(dragTotal.x) > 12f || abs(dragTotal.y) > 12f)) {
                                dragMode = if (abs(dragTotal.y) > abs(dragTotal.x)) {
                                    ViewerDragMode.Vertical
                                } else {
                                    ViewerDragMode.Horizontal
                                }
                            }
                            if (dragMode == ViewerDragMode.Vertical) {
                                change.consume()
                                dragOffsetY += dragAmount.y
                            }
                        }
                    },
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    AsyncImage(
                        model = photos[page].url,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("image_viewer_photo_$page")
                            .graphicsLayer { translationY = dragOffsetY },
                    )
                }
            }
        }
    }
}
