package com.lyco256.llm

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.lyco256.llm.data.ApiSettings
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OAuthSession
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageState
import com.lyco256.llm.data.SyncStateEntity
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagFilterState
import com.lyco256.llm.data.TagGroupEntity
import com.lyco256.llm.data.TagGroupNode
import com.lyco256.llm.data.TagHierarchy
import com.lyco256.llm.data.TagLeafNode
import com.lyco256.llm.data.TagNodeRef
import com.lyco256.llm.data.TagNodeType
import com.lyco256.llm.data.TagTreeNode
import com.lyco256.llm.data.TagWithCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private val authorizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (data == null) {
            Toast.makeText(this, "Xの認証がキャンセルされました", Toast.LENGTH_LONG).show()
        } else {
            viewModel.completeAuthorization(data) { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this, MainViewModel.factory(application))[MainViewModel::class.java]
        setContent {
            LikeListManagerUi(
                viewModel = viewModel,
                onLogin = { settings ->
                    viewModel.saveApiSettings(settings) {
                        runCatching { authorizationLauncher.launch(viewModel.createAuthorizationIntent()) }
                            .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
                    }
                },
            )
        }
    }
}

enum class AppTab(val label: String) {
    Unclassified("未分類"),
    Classified("分類"),
    Tags("タグ"),
}

data class MainUiState(
    val clips: List<ClipWithDetails> = emptyList(),
    val tags: List<TagWithCount> = emptyList(),
    val tagHierarchy: TagHierarchy = TagHierarchy(),
    val syncState: SyncStateEntity? = null,
    val apiSettings: ApiSettings = ApiSettings(),
    val oauthSession: OAuthSession? = null,
    val storageState: PostStorageState = PostStorageState(),
    val query: String = "",
    val tagFilters: Map<TagNodeRef, TagFilterState> = emptyMap(),
) {
    val unclassified: List<ClipWithDetails> = clips.filter { it.tags.isEmpty() }
    val classified: List<ClipWithDetails> = clips
        .filter { it.tags.isNotEmpty() }
        .filter { clip -> matchesTagFilters(clip, tagHierarchy, tagFilters) }
        .filter { clip ->
            query.isBlank() ||
                clip.clip.summary.contains(query, ignoreCase = true) ||
                clip.clip.text.contains(query, ignoreCase = true) ||
                clip.clip.authorName.contains(query, ignoreCase = true) ||
                clip.clip.authorUsername.contains(query, ignoreCase = true)
    }
}

