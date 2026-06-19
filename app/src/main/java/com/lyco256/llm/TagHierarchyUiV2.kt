package com.lyco256.llm

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.lyco256.llm.data.AssetEntity
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagGroupNode
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagLeafNode
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagTreeNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    onDelete: (ClipEntity) -> Unit,
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
                    modifier = Modifier.fillMaxSize(),
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
                            onDelete = onDelete,
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
fun EnhancedClassifiedScreen(
    uiState: MainUiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onTagFilterChange: (TagNodeRef) -> Unit,
    onClearTagFilters: () -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    var filterDialogOpen by remember { mutableStateOf(false) }
    val itemKeys = remember(uiState.classified) { uiState.classified.map { it.clip.id } }
    PreserveScrollAnchor(listState, "classified", itemKeys)
    Column(modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("本文/概要/投稿者を検索") },
        )
        Spacer(Modifier.height(8.dp))
        TagFilterSummaryRow(
            hierarchy = uiState.tagHierarchy,
            filters = uiState.tagFilters,
            onOpen = { filterDialogOpen = true },
            onClear = onClearTagFilters,
        )
        Spacer(Modifier.height(10.dp))
        if (uiState.classified.isEmpty()) {
            HierarchyEmptyState("条件に合う分類済みツイートはありません")
        } else {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(uiState.classified, key = { it.clip.id }) { clip ->
                        EnhancedTweetCard(
                            clip = clip,
                            hierarchy = uiState.tagHierarchy,
                            selectedTagIds = clip.tags.map { it.id }.toSet(),
                            onTagSelectionChange = { onTagsChange(clip.clip, it) },
                            onSummaryChange = onSummaryChange,
                            onDelete = onDelete,
                        )
                    }
                }
                LazyListScrollbar(listState)
                ScrollToTopButton(listState, hasItems = uiState.classified.isNotEmpty())
            }
        }
    }
    if (filterDialogOpen) {
        TagFilterDialog(
            hierarchy = uiState.tagHierarchy,
            filters = uiState.tagFilters,
            onCycle = onTagFilterChange,
            onClear = onClearTagFilters,
            onDismiss = { filterDialogOpen = false },
        )
    }
}

@Composable
fun EnhancedTagListScreen(
    hierarchy: TagHierarchy,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    onCreateTag: (String, Long?) -> Unit,
    onCreateGroup: (String, Long?) -> Unit,
    onRenameTag: (TagEntity, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String) -> Unit,
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
            Button(onClick = { createRequest = TagNodeType.GROUP to null }) { Text("グループ追加") }
            Button(onClick = { createRequest = TagNodeType.TAG to null }) { Text("タグ追加") }
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
        CreateNodeDialog(type, parentId, onDismiss = { createRequest = null }) { name ->
            if (type == TagNodeType.TAG) onCreateTag(name, parentId) else onCreateGroup(name, parentId)
            createRequest = null
        }
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
    onDelete: (ClipEntity) -> Unit,
) {
    val context = LocalContext.current
    var summary by remember(clip.clip.id, clip.clip.summary) { mutableStateOf(clip.clip.summary) }
    var deleteOpen by remember { mutableStateOf(false) }
    val selectionPath = remember { mutableStateListOf<Long>() }
    var selectionOpen by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(clip.clip.authorName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "@${clip.clip.authorUsername}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(clip.clip.postUrl)))
                    },
                ) { Text("Xで開く") }
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
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = summary,
                onValueChange = {
                    summary = it
                    onSummaryChange(clip.clip, it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("概要") },
                minLines = 1,
                maxLines = 3,
            )
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { deleteOpen = true }) {
                    Text("ローカル削除")
                }
                if (requireTagConfirmation) {
                    Button(
                        onClick = onTagConfirmation,
                        enabled = hierarchy.tags.isNotEmpty() && selectedTagIds.isNotEmpty(),
                    ) {
                        Text("分類済みにする")
                    }
                }
            }
        }
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
    if (deleteOpen) {
        ConfirmDialog(
            title = "ローカル削除",
            message = "このツイートをアプリ内の一覧から削除します。X側のいいねは変更しません。",
            onDismiss = { deleteOpen = false },
            onConfirm = {
                onDelete(clip.clip)
                deleteOpen = false
            },
        )
    }
}

@Composable
private fun TagHierarchySelector(
    hierarchy: TagHierarchy,
    selectedTagIds: Set<Long>,
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
    hierarchy: TagHierarchy,
    filters: Map<TagNodeRef, TagFilterState>,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    val activeFilters = filters.entries.filter { it.value != TagFilterState.NONE }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = activeFilters.isEmpty(),
                onClick = onClear,
                label = { Text("すべて") },
            )
        }
        items(activeFilters, key = { it.key.saveableKey() }) { (ref, state) ->
            val node = hierarchy.nodeFor(ref)
            FilterChip(
                selected = true,
                onClick = onOpen,
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.shortLabel()}${node?.name ?: "不明"}")
                    }
                },
            )
        }
        item {
            FilledTonalButton(onClick = onOpen) {
                Text(if (activeFilters.isEmpty()) "絞り込み" else "絞り込み ${activeFilters.size}件")
            }
        }
    }
}

