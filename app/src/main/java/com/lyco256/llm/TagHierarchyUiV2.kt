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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class VisibleTagRow(
    val node: TagTreeNode,
    val depth: Int,
    val parentGroupId: Long?,
    val indexInParent: Int,
)

private sealed interface TagDropTarget {
    data class Before(val parentGroupId: Long?, val index: Int) : TagDropTarget
    data class After(val parentGroupId: Long?, val index: Int) : TagDropTarget
    data class IntoGroup(val groupId: Long) : TagDropTarget
}

private data class DragState(
    val node: TagNodeRef,
    val label: String,
    val isGroup: Boolean,
    val originBounds: Rect,
    val anchor: Offset,
    val delta: Offset = Offset.Zero,
)

@Composable
fun EnhancedClipListScreen(
    title: String,
    clips: List<ClipWithDetails>,
    hierarchy: TagHierarchy,
    emptyText: String,
    modifier: Modifier = Modifier,
    requireTagConfirmation: Boolean = false,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    val pendingTagIds = remember { mutableStateMapOf<Long, Set<Long>>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        if (clips.isEmpty()) {
            HierarchyEmptyState(emptyText)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        }
    }
}

@Composable
fun EnhancedClassifiedScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onTagFilterChange: (TagNodeRef) -> Unit,
    onClearTagFilters: () -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    var filterDialogOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("分類リスト", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    var listBounds by remember { mutableStateOf<Rect?>(null) }
    var dragLayerBounds by remember { mutableStateOf<Rect?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("タグリスト", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
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
                .onGloballyPositioned { dragLayerBounds = it.boundsInRoot() },
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { listBounds = it.boundsInRoot() },
            ) {
                items(visibleRows, key = { it.node.ref().saveableKey() }) { row ->
                    TagManagementRow(
                        row = row,
                        hierarchy = hierarchy,
                        expanded = expanded,
                        currentBounds = rowBounds[row.node.ref()],
                        highlightedTarget = dragState?.let { targetDropTarget(it, rowBounds, visibleRows, listBounds) },
                        draggedNode = dragState?.node,
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
                        onDragStart = { anchor, bounds ->
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragState = DragState(
                                node = row.node.ref(),
                                label = row.node.name,
                                isGroup = row.node is TagGroupNode,
                                originBounds = bounds ?: Rect(Offset.Zero, Offset.Zero),
                                anchor = anchor,
                            )
                        },
                        onDragDelta = { delta ->
                            dragState = dragState?.takeIf { it.node == row.node.ref() }?.let { current ->
                                val updated = current.copy(delta = current.delta + delta)
                                val point = updated.pointerPosition()
                                val bounds = listBounds
                                if (bounds != null) {
                                    if (!bounds.containsWithMargin(point, DragCancelMarginPx)) {
                                        null
                                    } else {
                                        val edgeSize = 96f
                                        val scrollAmount = when {
                                            point.y < bounds.top + edgeSize -> -28f
                                            point.y > bounds.bottom - edgeSize -> 28f
                                            else -> 0f
                                        }
                                        if (scrollAmount != 0f) {
                                            scope.launch { listState.scrollBy(scrollAmount) }
                                        }
                                        updated
                                    }
                                } else {
                                    updated
                                }
                            }
                        },
                        onDragEnd = {
                            val currentDrag = dragState
                            if (currentDrag != null) {
                                val target = targetDropTarget(currentDrag, rowBounds, visibleRows, listBounds)
                                applyDrop(target, currentDrag.node, hierarchy, onMoveToIndex)
                            }
                            dragState = null
                        },
                    )
                }
            }
            dragState?.let { state ->
                TagDragPreview(state, dragLayerBounds)
            }
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
            Text(clip.clip.text, maxLines = 8, overflow = TextOverflow.Ellipsis)
            if (clip.assets.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                EnhancedMediaGrid(clip.assets.mapNotNull { it.localPath ?: it.previewUrl ?: it.remoteUrl })
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
                        Text("分類")
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
    row: VisibleTagRow,
    hierarchy: TagHierarchy,
    expanded: MutableMap<Long, Boolean>,
    currentBounds: Rect?,
    highlightedTarget: TagDropTarget?,
    draggedNode: TagNodeRef?,
    onBounds: (Rect) -> Unit,
    onToggleExpanded: () -> Unit,
    onMove: (TagNodeRef, Long?) -> Unit,
    onCreate: (TagNodeType, Long?) -> Unit,
    onRenameTag: (TagEntity, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
    onDragStart: (Offset, Rect?) -> Unit,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    var renameOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var moveOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var deleteOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var addAllOpen by remember(row.node.ref()) { mutableStateOf(false) }
    var menuOpen by remember(row.node.ref()) { mutableStateOf(false) }
    val isDragged = draggedNode == row.node.ref()
    val isTargetGroup = row.node is TagGroupNode && highlightedTarget == TagDropTarget.IntoGroup(row.node.id)
    val rowColor = when {
        isTargetGroup -> MaterialTheme.colorScheme.primaryContainer
        isDragged -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Column(Modifier.fillMaxWidth()) {
        if (highlightedTarget == TagDropTarget.Before(row.parentGroupId, row.indexInParent)) {
            TagInsertLine(row.depth)
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = rowColor),
            modifier = Modifier
                .padding(start = (row.depth * 14).dp)
                .fillMaxWidth()
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .pointerInput(row.node.ref()) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart(it, currentBounds) },
                        onDragCancel = onDragEnd,
                        onDragEnd = onDragEnd,
                    ) { change, dragAmount ->
                        change.consume()
                        onDragDelta(dragAmount)
                    }
                }
                .alpha(if (isDragged) 0.35f else 1f)
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
        if (highlightedTarget == TagDropTarget.After(row.parentGroupId, row.indexInParent)) {
            TagInsertLine(row.depth)
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
private fun TagInsertLine(depth: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .padding(start = (depth * 14).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun TagDragPreview(state: DragState, dragLayerBounds: Rect?) {
    val density = LocalDensity.current
    val layerLeft = dragLayerBounds?.left ?: 0f
    val layerTop = dragLayerBounds?.top ?: 0f
    val offset = IntOffset(
        (state.originBounds.left + state.delta.x - layerLeft).roundToInt(),
        (state.originBounds.top + state.delta.y - layerTop).roundToInt(),
    )
    Box(
        Modifier
            .offset { offset }
            .width(with(density) { state.originBounds.width.toDp() })
            .height(with(density) { state.originBounds.height.toDp() })
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

private fun DragState.pointerPosition(): Offset = Offset(
    originBounds.left + anchor.x + delta.x,
    originBounds.top + anchor.y + delta.y,
)

private const val DragCancelMarginPx = 24f

private fun Rect.containsWithMargin(point: Offset, margin: Float): Boolean =
    point.x >= left - margin &&
        point.x <= right + margin &&
        point.y >= top - margin &&
        point.y <= bottom + margin

private fun targetDropTarget(
    dragState: DragState,
    rowBounds: Map<TagNodeRef, Rect>,
    visibleRows: List<VisibleTagRow>,
    listBounds: Rect?,
): TagDropTarget? {
    val point = dragState.pointerPosition()
    if (listBounds != null && !listBounds.containsWithMargin(point, DragCancelMarginPx)) return null
    val rowsWithBounds = visibleRows.mapNotNull { row ->
        rowBounds[row.node.ref()]?.let { row to it }
    }
    if (rowsWithBounds.isEmpty()) return null
    val first = rowsWithBounds.first()
    if (point.y < first.second.top) return TagDropTarget.Before(first.first.parentGroupId, first.first.indexInParent)
    val last = rowsWithBounds.last()
    if (point.y > last.second.bottom) return TagDropTarget.After(last.first.parentGroupId, last.first.indexInParent)

    rowsWithBounds.zipWithNext().forEach { (previous, next) ->
        if (point.y > previous.second.bottom && point.y < next.second.top) {
            return TagDropTarget.Before(next.first.parentGroupId, next.first.indexInParent)
        }
    }

    rowsWithBounds.forEach { (row, bounds) ->
        if (point.y in bounds.top..bounds.bottom) {
            if (row.node is TagGroupNode) {
                val edgeZone = minOf(18f, bounds.height * 0.20f)
                val centerTop = bounds.top + edgeZone
                val centerBottom = bounds.bottom - edgeZone
                if (point.y in centerTop..centerBottom) return TagDropTarget.IntoGroup(row.node.id)
            }
            return if (point.y < bounds.center.y) {
                TagDropTarget.Before(row.parentGroupId, row.indexInParent)
            } else {
                nextSiblingRow(visibleRows, row)?.let {
                    TagDropTarget.Before(it.parentGroupId, it.indexInParent)
                } ?: TagDropTarget.After(row.parentGroupId, row.indexInParent)
            }
        }
    }
    return null
}

private fun nextSiblingRow(visibleRows: List<VisibleTagRow>, row: VisibleTagRow): VisibleTagRow? =
    visibleRows.firstOrNull {
        it.parentGroupId == row.parentGroupId && it.indexInParent == row.indexInParent + 1
    }

private fun applyDrop(
    target: TagDropTarget?,
    source: TagNodeRef,
    hierarchy: TagHierarchy,
    onMoveToIndex: (TagNodeRef, Long?, Int) -> Unit,
) {
    when (target) {
        null -> Unit
        is TagDropTarget.IntoGroup -> {
            val destinationCount = hierarchy.children(target.groupId).size
            onMoveToIndex(source, target.groupId, destinationCount)
        }
        is TagDropTarget.Before -> onMoveToIndex(source, target.parentGroupId, target.index)
        is TagDropTarget.After -> onMoveToIndex(source, target.parentGroupId, target.index + 1)
    }
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
fun EnhancedMediaGrid(urls: List<String>) {
    val shown = urls.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        when (shown.size) {
            1 -> EnhancedMediaCell(shown[0], Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEach { EnhancedMediaCell(it, Modifier.weight(1f).aspectRatio(1f)) }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.take(2).forEach { EnhancedMediaCell(it, Modifier.weight(1f).aspectRatio(1f)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.drop(2).forEach { EnhancedMediaCell(it, Modifier.weight(1f).aspectRatio(1f)) }
                    if (shown.size == 3) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EnhancedMediaCell(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}