private data class RepositoryUiState(
    val clips: List<ClipWithDetails>,
    val tags: List<TagWithCount>,
    val tagHierarchy: TagHierarchy,
    val syncState: SyncStateEntity?,
    val storageState: PostStorageState,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as LikeListManagerApp).container.repository
    private val query = MutableStateFlow("")
    private val tagFilters = MutableStateFlow<Map<TagNodeRef, TagFilterState>>(emptyMap())
    private val apiSettings = MutableStateFlow(ApiSettings())
    private val oauthSession = MutableStateFlow<OAuthSession?>(null)

    private val repositoryState = combine(
        repository.clipsWithDetails,
        repository.tagsWithCount,
        repository.tagHierarchy,
        repository.syncState,
        repository.storageState,
    ) { clips, tags, hierarchy, syncState, storageState ->
        RepositoryUiState(clips, tags, hierarchy, syncState, storageState)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repositoryState,
        apiSettings,
        oauthSession,
        query,
        tagFilters,
    ) { repositoryState, settings, session, queryValue, filters ->
        MainUiState(
            clips = repositoryState.clips,
            tags = repositoryState.tags,
            tagHierarchy = repositoryState.tagHierarchy,
            syncState = repositoryState.syncState,
            apiSettings = settings,
            oauthSession = session,
            storageState = repositoryState.storageState,
            query = queryValue,
            tagFilters = filters,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            apiSettings.value = repository.loadApiSettings()
            oauthSession.value = repository.loadOAuthSession()
            repository.ensureSeedData()
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun cycleTagFilter(node: TagNodeRef) {
        val current = tagFilters.value[node] ?: TagFilterState.NONE
        val next = when (current) {
            TagFilterState.NONE -> TagFilterState.INCLUDED
            TagFilterState.INCLUDED -> TagFilterState.REQUIRED
            TagFilterState.REQUIRED -> TagFilterState.NONE
        }
        tagFilters.value = tagFilters.value.toMutableMap().apply {
            if (next == TagFilterState.NONE) remove(node) else put(node, next)
        }
    }

    fun clearTagFilters() { tagFilters.value = emptyMap() }

    fun createTag(name: String, parentGroupId: Long?, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.createTag(name, parentGroupId) }
    fun createGroup(name: String, parentGroupId: Long?, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.createGroup(name, parentGroupId) }
    fun renameTag(tag: TagEntity, name: String, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.renameTag(tag, name) }
    fun renameGroup(group: TagGroupEntity, name: String, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.renameGroup(group, name) }
    fun deleteTag(tag: TagEntity, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.deleteTag(tag.id) }
    fun deleteGroup(group: TagGroupEntity, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.deleteGroup(group.id) }
    fun moveTagNode(node: TagNodeRef, parentGroupId: Long?, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.moveNode(node, parentGroupId) }
    fun reorderTagNodes(parentGroupId: Long?, nodes: List<TagNodeRef>, onMessage: (String) -> Unit) = tagAction(onMessage) {
        repository.reorderSiblings(parentGroupId, nodes)
    }
    fun addAllFromTagToTag(source: TagEntity, target: TagEntity) = viewModelScope.launch {
        repository.addAllFromTagToTag(source.id, target.id)
    }
    fun setClipTags(clip: ClipEntity, tagIds: Set<Long>) = viewModelScope.launch {
        repository.setClipTags(clip.id, tagIds)
    }
    fun updateSummary(clip: ClipEntity, summary: String) = viewModelScope.launch {
        repository.updateSummary(clip, summary)
    }
    fun moveClipToTrash(clip: ClipEntity) = viewModelScope.launch {
        repository.moveClipToTrash(clip)
    }
    fun saveApiSettings(settings: ApiSettings, onSaved: (() -> Unit)? = null) = viewModelScope.launch {
        repository.saveApiSettings(settings)
        apiSettings.value = repository.loadApiSettings()
        onSaved?.invoke()
    }

    fun createAuthorizationIntent(): Intent = repository.createAuthorizationIntent()

    fun completeAuthorization(intent: Intent, onMessage: (String) -> Unit) = viewModelScope.launch {
        runCatching { repository.completeAuthorization(intent) }
            .onSuccess {
                oauthSession.value = it
                onMessage("@${it.username} でXにログインしました")
            }
            .onFailure { onMessage(it.message ?: "Xの認証に失敗しました") }
    }

    fun logout(onMessage: (String) -> Unit) = viewModelScope.launch {
        repository.logout()
        oauthSession.value = null
        onMessage("Xからログアウトしました")
    }

    fun syncNow(onMessage: (String) -> Unit) = viewModelScope.launch {
        try {
            onMessage(repository.syncNow())
        } catch (error: Exception) {
            oauthSession.value = repository.loadOAuthSession()
            onMessage(error.message ?: "同期に失敗しました")
        }
    }

    private fun tagAction(onMessage: (String) -> Unit, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { onMessage(it.message ?: "タグ操作に失敗しました") }
    }

    fun refreshStorageLocations(onComplete: (() -> Unit)? = null) = viewModelScope.launch {
        repository.refreshStorageLocations()
        onComplete?.invoke()
    }

    fun estimateStorageMove(
        targetId: String,
        onResult: (Result<PostStorageEstimate>) -> Unit,
    ) = viewModelScope.launch {
        onResult(runCatching { repository.estimateStorageMove(targetId) })
    }

    fun movePostStorage(targetId: String, onMessage: (String) -> Unit) = viewModelScope.launch {
        repository.movePostStorage(targetId)
            .onSuccess { onMessage("投稿データの保存先を変更しました") }
            .onFailure { onMessage(it.message ?: "保存先の変更に失敗しました") }
    }

    fun clearApiSettings() = viewModelScope.launch {
        repository.clearApiSettings()
        apiSettings.value = repository.loadApiSettings()
        oauthSession.value = null
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
    }
}

@Composable
fun LikeListManagerUi(viewModel: MainViewModel, onLogin: (ApiSettings) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7DB7FF),
            secondary = Color(0xFFFFCF70),
            background = Color(0xFF101316),
            surface = Color(0xFF171B20),
            surfaceVariant = Color(0xFF20262D),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            MainScreen(uiState, viewModel, onLogin)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(uiState: MainUiState, viewModel: MainViewModel, onLogin: (ApiSettings) -> Unit) {
    var tab by remember { mutableStateOf(AppTab.Unclassified) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var storageOpen by remember { mutableStateOf(false) }
    var storageEstimate by remember { mutableStateOf<PostStorageEstimate?>(null) }
    var storageEstimating by remember { mutableStateOf(false) }
    var storageMoveStarting by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("like list manager", fontWeight = FontWeight.SemiBold) },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Text("...")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("同期する") },
                                onClick = {
                                    viewModel.syncNow { syncMessage = it }
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("同期/使用量") },
                                onClick = {
                                    usageOpen = true
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("投稿データの保存先") },
                                onClick = {
                                    viewModel.refreshStorageLocations()
                                    storageOpen = true
                                    menuOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("X API設定") },
                                onClick = {
                                    settingsOpen = true
                                    menuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                        icon = { Text(tabIcon(item)) },
                    )
                }
            }
        },
    ) { padding ->
        if (uiState.storageState.isMigrating) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("投稿データを移動しています", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("完了するまでアプリを閉じずにお待ちください")
            }
        } else if (!uiState.storageState.isAvailable) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("選択したSDカードを利用できません", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Text("SDカードを再装着するか、メニューから投稿データの保存先を変更してください")
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    viewModel.refreshStorageLocations()
                    storageOpen = true
                }) { Text("保存先を確認") }
            }
        } else when (tab) {
            AppTab.Unclassified -> ClipListScreen(
                title = "未分類",
                clips = uiState.unclassified,
                hierarchy = uiState.tagHierarchy,
                emptyText = "タグなしのツイートはありません",
                modifier = Modifier.padding(padding),
                requireTagConfirmation = true,
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onDelete = viewModel::moveClipToTrash,
            )
            AppTab.Classified -> ClassifiedScreen(
                uiState = uiState,
                modifier = Modifier.padding(padding),
                onQueryChange = viewModel::setQuery,
                onTagFilterChange = viewModel::cycleTagFilter,
                onClearTagFilters = viewModel::clearTagFilters,
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onDelete = viewModel::moveClipToTrash,
            )
            AppTab.Tags -> TagListScreen(
                hierarchy = uiState.tagHierarchy,
                modifier = Modifier.padding(padding),
                onCreateTag = { name, parent -> viewModel.createTag(name, parent) { syncMessage = it } },
                onCreateGroup = { name, parent -> viewModel.createGroup(name, parent) { syncMessage = it } },
                onRenameTag = { tag, name -> viewModel.renameTag(tag, name) { syncMessage = it } },
                onRenameGroup = { group, name -> viewModel.renameGroup(group, name) { syncMessage = it } },
                onDeleteTag = { tag -> viewModel.deleteTag(tag) { syncMessage = it } },
                onDeleteGroup = { group -> viewModel.deleteGroup(group) { syncMessage = it } },
                onMove = { node, parent -> viewModel.moveTagNode(node, parent) { syncMessage = it } },
                onReorder = { parent, nodes -> viewModel.reorderTagNodes(parent, nodes) { syncMessage = it } },
                onAddAll = viewModel::addAllFromTagToTag,
            )
        }
    }

    if (usageOpen) UsageDialog(uiState.syncState, uiState.oauthSession, onDismiss = { usageOpen = false })
    if (storageOpen) {
        PostStorageDialog(
            state = uiState.storageState,
            onDismiss = { storageOpen = false },
            onSelect = { targetId ->
                storageEstimating = true
                viewModel.estimateStorageMove(targetId) { result ->
                    storageEstimating = false
                    result.onSuccess { storageEstimate = it }
                        .onFailure { syncMessage = it.message ?: "移動量を確認できませんでした" }
                }
            },
        )
    }
    if (storageEstimating) {
        StorageProgressDialog(
            title = "移動するデータを確認しています",
            message = "投稿件数とファイル容量を計算しています",
        )
    }
    storageEstimate?.let { estimate ->
        AlertDialog(
            onDismissRequest = { storageEstimate = null },
            title = { Text("投稿データを移動しますか？") },
            text = {
                Text(
                    "移動先: ${estimate.target.displayName}\n" +
                        "投稿: ${estimate.clipCount} 件\n" +
                        "ファイル: ${estimate.fileCount} 件\n" +
                        "容量: ${formatBytes(estimate.totalBytes)}\n\n" +
                        "移動中は同期と編集を一時停止します。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetId = estimate.target.id
                    storageEstimate = null
                    storageOpen = false
                    storageMoveStarting = true
                    viewModel.movePostStorage(targetId) {
                        storageMoveStarting = false
                        syncMessage = it
                    }
                }) { Text("移動する") }
            },
            dismissButton = { TextButton(onClick = { storageEstimate = null }) { Text("キャンセル") } },
        )
    }
    if (storageMoveStarting && !uiState.storageState.isMigrating) {
        StorageProgressDialog(
            title = "移動を開始しています",
            message = "保存先を準備しています",
        )
    }
    syncMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { syncMessage = null },
            title = { Text("同期結果") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { syncMessage = null }) { Text("閉じる") } },
        )
    }
    if (settingsOpen) {
        ApiSettingsDialog(
            initial = uiState.apiSettings,
            session = uiState.oauthSession,
            onDismiss = { settingsOpen = false },
            onSave = {
                viewModel.saveApiSettings(it)
                settingsOpen = false
            },
            onClear = viewModel::clearApiSettings,
            onLogin = onLogin,
            onLogout = { viewModel.logout { syncMessage = it } },
        )
    }
}

