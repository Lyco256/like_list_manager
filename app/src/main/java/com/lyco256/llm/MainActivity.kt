package com.lyco256.llm

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.lyco256.llm.data.SyncStateEntity
import com.lyco256.llm.data.TagEntity
import com.lyco256.llm.data.TagWithCount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = MainViewModel.factory(application),
            )
            LikeListManagerUi(viewModel)
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
    val syncState: SyncStateEntity? = null,
    val apiSettings: ApiSettings = ApiSettings(),
    val query: String = "",
    val selectedTagId: Long? = null,
) {
    val unclassified: List<ClipWithDetails> = clips.filter { it.tags.isEmpty() }
    val classified: List<ClipWithDetails> = clips
        .filter { it.tags.isNotEmpty() }
        .filter { clip -> selectedTagId == null || clip.tags.any { it.id == selectedTagId } }
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
    val syncState: SyncStateEntity?,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as LikeListManagerApp).container.repository
    private val query = MutableStateFlow("")
    private val selectedTagId = MutableStateFlow<Long?>(null)
    private val apiSettings = MutableStateFlow(ApiSettings())

    private val repositoryState = combine(
        repository.clipsWithDetails,
        repository.tagsWithCount,
        repository.syncState,
    ) { clips, tags, syncState ->
        RepositoryUiState(clips, tags, syncState)
    }

    val uiState: StateFlow<MainUiState> = combine(
        repositoryState,
        apiSettings,
        query,
        selectedTagId,
    ) { repositoryState, settings, queryValue, selected ->
        MainUiState(
            clips = repositoryState.clips,
            tags = repositoryState.tags,
            syncState = repositoryState.syncState,
            apiSettings = settings,
            query = queryValue,
            selectedTagId = selected,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        viewModelScope.launch {
            apiSettings.value = repository.loadApiSettings()
            repository.ensureSeedData()
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSelectedTag(tagId: Long?) {
        selectedTagId.value = tagId
    }

    fun createTag(name: String) = viewModelScope.launch { repository.createTag(name) }
    fun renameTag(tag: TagEntity, name: String) = viewModelScope.launch { repository.renameTag(tag, name) }
    fun deleteTag(tag: TagEntity) = viewModelScope.launch { repository.deleteTag(tag.id) }
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
    fun saveApiSettings(settings: ApiSettings) = viewModelScope.launch {
        repository.saveApiSettings(settings)
        apiSettings.value = repository.loadApiSettings()
    }

    fun syncNow(onMessage: (String) -> Unit) = viewModelScope.launch {
        runCatching { repository.syncNow() }
            .onSuccess(onMessage)
            .onFailure { onMessage(it.message ?: "同期に失敗しました") }
    }

    fun clearApiSettings() = viewModelScope.launch {
        repository.clearApiSettings()
        apiSettings.value = repository.loadApiSettings()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
    }
}

@Composable
fun LikeListManagerUi(viewModel: MainViewModel) {
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
            MainScreen(uiState, viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(uiState: MainUiState, viewModel: MainViewModel) {
    var tab by remember { mutableStateOf(AppTab.Unclassified) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
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
        when (tab) {
            AppTab.Unclassified -> ClipListScreen(
                title = "未分類",
                clips = uiState.unclassified,
                tags = uiState.tags.map { it.tag },
                emptyText = "タグなしのツイートはありません",
                modifier = Modifier.padding(padding),
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onDelete = viewModel::moveClipToTrash,
            )
            AppTab.Classified -> ClassifiedScreen(
                uiState = uiState,
                modifier = Modifier.padding(padding),
                onQueryChange = viewModel::setQuery,
                onTagFilterChange = viewModel::setSelectedTag,
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onDelete = viewModel::moveClipToTrash,
            )
            AppTab.Tags -> TagListScreen(
                tags = uiState.tags,
                modifier = Modifier.padding(padding),
                onCreate = viewModel::createTag,
                onRename = viewModel::renameTag,
                onDelete = viewModel::deleteTag,
                onAddAll = viewModel::addAllFromTagToTag,
            )
        }
    }

    if (usageOpen) UsageDialog(uiState.syncState, uiState.apiSettings, onDismiss = { usageOpen = false })
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
            onDismiss = { settingsOpen = false },
            onSave = {
                viewModel.saveApiSettings(it)
                settingsOpen = false
            },
            onClear = viewModel::clearApiSettings,
        )
    }
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
    tags: List<TagEntity>,
    emptyText: String,
    modifier: Modifier = Modifier,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        if (clips.isEmpty()) {
            EmptyState(emptyText)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(clips, key = { it.clip.id }) { clip ->
                    TweetCard(clip, tags, onTagsChange, onSummaryChange, onDelete)
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
    onTagFilterChange: (Long?) -> Unit,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
    onSummaryChange: (ClipEntity, String) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
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
        TagFilterRow(uiState.tags, uiState.selectedTagId, onTagFilterChange)
        Spacer(Modifier.height(10.dp))
        if (uiState.classified.isEmpty()) {
            EmptyState("条件に合う分類済みツイートはありません")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(uiState.classified, key = { it.clip.id }) { clip ->
                    TweetCard(clip, uiState.tags.map { it.tag }, onTagsChange, onSummaryChange, onDelete)
                }
            }
        }
    }
}

@Composable
fun TweetCard(
    clip: ClipWithDetails,
    allTags: List<TagEntity>,
    onTagsChange: (ClipEntity, Set<Long>) -> Unit,
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
            TagChipRow(
                allTags = allTags,
                selectedIds = clip.tags.map { it.id }.toSet(),
                onToggle = { tagId ->
                    val selected = clip.tags.map { it.id }.toMutableSet()
                    if (!selected.add(tagId)) selected.remove(tagId)
                    onTagsChange(clip.clip, selected)
                },
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { deleteOpen = true }) {
                Text("ローカル削除")
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
fun TagChipRow(allTags: List<TagEntity>, selectedIds: Set<Long>, onToggle: (Long) -> Unit) {
    if (allTags.isEmpty()) {
        Text("タグリストでタグを追加すると、ここから選べます", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        allTags.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tag ->
                    FilterChip(
                        selected = selectedIds.contains(tag.id),
                        onClick = { onToggle(tag.id) },
                        label = { Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

@Composable
fun TagFilterRow(tags: List<TagWithCount>, selectedTagId: Long?, onTagFilterChange: (Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selectedTagId == null, onClick = { onTagFilterChange(null) }, label = { Text("すべて") })
        }
        tags.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    FilterChip(
                        selected = selectedTagId == item.tag.id,
                        onClick = { onTagFilterChange(item.tag.id) },
                        label = { Text("${item.tag.name} (${item.count})") },
                    )
                }
            }
        }
    }
}

@Composable
fun TagListScreen(
    tags: List<TagWithCount>,
    modifier: Modifier = Modifier,
    onCreate: (String) -> Unit,
    onRename: (TagEntity, String) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var newTag by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("タグリスト", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newTag,
                onValueChange = { newTag = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("新しいタグ") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                onCreate(newTag)
                newTag = ""
            }) { Text("追加") }
        }
        Spacer(Modifier.height(12.dp))
        if (tags.isEmpty()) {
            EmptyState("タグはまだありません")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tags, key = { it.tag.id }) { item ->
                    TagRow(item, tags, onRename, onDelete, onAddAll)
                }
            }
        }
    }
}