@Composable
private fun TagFilterDialog(
    hierarchy: TagHierarchy,
    filters: Map<TagNodeRef, TagFilterState>,
    onCycle: (TagNodeRef) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val path = remember { mutableStateListOf<Long>() }
    val currentParentId = path.lastOrNull()
    val currentGroup = currentParentId?.let { hierarchy.groups.firstOrNull { group -> group.id == it } }
    val children = hierarchy.children(currentParentId)
    BackHandler(enabled = path.isNotEmpty()) {
        path.removeAt(path.lastIndex)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(0.dp)) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (path.isEmpty()) onDismiss() else path.removeAt(path.lastIndex)
                    }) {
                        Icon(if (path.isEmpty()) Icons.Filled.Close else Icons.Filled.ArrowBack, contentDescription = null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("絞り込み", style = MaterialTheme.typography.titleLarge)
                        Text(currentGroup?.name ?: "ルート", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onClear) { Text("クリア") }
                }
                if (children.isEmpty()) {
                    HierarchyEmptyState("この階層には条件を設定できる項目がありません")
                } else {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(144.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            gridItems(children, key = { it.ref().saveableKey() }) { node ->
                                val ref = node.ref()
                                val state = filters[ref] ?: TagFilterState.NONE
                                val selected = state != TagFilterState.NONE
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onCycle(ref) },
                                        modifier = Modifier.weight(1f),
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
                                                            tint = Color((node as TagLeafNode).tag.color),
                                                            modifier = Modifier.size(18.dp),
                                                        )
                                                        Spacer(Modifier.width(6.dp))
                                                    }
                                                    Text("${state.shortLabel()}${node.name}")
                                                }
                                                if (node is TagGroupNode) {
                                                    Text(
                                                        "${node.count} 件",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
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
        }
    }
}

@Composable
private fun TagHierarchyChip(
    node: TagTreeNode,
    hierarchy: TagHierarchy,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onOpenGroup: (Long) -> Unit,
    fillMaxWidth: Boolean = false,
) {
    val modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier
    val shape = RoundedCornerShape(8.dp)
    if (node is TagGroupNode) {
        val selectedCount = hierarchy.descendantTagIdsByGroup[node.id].orEmpty().count { it in selectedTagIds }
        Surface(
            modifier = modifier
                .heightIn(min = 32.dp)
                .clip(shape)
                .clickable { onOpenGroup(node.id) },
            shape = shape,
            color = if (selectedCount > 0) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Surface(
            modifier = modifier
                .heightIn(min = 32.dp)
                .clip(shape)
                .clickable { onToggleTag(tag.id) },
            shape = shape,
            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(tag.color) else MaterialTheme.colorScheme.outlineVariant),
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
                        .background(Color(tag.color)),
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
    onRenameTag: (TagEntity, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var renameOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var moveOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var deleteOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var addAllOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var menuOpen by remember(row.node.ref()) { mutableStateOf(false) }
    val rowColor = when {
        isGroupDropTarget -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Column(modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = rowColor),
            modifier = Modifier
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
                        IconButton(onClick = onToggleExpanded) {
                            Icon(
                                Icons.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.graphicsLayer(rotationZ = if (expanded[row.node.id] == true) 90f else 0f),
                            )
                        }
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            tint = if (expanded[row.node.id] == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    } else {
                        val tag = (row.node as TagLeafNode).tag
                        Icon(
                            Icons.Filled.LocalOffer,
                            contentDescription = null,
                            tint = Color(tag.color),
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
                            if (row.node is TagGroupNode) {
                                Badge { Text("${row.node.count}") }
                            }
                        }
                        Text(
                            if (row.node is TagGroupNode) "グループ" else "タグ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("操作") }
                        androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (row.node is TagGroupNode) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("子グループを追加") },
                                    onClick = { menuOpen = false; onCreate(TagNodeType.GROUP, row.node.id) },
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("子タグを追加") },
                                    onClick = { menuOpen = false; onCreate(TagNodeType.TAG, row.node.id) },
                                )
                            } else {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("別タグへ一括追加") },
                                    onClick = { menuOpen = false; addAllOpen = true },
                                )
                            }
                            androidx.compose.material3.DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menuOpen = false; renameOpen = true })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("別グループへ移動") }, onClick = { menuOpen = false; moveOpen = true })
                            androidx.compose.material3.DropdownMenuItem(text = { Text("削除") }, onClick = { menuOpen = false; deleteOpen = true })
                        }
                    }
            }
        }
    }
    if (renameOpen) {
        RenameNodeDialog(row.node.name, onDismiss = { renameOpen = false }) { name ->
            when (row.node) {
                is TagGroupNode -> onRenameGroup(row.node.group, name)
                is TagLeafNode -> onRenameTag(row.node.tag, name)
            }
            renameOpen = false
        }
    }
    if (moveOpen) {
        MoveNodeDialog(row.node, hierarchy.groups, onDismiss = { moveOpen = false }) { parent ->
            onMove(row.node.ref(), parent)
            moveOpen = false
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
                TextButton(onClick = {
                    if (row.node is TagGroupNode) onDeleteGroup(row.node.group) else onDeleteTag((row.node as TagLeafNode).tag)
                    deleteOpen = false
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("閉じる") } },
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
            .size(48.dp),
        shape = CircleShape,
        containerColor = Color.White,
    ) {
        Text("↑", color = Color.Black, fontWeight = FontWeight.Bold)
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        when (shown.size) {
            1 -> EnhancedMediaCell(
                shown[0].url,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(shown[0].asset.displayAspectRatio()),
            )
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEach { EnhancedMediaCell(it.url, ContentScale.Crop, Modifier.weight(1f).aspectRatio(1f)) }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.take(2).forEach { EnhancedMediaCell(it.url, ContentScale.Crop, Modifier.weight(1f).aspectRatio(1f)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.drop(2).forEach { EnhancedMediaCell(it.url, ContentScale.Crop, Modifier.weight(1f).aspectRatio(1f)) }
                    if (shown.size == 3) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
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
private fun EnhancedMediaCell(url: String, contentScale: ContentScale, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