internal fun matchesTagFilters(
    clip: ClipWithDetails,
    hierarchy: TagHierarchy,
    filters: Map<TagNodeRef, TagFilterState>,
): Boolean {
    if (filters.isEmpty()) return true
    val clipTagIds = clip.tags.mapTo(mutableSetOf()) { it.id }
    fun matches(ref: TagNodeRef): Boolean {
        val targetIds = when (ref.type) {
            TagNodeType.TAG -> setOf(ref.id)
            TagNodeType.GROUP -> hierarchy.descendantTagIdsByGroup[ref.id].orEmpty()
        }
        return clipTagIds.any { it in targetIds }
    }
    val required = filters.filterValues { it == TagFilterState.REQUIRED }.keys
    val included = filters.filterValues { it == TagFilterState.INCLUDED }.keys
    return required.all(::matches) && (included.isEmpty() || included.any(::matches))
}

@Composable
fun PostStorageDialog(
    state: PostStorageState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("投稿データの保存先") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("投稿、タグ、概要、同期状態、保存画像を同じストレージへ保存します。認証情報とアプリ設定は内部ストレージに残ります。")
                }
                if (state.isRefreshing) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(12.dp))
                            Text("使用容量を計算しています")
                        }
                    }
                }
                items(state.locations, key = { it.id }) { location ->
                    StorageLocationCard(
                        location = location,
                        enabled = !state.isRefreshing && !state.isMigrating,
                        onSelect = onSelect,
                    )
                }
                if (state.isMigrating) item { Text(state.migrationMessage ?: "移動中です") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun StorageLocationCard(
    location: PostStorageLocation,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                location.displayName + if (location.isCurrent) "（現在）" else "",
                fontWeight = FontWeight.SemiBold,
            )
            Text(if (location.isAvailable) location.path else "未装着または読み取り不可")
            if (location.isAvailable) {
                Text(
                    "使用中: ${location.usedBytes?.let(::formatBytes) ?: "計算中"} / " +
                        "空き: ${formatBytes(location.freeBytes)}",
                )
            }
            if (!location.isCurrent && location.isAvailable) {
                TextButton(
                    onClick = { onSelect(location.id) },
                    enabled = enabled,
                ) { Text("ここへ移動") }
            }
        }
    }
}