@Composable
fun TagRow(
    item: TagWithCount,
    allTags: List<TagWithCount>,
    onRename: (TagEntity, String) -> Unit,
    onDelete: (TagEntity) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var editOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var mergeOpen by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(Color(item.tag.color)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.tag.name, fontWeight = FontWeight.SemiBold)
                Text("${item.count} 件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { editOpen = true }) { Text("名称") }
            TextButton(onClick = { mergeOpen = true }) { Text("追加") }
            TextButton(onClick = { deleteOpen = true }) { Text("削除") }
        }
    }
    if (editOpen) RenameTagDialog(item.tag, onDismiss = { editOpen = false }, onRename = onRename)
    if (deleteOpen) {
        ConfirmDialog(
            title = "タグを削除",
            message = "「${item.tag.name}」が付いた ${item.count} 件から割り当ても外れます。削除しますか？",
            onDismiss = { deleteOpen = false },
            onConfirm = {
                onDelete(item.tag)
                deleteOpen = false
            },
        )
    }
    if (mergeOpen) {
        AddAllTagsDialog(
            source = item.tag,
            targets = allTags.map { it.tag }.filter { it.id != item.tag.id },
            onDismiss = { mergeOpen = false },
            onAddAll = {
                onAddAll(item.tag, it)
                mergeOpen = false
            },
        )
    }
}

@Composable
fun RenameTagDialog(tag: TagEntity, onDismiss: () -> Unit, onRename: (TagEntity, String) -> Unit) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグ名を変更") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = {
                onRename(tag, name)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
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
fun UsageDialog(syncState: SyncStateEntity?, settings: ApiSettings, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期/使用量") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("API設定: ${if (settings.hasCompleteOAuth1Credentials) "登録済み" else "未登録"}")
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
fun ApiSettingsDialog(initial: ApiSettings, onDismiss: () -> Unit, onSave: (ApiSettings) -> Unit, onClear: () -> Unit) {
    var settings by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("X API設定") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Callback URI: likelistmanager://oauth/x/callback") }
                item {
                    OutlinedTextField(
                        value = settings.xUserId,
                        onValueChange = { settings = settings.copy(xUserId = it) },
                        label = { Text("X User ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { settings = settings.copy(apiKey = it) },
                        label = { Text("OAuth 1.0a API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = settings.apiKeySecret,
                        onValueChange = { settings = settings.copy(apiKeySecret = it) },
                        label = { Text("API Key Secret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = settings.accessToken,
                        onValueChange = { settings = settings.copy(accessToken = it) },
                        label = { Text("Access Token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = settings.accessTokenSecret,
                        onValueChange = { settings = settings.copy(accessTokenSecret = it) },
                        label = { Text("Access Token Secret") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
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