@Composable
private fun StorageProgressDialog(title: String, message: String) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(message)
            }
        },
        confirmButton = {},
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

fun tabIcon(tab: AppTab): String = when (tab) {
    AppTab.Unclassified -> "?"
    AppTab.Classified -> "#"
    AppTab.Tags -> "+"
}

@Composable
fun ClipListScreen(
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
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        if (clips.isEmpty()) {
            EmptyState(emptyText)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(clips, key = { it.clip.id }) { clip ->
                    val selectedTagIds = if (requireTagConfirmation) {
                        pendingTagIds[clip.clip.id] ?: clip.tags.map { it.id }.toSet()
                    } else {
                        clip.tags.map { it.id }.toSet()
                    }
                    TweetCard(
                        clip = clip,
                        hierarchy = hierarchy,
                        expandedGroups = expandedGroups,
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
fun ClassifiedScreen(
    uiState: MainUiState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onTagFilterChange: (TagNodeRef) -> Unit,
    onClearTagFilters: () -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
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
        TagFilterTree(uiState.tagHierarchy, uiState.tagFilters, expandedGroups, onTagFilterChange, onClearTagFilters)
        Spacer(Modifier.height(10.dp))
        if (uiState.classified.isEmpty()) {
            EmptyState("条件に合う分類済みツイートはありません")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.classified, key = { it.clip.id }) { clip ->
                    TweetCard(
                        clip = clip,
                        hierarchy = uiState.tagHierarchy,
                        expandedGroups = expandedGroups,
                        selectedTagIds = clip.tags.map { it.id }.toSet(),
                        onTagSelectionChange = { onTagsChange(clip.clip, it) },
                        onSummaryChange = onSummaryChange,
                        onDelete = onDelete,
                    )
                }
            }
        }
    }
}

@Composable
fun TweetCard(
    clip: ClipWithDetails,
    hierarchy: TagHierarchy,
    expandedGroups: MutableMap<Long, Boolean>,
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
                MediaGrid(clip.assets.mapNotNull { it.localPath ?: it.previewUrl ?: it.remoteUrl })
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
            TagTreePicker(
                hierarchy = hierarchy,
                expandedGroups = expandedGroups,
                selectedIds = selectedTagIds,
                onToggle = { tagId ->
                    val selected = selectedTagIds.toMutableSet()
                    if (!selected.add(tagId)) selected.remove(tagId)
                    onTagSelectionChange(selected)
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
fun MediaGrid(urls: List<String>) {
    val shown = urls.take(4)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        when (shown.size) {
            1 -> MediaCell(shown[0], Modifier.fillMaxWidth().aspectRatio(16f / 10f))
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEach { MediaCell(it, Modifier.weight(1f).aspectRatio(1f)) }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.take(2).forEach { MediaCell(it, Modifier.weight(1f).aspectRatio(1f)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.drop(2).forEach { MediaCell(it, Modifier.weight(1f).aspectRatio(1f)) }
                    if (shown.size == 3) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MediaCell(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

@Composable
fun TagTreePicker(
    hierarchy: TagHierarchy,
    expandedGroups: MutableMap<Long, Boolean>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    if (hierarchy.tags.isEmpty()) {
        Text("タグリストでタグを追加すると、ここから選べます", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    TagPickerChildren(hierarchy, null, 0, expandedGroups, selectedIds, onToggle)
}

@Composable
private fun TagPickerChildren(
    hierarchy: TagHierarchy,
    parentId: Long?,
    depth: Int,
    expandedGroups: MutableMap<Long, Boolean>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hierarchy.children(parentId).forEach { node ->
            when (node) {
                is TagGroupNode -> {
                    val expanded = expandedGroups[node.id] == true
                    TextButton(
                        onClick = { expandedGroups[node.id] = !expanded },
                        modifier = Modifier.padding(start = (depth * 14).dp),
                    ) { Text("${if (expanded) "▼" else "▶"} ${node.name}") }
                    if (expanded) TagPickerChildren(hierarchy, node.id, depth + 1, expandedGroups, selectedIds, onToggle)
                }
                is TagLeafNode -> FilterChip(
                    selected = node.id in selectedIds,
                    onClick = { onToggle(node.id) },
                    modifier = Modifier.padding(start = (depth * 14).dp),
                    label = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}

@Composable
fun TagFilterTree(
    hierarchy: TagHierarchy,
    filters: Map<TagNodeRef, TagFilterState>,
    expandedGroups: MutableMap<Long, Boolean>,
    onCycle: (TagNodeRef) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = filters.isEmpty(), onClick = onClear, label = { Text("すべて") })
        TagFilterChildren(hierarchy, null, 0, filters, expandedGroups, onCycle)
        if (filters.isNotEmpty()) Text("含: いずれか / 必: すべて", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TagFilterChildren(
    hierarchy: TagHierarchy,
    parentId: Long?,
    depth: Int,
    filters: Map<TagNodeRef, TagFilterState>,
    expandedGroups: MutableMap<Long, Boolean>,
    onCycle: (TagNodeRef) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        hierarchy.children(parentId).forEach { node ->
            val ref = node.ref()
            val state = filters[ref] ?: TagFilterState.NONE
            Row(Modifier.padding(start = (depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
                if (node is TagGroupNode) {
                    val expanded = expandedGroups[node.id] == true
                    IconButton(onClick = { expandedGroups[node.id] = !expanded }, modifier = Modifier.size(36.dp)) {
                        Text(if (expanded) "▼" else "▶")
                    }
                } else Spacer(Modifier.width(36.dp))
                FilterChip(
                    selected = state != TagFilterState.NONE,
                    onClick = { onCycle(ref) },
                    label = { Text("${state.shortLabel()}${node.name} (${node.count})") },
                )
            }
            if (node is TagGroupNode && expandedGroups[node.id] == true) {
                TagFilterChildren(hierarchy, node.id, depth + 1, filters, expandedGroups, onCycle)
            }
        }
    }
}

@Composable
fun TagListScreen(
    hierarchy: TagHierarchy,
    modifier: Modifier = Modifier,
    onCreateTag: (String, Long?) -> Unit,
    onCreateGroup: (String, Long?) -> Unit,
    onRenameTag: (TagEntity, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onMove: (TagNodeRef, Long?) -> Unit,
    onReorder: (Long?, List<TagNodeRef>) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var createType by remember { mutableStateOf<TagNodeType?>(null) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("タグリスト", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { createType = TagNodeType.GROUP }) { Text("グループ追加") }
            Button(onClick = { createType = TagNodeType.TAG }) { Text("タグ追加") }
        }
        Text("長押しして上下へドラッグすると同じ階層内で並び替えます", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                TagManagementChildren(
                    hierarchy, null, 0, expanded,
                    onCreateTag, onCreateGroup, onRenameTag, onRenameGroup,
                    onDeleteTag, onDeleteGroup, onMove, onReorder, onAddAll,
                )
            }
        }
    }
    createType?.let { type ->
        CreateNodeDialog(type, null, onDismiss = { createType = null }) { name ->
            if (type == TagNodeType.TAG) onCreateTag(name, null) else onCreateGroup(name, null)
            createType = null
        }
    }
}

@Composable
private fun TagManagementChildren(
    hierarchy: TagHierarchy,
    parentId: Long?,
    depth: Int,
    expanded: MutableMap<Long, Boolean>,
    onCreateTag: (String, Long?) -> Unit,
    onCreateGroup: (String, Long?) -> Unit,
    onRenameTag: (TagEntity, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onMove: (TagNodeRef, Long?) -> Unit,
    onReorder: (Long?, List<TagNodeRef>) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    val children = hierarchy.children(parentId)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        children.forEach { node ->
            var createType by remember(node.ref()) { mutableStateOf<TagNodeType?>(null) }
            var renameOpen by remember(node.ref()) { mutableStateOf(false) }
            var moveOpen by remember(node.ref()) { mutableStateOf(false) }
            var deleteOpen by remember(node.ref()) { mutableStateOf(false) }
            var addAllOpen by remember(node.ref()) { mutableStateOf(false) }
            var menuOpen by remember(node.ref()) { mutableStateOf(false) }
            var dragTotal by remember(node.ref()) { mutableStateOf(0f) }
            Card(
                modifier = Modifier
                    .padding(start = (depth * 14).dp)
                    .fillMaxWidth()
                    .pointerInput(children.map { it.ref() }) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragTotal = 0f },
                            onDragEnd = { dragTotal = 0f },
                            onDragCancel = { dragTotal = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                dragTotal += amount.y
                                if (kotlin.math.abs(dragTotal) >= 44f) {
                                    val refs = children.map { it.ref() }.toMutableList()
                                    val from = refs.indexOf(node.ref())
                                    val to = (from + if (dragTotal > 0) 1 else -1).coerceIn(refs.indices)
                                    if (from != to) {
                                        val moved = refs.removeAt(from)
                                        refs.add(to, moved)
                                        onReorder(parentId, refs)
                                    }
                                    dragTotal = 0f
                                }
                            },
                        )
                    },
            ) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (node is TagGroupNode) {
                        IconButton(onClick = { expanded[node.id] = expanded[node.id] != true }) {
                            Text(if (expanded[node.id] == true) "▼" else "▶")
                        }
                    } else {
                        val tag = (node as TagLeafNode).tag
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(Color(tag.color)))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(node.name, fontWeight = FontWeight.SemiBold)
                        Text("${node.count} 件", style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("操作") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (node is TagGroupNode) {
                                DropdownMenuItem(text = { Text("子グループを追加") }, onClick = { menuOpen = false; createType = TagNodeType.GROUP })
                                DropdownMenuItem(text = { Text("子タグを追加") }, onClick = { menuOpen = false; createType = TagNodeType.TAG })
                            } else {
                                DropdownMenuItem(text = { Text("別タグへ一括追加") }, onClick = { menuOpen = false; addAllOpen = true })
                            }
                            DropdownMenuItem(text = { Text("名前を変更") }, onClick = { menuOpen = false; renameOpen = true })
                            DropdownMenuItem(text = { Text("別グループへ移動") }, onClick = { menuOpen = false; moveOpen = true })
                            DropdownMenuItem(text = { Text("削除") }, onClick = { menuOpen = false; deleteOpen = true })
                        }
                    }
                }
            }
            if (node is TagGroupNode && expanded[node.id] == true) {
                TagManagementChildren(
                    hierarchy, node.id, depth + 1, expanded,
                    onCreateTag, onCreateGroup, onRenameTag, onRenameGroup,
                    onDeleteTag, onDeleteGroup, onMove, onReorder, onAddAll,
                )
            }
            createType?.let { type ->
                CreateNodeDialog(type, (node as? TagGroupNode)?.id ?: parentId, onDismiss = { createType = null }) { name ->
                    val parent = (node as? TagGroupNode)?.id ?: parentId
                    if (type == TagNodeType.TAG) onCreateTag(name, parent) else onCreateGroup(name, parent)
                    createType = null
                }
            }
            if (renameOpen) RenameNodeDialog(node.name, onDismiss = { renameOpen = false }) { name ->
                when (node) {
                    is TagGroupNode -> onRenameGroup(node.group, name)
                    is TagLeafNode -> onRenameTag(node.tag, name)
                }
                renameOpen = false
            }
            if (moveOpen) MoveNodeDialog(node, hierarchy.groups, onDismiss = { moveOpen = false }) { parent ->
                onMove(node.ref(), parent)
                moveOpen = false
            }
            if (deleteOpen) ConfirmDialog(
                title = if (node is TagGroupNode) "グループを削除" else "タグを削除",
                message = if (node is TagGroupNode) "空のグループ「${node.name}」を削除します。" else "「${node.name}」の割り当ても外れます。",
                onDismiss = { deleteOpen = false },
                onConfirm = {
                    if (node is TagGroupNode) onDeleteGroup(node.group) else onDeleteTag((node as TagLeafNode).tag)
                    deleteOpen = false
                },
            )
            if (addAllOpen && node is TagLeafNode) AddAllTagsDialog(
                source = node.tag,
                targets = hierarchy.tags.map { it.tag }.filter { it.id != node.id },
                onDismiss = { addAllOpen = false },
                onAddAll = { onAddAll(node.tag, it); addAllOpen = false },
            )
        }
    }
}

@Composable
private fun CreateNodeDialog(type: TagNodeType, parentId: Long?, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember(type, parentId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == TagNodeType.TAG) "タグを追加" else "グループを追加") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("名前") }) },
        confirmButton = { TextButton(onClick = { onCreate(name) }) { Text("追加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun RenameNodeDialog(initialName: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名前を変更") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
private fun MoveNodeDialog(node: TagTreeNode, groups: List<TagGroupEntity>, onDismiss: () -> Unit, onMove: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${node.name}」を移動") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { AssistChip(onClick = { onMove(null) }, label = { Text("ルート") }) }
                items(groups, key = { it.id }) { group ->
                    AssistChip(onClick = { onMove(group.id) }, label = { Text(group.name) })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

private fun TagTreeNode.ref(): TagNodeRef = TagNodeRef(
    if (this is TagGroupNode) TagNodeType.GROUP else TagNodeType.TAG,
    id,
)

private fun TagFilterState.shortLabel(): String = when (this) {
    TagFilterState.NONE -> ""
    TagFilterState.INCLUDED -> "含: "
    TagFilterState.REQUIRED -> "必: "
}

@Composable
fun AddAllTagsDialog(source: TagEntity, targets: List<TagEntity>, onDismiss: () -> Unit, onAddAll: (TagEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグ内容を別タグへ追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("「${source.name}」の全ツイートに追加するタグを選びます。元のタグは残ります。")
                targets.forEach { target -> AssistChip(onClick = { onAddAll(target) }, label = { Text(target.name) }) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
fun UsageDialog(syncState: SyncStateEntity?, session: OAuthSession?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期/使用量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("X認証: ${session?.let { "@${it.username} でログイン中" } ?: "未ログイン"}")
                Text("月間取得数: ${syncState?.monthlyFetchedCount ?: 0} / ${syncState?.monthlyBudgetLimit ?: 1800}")
                Text("警告ライン: ${syncState?.monthlyWarningLimit ?: 1500}")
                Text("停止ライン: ${syncState?.monthlyStopLimit ?: 2000}")
                Text("15分制限: ${syncState?.rateLimitRemaining ?: "-"} / ${syncState?.rateLimitLimit ?: "-"}")
                Text("次回回復: ${syncState?.rateLimitResetEpochSeconds?.let { Instant.ofEpochSecond(it).toString() } ?: "未取得"}")
                Text("最終同期: ${syncState?.lastSyncAt ?: "未同期"}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

@Composable
fun ApiSettingsDialog(
    initial: ApiSettings,
    session: OAuthSession?,
    onDismiss: () -> Unit,
    onSave: (ApiSettings) -> Unit,
    onClear: () -> Unit,
    onLogin: (ApiSettings) -> Unit,
    onLogout: () -> Unit,
) {
    var settings by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("X API設定") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Callback URI: likelistmanager://oauth/x/callback") }
                item {
                    OutlinedTextField(
                        value = settings.clientId,
                        onValueChange = { settings = settings.copy(clientId = it) },
                        label = { Text("OAuth 2.0 Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(session?.let { "ログイン中: ${it.displayName} (@${it.username})" } ?: "Xにはまだログインしていません")
                }
                item {
                    Button(
                        onClick = {
                            if (session == null) onLogin(settings) else onLogout()
                        },
                        enabled = session != null || settings.clientId.isNotBlank(),
                    ) {
                        Text(if (session == null) "保存してXにログイン" else "Xからログアウト")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(settings) }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("消去") }
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("実行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("戻る") } },
    )
}

@Composable
fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
