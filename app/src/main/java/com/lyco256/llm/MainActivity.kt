package com.lyco256.llm

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Icon
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.lyco256.llm.data.ApiSettings
import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClassifiedClipItem
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.LikeCountRefreshEstimate
import com.lyco256.llm.data.OAuthSession
import com.lyco256.llm.data.MediaGridClipSource
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageState
import com.lyco256.llm.data.SettingsSnapshot
import com.lyco256.llm.data.SyncStateEntity
import com.lyco256.llm.data.TagColorId
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
import com.lyco256.llm.data.tagColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

open class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    protected open val initialAppTab: AppTab = AppTab.Unclassified
    protected open val initialClassifiedDisplayMode: ClassifiedDisplayMode = ClassifiedDisplayMode.Card
    protected open val persistScreenState: Boolean = true
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
                initialTab = initialAppTab,
                initialClassifiedDisplayMode = initialClassifiedDisplayMode,
                persistScreenState = persistScreenState,
            )
        }
    }

    override fun onDestroy() {
        if (::viewModel.isInitialized) viewModel.closeMediaGridTweetDialog()
        super.onDestroy()
    }

}

enum class AppTab(val label: String) {
    Unclassified("未分類"),
    Classified("分類済み"),
    Tags("タグ管理"),
}

enum class ClassifiedDisplayMode {
    Card,
    MediaGrid,
}

enum class SearchMode(val label: String) {
    Literal("リテラル"),
    Regex("正規表現"),
}

enum class SearchTarget(val label: String) {
    Text("本文"),
    Summary("概要"),
    OcrText("OCR"),
    AuthorName("表示名"),
    Username("@ユーザー名"),
}

data class TweetAuthorKey(
    val authorId: String?,
    val username: String,
)

data class TweetAuthorOption(
    val key: TweetAuthorKey,
    val displayName: String,
    val username: String,
    val count: Int,
)

enum class ClassifiedSortPriority(val label: String) {
    TagFirst("タグ優先"),
    UserFirst("ユーザー優先"),
}

enum class ClassifiedSortBase(val label: String) {
    Default("保存順"),
    LikeCount("いいね順"),
    PostTime("投稿時間順"),
}

data class ClassifiedSortState(
    val tagEnabled: Boolean = false,
    val tagDescending: Boolean = false,
    val userEnabled: Boolean = false,
    val userDescending: Boolean = true,
    val priority: ClassifiedSortPriority = ClassifiedSortPriority.TagFirst,
    val baseOrder: ClassifiedSortBase = ClassifiedSortBase.Default,
    val likeCountDescending: Boolean = true,
    val postTimeDescending: Boolean = true,
)

data class TweetFilterState(
    val query: String = "",
    val searchMode: SearchMode = SearchMode.Literal,
    val searchTargets: Set<SearchTarget> = SearchTarget.entries.toSet(),
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val selectedAuthors: Set<TweetAuthorKey> = emptySet(),
    val tagFilters: Map<TagNodeRef, TagFilterState> = emptyMap(),
    val taggedOnly: Boolean = true,
) {
    val regexError: String? = if (searchMode == SearchMode.Regex && query.isNotBlank()) {
        runCatching { Regex(query, RegexOption.IGNORE_CASE) }.exceptionOrNull()?.message ?: null
    } else {
        null
    }

    val hasActiveFilters: Boolean =
        query.isNotBlank() ||
            startDate != null ||
            endDate != null ||
            selectedAuthors.isNotEmpty() ||
            tagFilters.isNotEmpty() ||
            !taggedOnly
}

data class MainUiState(
    val clips: List<ClipWithDetails> = emptyList(),
    val tags: List<TagWithCount> = emptyList(),
    val tagHierarchy: TagHierarchy = TagHierarchy(),
    val syncState: SyncStateEntity? = null,
    val apiSettings: ApiSettings = ApiSettings(),
    val oauthSession: OAuthSession? = null,
    val storageState: PostStorageState = PostStorageState(),
    val settingsSnapshot: SettingsSnapshot = SettingsSnapshot(),
    val filters: TweetFilterState = TweetFilterState(),
    val sort: ClassifiedSortState = ClassifiedSortState(),
) {
    val unclassified: List<ClipWithDetails> by lazy { clips.filter { it.tags.isEmpty() } }
    val authorOptions: List<TweetAuthorOption> by lazy { buildAuthorOptions(clips) }
    val classified: List<ClipWithDetails> by lazy { sortClipsForDisplay(
        clips = filterClipsForSearch(clips, tagHierarchy, filters),
        hierarchy = tagHierarchy,
        filters = filters,
        sort = sort,
    ) }
    val query: String = filters.query
    val tagFilters: Map<TagNodeRef, TagFilterState> = filters.tagFilters
}

data class MediaGridDataKey(
    val sourceRevision: Long,
    val hierarchyRevision: Long,
    val filter: TweetFilterState,
    val sort: ClassifiedSortState,
)

data class ClassifiedMediaGridState(
    val status: MediaGridLoadStatus = MediaGridLoadStatus.Ready,
    val dataKey: MediaGridDataKey? = null,
    val sourceRevision: Long = 0,
    val sourceClipCount: Int = 0,
    val sourceMediaAssetCount: Int = 0,
    val sourceTaggedClipCount: Int = 0,
    val entries: List<MediaGridEntry> = emptyList(),
    val tagIdsByClip: Map<Long, LongArray> = emptyMap(),
    val matchingClipCount: Int = 0,
    val matchingMediaCount: Int = 0,
    val isEmptyByFilter: Boolean = true,
    val hasMatchingClipButNoMedia: Boolean = false,
)

enum class MediaGridLoadStatus { Calculating, Ready }

sealed interface MediaGridTweetDialogState {
    object Closed : MediaGridTweetDialogState
    object Loading : MediaGridTweetDialogState
    data class Loaded(val clip: ClipWithDetails) : MediaGridTweetDialogState
    object NotFound : MediaGridTweetDialogState
}

private data class RepositoryUiState(
    val clips: List<ClipWithDetails>,
    val tags: List<TagWithCount>,
    val tagHierarchy: TagHierarchy,
    val syncState: SyncStateEntity?,
    val storageState: PostStorageState,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContainer = (application as LikeListManagerApp).container
    private val repository = appContainer.repository
    private val filters = MutableStateFlow(TweetFilterState())
    private val sort = MutableStateFlow(ClassifiedSortState())
    private val apiSettings = MutableStateFlow(ApiSettings())
    private val oauthSession = MutableStateFlow<OAuthSession?>(null)
    private val settingsSnapshot = MutableStateFlow(SettingsSnapshot())
    private val mediaGridSource = repository.mediaGridSource
    private val selectedMediaGridClipId = MutableStateFlow<Long?>(null)
    private val mediaGridCache = MediaGridMetadataCache()
    internal val mediaGridSessionCoordinator = MediaGridSessionCoordinator(
        context = application.applicationContext,
        preparer = appContainer.mediaGridImagePreparer,
        imageLoader = appContainer.mediaGridImageLoader,
    ) { assetId, candidate -> repository.recoverMediaGridPreview(assetId, candidate.sourceIdentity) }
    internal val mediaGridSessionState: StateFlow<MediaGridSessionUiState> = mediaGridSessionCoordinator.state

    private val repositoryState = combine(
        repository.clipsWithDetails,
        repository.tagsWithCount,
        repository.tagHierarchy,
        repository.syncState,
        repository.storageState,
    ) { clips, tags, hierarchy, syncState, storageState ->
        RepositoryUiState(clips, tags, hierarchy, syncState, storageState)
    }

    private val uiStateBase = combine(
        repositoryState,
        apiSettings,
        oauthSession,
        filters,
        sort,
    ) { repositoryState, settings, session, filterValue, sortValue ->
        MainUiState(
            clips = repositoryState.clips,
            tags = repositoryState.tags,
            tagHierarchy = repositoryState.tagHierarchy,
            syncState = repositoryState.syncState,
            apiSettings = settings,
            oauthSession = session,
            storageState = repositoryState.storageState,
            filters = filterValue,
            sort = sortValue,
        )
    }

    val uiState: StateFlow<MainUiState> = combine(
        uiStateBase,
        settingsSnapshot,
    ) { baseState, snapshot ->
        baseState.copy(settingsSnapshot = snapshot)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    val classifiedMediaGridState: StateFlow<ClassifiedMediaGridState> = combine(
        mediaGridSource,
        repository.tagHierarchy,
        filters,
        sort,
    ) { source, hierarchy, filterValue, sortValue -> Triple(source, hierarchy, filterValue to sortValue) }
        .transformLatest { (snapshot, hierarchy, conditions) ->
            val (filterValue, sortValue) = conditions
            val effectiveFilter = effectiveMediaGridFilter(filterValue)
            val effectiveSort = effectiveMediaGridSort(sortValue)
            val key = MediaGridCacheKey(snapshot.revision, hierarchy.structuralRevision, effectiveFilter, effectiveSort)
            val cached = mediaGridCache.get(key)
            if (cached != null) {
                emit(cached)
            } else {
                emit(ClassifiedMediaGridState(status = MediaGridLoadStatus.Calculating, dataKey = MediaGridDataKey(snapshot.revision, hierarchy.structuralRevision, effectiveFilter, effectiveSort)))
                emit(prepareMediaGridMetadata(snapshot, hierarchy, effectiveFilter, effectiveSort, mediaGridCache, key))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClassifiedMediaGridState(status = MediaGridLoadStatus.Calculating))

    val mediaGridTweetDialogState: StateFlow<MediaGridTweetDialogState> = selectedMediaGridClipId
        .flatMapLatest { clipId ->
            if (clipId == null) {
                flowOf(MediaGridTweetDialogState.Closed)
            } else {
                repository.observeClipWithDetails(clipId)
                    .map { clip ->
                        clip?.let { MediaGridTweetDialogState.Loaded(it) }
                            ?: MediaGridTweetDialogState.NotFound
                    }
                    .onStart { emit(MediaGridTweetDialogState.Loading) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaGridTweetDialogState.Closed)

    init {
        viewModelScope.launch {
            apiSettings.value = repository.loadApiSettings()
            oauthSession.value = repository.loadOAuthSession()
            if (!BuildConfig.TEST_HARNESS) repository.ensureSeedData()
            refreshSettingsSnapshotInternal()
        }
    }

    fun setQuery(value: String) {
        filters.value = filters.value.copy(query = value)
    }

    fun setSearchMode(value: SearchMode) {
        filters.value = filters.value.copy(searchMode = value)
    }

    fun toggleSearchTarget(target: SearchTarget) {
        val current = filters.value.searchTargets
        val next = if (target in current && current.size > 1) current - target else current + target
        filters.value = filters.value.copy(searchTargets = next)
    }

    fun setDateRange(startDate: LocalDate?, endDate: LocalDate?) {
        filters.value = filters.value.copy(startDate = startDate, endDate = endDate)
    }

    fun toggleAuthorFilter(key: TweetAuthorKey) {
        val current = filters.value.selectedAuthors
        filters.value = filters.value.copy(
            selectedAuthors = if (key in current) current - key else current + key,
        )
    }

    fun clearAuthorFilters() {
        filters.value = filters.value.copy(selectedAuthors = emptySet())
    }

    fun setTaggedOnly(value: Boolean) {
        filters.value = filters.value.copy(taggedOnly = value)
    }

    fun filterByAuthorFromClip(clip: ClipEntity) {
        filters.value = filters.value.copy(
            taggedOnly = false,
            selectedAuthors = filters.value.selectedAuthors + clip.authorKey(),
        )
    }

    fun cycleTagFilter(node: TagNodeRef) {
        val current = filters.value.tagFilters[node] ?: TagFilterState.NONE
        val next = when (current) {
            TagFilterState.NONE -> TagFilterState.INCLUDED
            TagFilterState.INCLUDED -> if (node.type == TagNodeType.GROUP) TagFilterState.EXCLUDED else TagFilterState.REQUIRED
            TagFilterState.REQUIRED -> TagFilterState.EXCLUDED
            TagFilterState.EXCLUDED -> TagFilterState.NONE
        }
        filters.value = filters.value.copy(tagFilters = filters.value.tagFilters.toMutableMap().apply {
            if (next == TagFilterState.NONE) remove(node) else put(node, next)
        })
    }

    fun clearTagFilters() { filters.value = filters.value.copy(tagFilters = emptyMap()) }

    fun clearAllFilters() { filters.value = TweetFilterState() }

    fun openMediaGridTweetDialog(clipId: Long) {
        selectedMediaGridClipId.value = clipId
    }

    fun closeMediaGridTweetDialog() {
        selectedMediaGridClipId.value = null
    }

    fun applyFilters(value: TweetFilterState) { filters.value = value }

    fun applySort(value: ClassifiedSortState) { sort.value = value }

    fun clearSort() { sort.value = ClassifiedSortState() }

    fun createTag(name: String, parentGroupId: Long?, colorId: String, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.createTag(name, parentGroupId, colorId) }
    fun createGroup(name: String, parentGroupId: Long?, colorId: String, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.createGroup(name, parentGroupId, colorId) }
    fun renameTag(tag: TagEntity, name: String, colorId: String, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.renameTag(tag.copy(colorId = colorId), name) }
    fun renameGroup(group: TagGroupEntity, name: String, colorId: String, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.renameGroup(group.copy(colorId = colorId), name) }
    fun deleteTag(tag: TagEntity, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.deleteTag(tag.id) }
    fun deleteGroup(group: TagGroupEntity, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.deleteGroup(group.id) }
    fun moveTagNode(node: TagNodeRef, parentGroupId: Long?, onMessage: (String) -> Unit) = tagAction(onMessage) { repository.moveNode(node, parentGroupId) }
    fun moveTagNodeToIndex(node: TagNodeRef, parentGroupId: Long?, index: Int, onMessage: (String) -> Unit) = tagAction(onMessage) {
        repository.moveNodeToParentAtSlot(node, parentGroupId, index)
    }
    fun reorderTagNodes(parentGroupId: Long?, nodes: List<TagNodeRef>, onMessage: (String) -> Unit) = tagAction(onMessage) {
        repository.reorderSiblings(parentGroupId, nodes)
    }
    fun addAllFromTagToTag(source: TagEntity, target: TagEntity) = viewModelScope.launch {
        repository.addAllFromTagToTag(source.id, target.id)
    }
    fun setClipTags(clip: ClipEntity, tagIds: Set<Long>) = viewModelScope.launch {
        repository.setClipTags(clip.id, tagIds)
    }
    fun applyClipTagChanges(
        clipIds: Set<Long>,
        addTagIds: Set<Long>,
        removeTagIds: Set<Long>,
        onResult: (String?) -> Unit,
    ) = viewModelScope.launch {
        runCatching { repository.applyClipTagChanges(clipIds, addTagIds, removeTagIds) }
            .onSuccess { onResult(null) }
            .onFailure { onResult(it.message ?: "タグの適用に失敗しました") }
    }
    fun updateSummary(clip: ClipEntity, summary: String) = viewModelScope.launch {
        repository.updateSummary(clip, summary)
    }

    fun updateOcrText(clip: ClipEntity, ocrText: String) = viewModelScope.launch {
        repository.updateOcrText(clip, ocrText)
    }

    fun detectOcrText(
        clip: ClipWithDetails,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit,
    ) = viewModelScope.launch {
        runCatching { repository.detectOcrText(clip) }
            .onSuccess(onSuccess)
            .onFailure { onFailure(it.message ?: "画像認識に失敗しました") }
    }

    fun moveClipToTrash(clip: ClipEntity) = viewModelScope.launch {
        repository.moveClipToTrash(clip)
    }
    fun saveApiSettings(settings: ApiSettings, onSaved: (() -> Unit)? = null) = viewModelScope.launch {
        repository.saveApiSettings(settings)
        apiSettings.value = repository.loadApiSettings()
        onSaved?.invoke()
    }

    fun replaceApiSettings(settings: ApiSettings, onSaved: (() -> Unit)? = null) = viewModelScope.launch {
        repository.clearApiSettings()
        repository.saveApiSettings(settings)
        apiSettings.value = repository.loadApiSettings()
        oauthSession.value = null
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
        val revokeFailed = repository.logout()
        oauthSession.value = null
        onMessage(
            if (revokeFailed) {
                "X側の解除確認には失敗した可能性があります。\nこの端末のログイン情報は削除済みです。"
            } else {
                "Xからログアウトしました"
            },
        )
    }

    fun syncNow(onMessage: (String) -> Unit) = viewModelScope.launch {
        try {
            onMessage(repository.syncNow())
        } catch (error: Exception) {
            oauthSession.value = repository.loadOAuthSession()
            onMessage(error.message ?: "同期に失敗しました")
        } finally {
            refreshSettingsSnapshotInternal()
        }
    }

    fun estimateLikeCountRefresh(onResult: (Result<LikeCountRefreshEstimate>) -> Unit) = viewModelScope.launch {
        onResult(runCatching { repository.estimateLikeCountRefresh() })
    }

    fun refreshLikeCounts(onMessage: (String) -> Unit) = viewModelScope.launch {
        try {
            onMessage(repository.refreshLikeCounts())
        } catch (error: Exception) {
            oauthSession.value = repository.loadOAuthSession()
            onMessage(error.message ?: "いいね数の再取得に失敗しました")
        } finally {
            refreshSettingsSnapshotInternal()
        }
    }

    private fun tagAction(onMessage: (String) -> Unit, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { onMessage(it.message ?: "画像認識に失敗しました") }
    }

    fun refreshStorageLocations(onComplete: (() -> Unit)? = null) = viewModelScope.launch {
        refreshSettingsSnapshotInternal()
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
            .onSuccess {
                refreshSettingsSnapshotInternal()
                onMessage("投稿データの保存先を変更しました")
            }
            .onFailure { onMessage(it.message ?: "保存先の変更に失敗しました") }
    }

    fun clearApiSettings() = viewModelScope.launch {
        repository.clearApiSettings()
        apiSettings.value = repository.loadApiSettings()
        oauthSession.value = null
    }

    fun refreshSettingsSnapshot() = viewModelScope.launch {
        refreshSettingsSnapshotInternal()
    }

    private suspend fun refreshSettingsSnapshotInternal() {
        repository.refreshStorageLocations()
        settingsSnapshot.value = repository.loadSettingsSnapshot()
    }

    override fun onCleared() {
        mediaGridSessionCoordinator.dispose()
        super.onCleared()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
    }
}

@Composable
fun LikeListManagerUi(
    viewModel: MainViewModel,
    onLogin: (ApiSettings) -> Unit,
    initialTab: AppTab = AppTab.Unclassified,
    initialClassifiedDisplayMode: ClassifiedDisplayMode = ClassifiedDisplayMode.Card,
    persistScreenState: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsState()
    val mediaGridTweetDialogState by viewModel.mediaGridTweetDialogState.collectAsState()
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
            MainScreen(
                uiState = uiState,
                viewModel = viewModel,
                onLogin = onLogin,
                initialTab = initialTab,
                initialClassifiedDisplayMode = initialClassifiedDisplayMode,
                persistScreenState = persistScreenState,
            )
            MediaGridTweetDialog(
                state = mediaGridTweetDialogState,
                hierarchy = uiState.tagHierarchy,
                onDismiss = viewModel::closeMediaGridTweetDialog,
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onOcrSave = viewModel::updateOcrText,
                onOcrDetect = viewModel::detectOcrText,
                onDelete = viewModel::moveClipToTrash,
                onAuthorClick = { clip ->
                    viewModel.closeMediaGridTweetDialog()
                    viewModel.filterByAuthorFromClip(clip)
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onLogin: (ApiSettings) -> Unit,
    initialTab: AppTab = AppTab.Unclassified,
    initialClassifiedDisplayMode: ClassifiedDisplayMode = ClassifiedDisplayMode.Card,
    persistScreenState: Boolean = true,
) {
    var tab by if (persistScreenState) {
        rememberSaveable { mutableStateOf(initialTab) }
    } else {
        remember { mutableStateOf(initialTab) }
    }
    var classifiedDisplayMode by if (persistScreenState) {
        rememberSaveable { mutableStateOf(initialClassifiedDisplayMode) }
    } else {
        remember { mutableStateOf(initialClassifiedDisplayMode) }
    }
    var classifiedMediaGridColumnCount by if (persistScreenState) {
        rememberSaveable { mutableStateOf(ClassifiedMediaGridDefaultColumnCount) }
    } else {
        remember { mutableStateOf(ClassifiedMediaGridDefaultColumnCount) }
    }
    var settingsOpen by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var likeRefreshEstimating by remember { mutableStateOf(false) }
    var likeRefreshRunning by remember { mutableStateOf(false) }
    val unclassifiedListState = rememberLazyListState()
    val tagListState = rememberLazyListState()
    val classifiedListStates = remember { mutableMapOf<String, LazyListState>() }
    val classifiedScrollKey = if (classifiedDisplayMode == ClassifiedDisplayMode.Card) {
        uiState.classifiedScrollKey()
    } else {
        "classified-card-inactive"
    }
    val mediaGridState by viewModel.classifiedMediaGridState.collectAsState()
    val mediaGridSessionState by viewModel.mediaGridSessionState.collectAsState()
    val mediaGridLazyState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    LaunchedEffect(mediaGridState, classifiedMediaGridColumnCount, tab, settingsOpen, classifiedDisplayMode) {
        viewModel.mediaGridSessionCoordinator.update(
            mediaGridState,
            classifiedMediaGridColumnCount,
            tab == AppTab.Classified && classifiedDisplayMode == ClassifiedDisplayMode.MediaGrid && !settingsOpen,
        )
    }
    LaunchedEffect(tab, settingsOpen, classifiedDisplayMode) {
        viewModel.mediaGridSessionCoordinator.setVisible(
            tab == AppTab.Classified && classifiedDisplayMode == ClassifiedDisplayMode.MediaGrid && !settingsOpen,
        )
    }
    val classifiedListState = remember(classifiedScrollKey) {
        classifiedListStates.getOrPut(classifiedScrollKey) { LazyListState() }
    }
    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(
            uiState = uiState,
            viewModel = viewModel,
            onBack = { settingsOpen = false },
            onLogin = onLogin,
        )
        return
    }
    val topBarTitle = screenTitle(
        tab = tab,
        uiState = uiState,
    )

    Scaffold(
        modifier = Modifier.testTag("main_screen"),
        topBar = {
            TopAppBar(
                title = {
                    if (tab == AppTab.Unclassified) {
                        Text(
                            buildAnnotatedString {
                                append("未分類 ")
                                withStyle(
                                    SpanStyle(
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                ) {
                                    append("${uiState.unclassified.size}件")
                                }
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Text(topBarTitle, fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            settingsOpen = true
                        },
                        modifier = Modifier.testTag("top_settings_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "設定",
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("tab_${item.name.lowercase()}"),
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
                Text("SDカードを再装着するか、設定画面から投稿データの保存先を変更してください")
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    viewModel.refreshStorageLocations()
                    settingsOpen = true
                }) { Text("保存先を確認") }
            }
        } else when (tab) {
            AppTab.Unclassified -> EnhancedClipListScreen(
                title = "未分類",
                clips = uiState.unclassified,
                hierarchy = uiState.tagHierarchy,
                emptyText = "タグなしのツイートはありません",
                listState = unclassifiedListState,
                modifier = Modifier.padding(padding).testTag("unclassified_screen"),
                requireTagConfirmation = true,
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onOcrSave = viewModel::updateOcrText,
                onOcrDetect = viewModel::detectOcrText,
                onDelete = viewModel::moveClipToTrash,
                onAuthorClick = { clip ->
                    viewModel.filterByAuthorFromClip(clip)
                    tab = AppTab.Classified
                },
            )
            AppTab.Classified -> EnhancedClassifiedScreen(
                uiState = uiState,
                mediaGridState = mediaGridState,
                mediaGridSessionState = mediaGridSessionState,
                listState = classifiedListState,
                mediaGridLazyState = mediaGridLazyState,
                onMediaGridAnchorChange = viewModel.mediaGridSessionCoordinator::saveAnchor,
                displayMode = classifiedDisplayMode,
                mediaGridColumnCount = classifiedMediaGridColumnCount,
                onMediaGridColumnCountChange = { classifiedMediaGridColumnCount = it },
                onMediaGridCellClick = viewModel::openMediaGridTweetDialog,
                onMediaGridBulkTagsChange = viewModel::applyClipTagChanges,
                onToggleDisplayMode = {
                    classifiedDisplayMode = when (classifiedDisplayMode) {
                        ClassifiedDisplayMode.Card -> ClassifiedDisplayMode.MediaGrid
                        ClassifiedDisplayMode.MediaGrid -> ClassifiedDisplayMode.Card
                    }
                },
                modifier = Modifier.padding(padding).testTag("classified_screen"),
                onApplyFilters = viewModel::applyFilters,
                onApplySort = viewModel::applySort,
                onClearAllFilters = viewModel::clearAllFilters,
                onTagsChange = viewModel::setClipTags,
                onSummaryChange = viewModel::updateSummary,
                onOcrSave = viewModel::updateOcrText,
                onOcrDetect = viewModel::detectOcrText,
                onDelete = viewModel::moveClipToTrash,
                onAuthorClick = viewModel::filterByAuthorFromClip,
            )
            AppTab.Tags -> EnhancedTagListScreen(
                hierarchy = uiState.tagHierarchy,
                listState = tagListState,
                modifier = Modifier.padding(padding).testTag("tags_screen"),
                onCreateTag = { name, parent, colorId -> viewModel.createTag(name, parent, colorId) { syncMessage = it } },
                onCreateGroup = { name, parent, colorId -> viewModel.createGroup(name, parent, colorId) { syncMessage = it } },
                onRenameTag = { tag, name, colorId -> viewModel.renameTag(tag, name, colorId) { syncMessage = it } },
                onRenameGroup = { group, name, colorId -> viewModel.renameGroup(group, name, colorId) { syncMessage = it } },
                onDeleteTag = { tag -> viewModel.deleteTag(tag) { syncMessage = it } },
                onDeleteGroup = { group -> viewModel.deleteGroup(group) { syncMessage = it } },
                onMove = { node, parent -> viewModel.moveTagNode(node, parent) { syncMessage = it } },
                onMoveToIndex = { node, parent, index -> viewModel.moveTagNodeToIndex(node, parent, index) { syncMessage = it } },
                onAddAll = viewModel::addAllFromTagToTag,
            )
        }
    }

    if (likeRefreshEstimating || likeRefreshRunning) {
        StorageProgressDialog(
            title = if (likeRefreshRunning) "いいね数を更新しています" else "いいね数の更新を確認しています",
            message = if (likeRefreshRunning) "少しお待ちください" else "ローカル同期を取得しています",
        )
    }
    syncMessage?.let { message ->
        SyncResultDialog(message = message, onDismiss = { syncMessage = null })
    }
}

@Composable
internal fun SyncResultDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("同期結果") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("sync_result_close")) { Text("閉じる") } },
    )
}

internal fun matchesTagFilters(
    clip: ClassifiedClipItem,
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
    val excluded = filters.filterValues { it == TagFilterState.EXCLUDED }.keys
    val required = filters.filterValues { it == TagFilterState.REQUIRED }.keys
    val included = filters.filterValues { it == TagFilterState.INCLUDED }.keys
    return excluded.none(::matches) && required.all(::matches) && (included.isEmpty() || included.any(::matches))
}

internal fun <T : ClassifiedClipItem> filterClipsForSearch(
    clips: List<T>,
    hierarchy: TagHierarchy,
    filters: TweetFilterState,
): List<T> {
    val regex = if (filters.searchMode == SearchMode.Regex && filters.query.isNotBlank()) {
        runCatching { Regex(filters.query, RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
    } else {
        null
    }
    return clips
        .asSequence()
        .filter { clip -> !filters.taggedOnly || clip.tags.isNotEmpty() }
        .filter { clip -> matchesDateRange(clip.clip, filters.startDate, filters.endDate) }
        .filter { clip -> matchesAuthors(clip.clip, filters.selectedAuthors) }
        .filter { clip -> matchesTagFilters(clip, hierarchy, filters.tagFilters) }
        .filter { clip -> matchesTextSearch(clip.clip, filters.query, filters.searchMode, filters.searchTargets, regex) }
        .toList()
}

private fun matchesDateRange(clip: ClipEntity, startDate: LocalDate?, endDate: LocalDate?): Boolean {
    if (startDate == null && endDate == null) return true
    val date = clip.postedLocalDate() ?: return false
    return (startDate == null || !date.isBefore(startDate)) && (endDate == null || !date.isAfter(endDate))
}

private fun matchesAuthors(clip: ClipEntity, selectedAuthors: Set<TweetAuthorKey>): Boolean {
    if (selectedAuthors.isEmpty()) return true
    return clip.authorKey() in selectedAuthors
}

private fun matchesTextSearch(
    clip: ClipEntity,
    query: String,
    mode: SearchMode,
    targets: Set<SearchTarget>,
    regex: Regex?,
): Boolean {
    val cleanQuery = query.trim()
    if (cleanQuery.isBlank()) return true
    val values = buildList {
        if (SearchTarget.Text in targets) add(clip.text)
        if (SearchTarget.Summary in targets) add(clip.summary)
        if (SearchTarget.OcrText in targets) add(clip.ocrText)
        if (SearchTarget.AuthorName in targets) add(clip.authorName)
        if (SearchTarget.Username in targets) add(clip.authorUsername)
    }
    if (values.isEmpty()) return false
    return when (mode) {
        SearchMode.Literal -> values.any { it.contains(cleanQuery, ignoreCase = true) }
        SearchMode.Regex -> regex?.let { compiled -> values.any { compiled.containsMatchIn(it) } } ?: false
    }
}

private fun buildAuthorOptions(clips: List<out ClassifiedClipItem>): List<TweetAuthorOption> =
    clips.groupBy { it.clip.authorKey() }
        .map { (key, authorClips) ->
            val latest = authorClips.first().clip
            TweetAuthorOption(
                key = key,
                displayName = latest.authorName,
                username = latest.authorUsername,
                count = authorClips.size,
            )
        }
        .sortedWith(compareBy<TweetAuthorOption> { it.displayName.lowercase() }.thenBy { it.username.lowercase() })

internal fun <T : ClassifiedClipItem> sortClipsForDisplay(
    clips: List<T>,
    hierarchy: TagHierarchy,
    filters: TweetFilterState,
    sort: ClassifiedSortState,
): List<T> {
    if (clips.size <= 1) return clips

    val authorCounts = clips.groupingBy { it.clip.authorKey() }.eachCount()
    val tagOrderIndexById = hierarchy.tagDisplayOrderIndexById()
    val candidateTagIds = filters.sortTagCandidateIds(hierarchy)

    return clips.sortedWith(java.util.Comparator { left, right ->
        compareClassifiedClips(
            left = left,
            right = right,
            sort = sort,
            authorCounts = authorCounts,
            tagOrderIndexById = tagOrderIndexById,
            candidateTagIds = candidateTagIds,
        )
    })
}

private fun <T : ClassifiedClipItem> compareClassifiedClips(
    left: T,
    right: T,
    sort: ClassifiedSortState,
    authorCounts: Map<TweetAuthorKey, Int>,
    tagOrderIndexById: Map<Long, Int>,
    candidateTagIds: Set<Long>,
): Int {
    fun compareTagSort(): Int {
        if (!sort.tagEnabled) return 0
        val leftKey = left.representativeTagOrder(tagOrderIndexById, candidateTagIds)
        val rightKey = right.representativeTagOrder(tagOrderIndexById, candidateTagIds)
        return compareNullableInt(leftKey, rightKey, sort.tagDescending)
    }

    fun compareUserSort(): Int {
        if (!sort.userEnabled) return 0
        val leftCount = authorCounts[left.clip.authorKey()] ?: 0
        val rightCount = authorCounts[right.clip.authorKey()] ?: 0
        val primary = if (sort.userDescending) rightCount.compareTo(leftCount) else leftCount.compareTo(rightCount)
        if (primary != 0) return primary

        val name = left.clip.authorUsername.lowercase().compareTo(right.clip.authorUsername.lowercase())
        if (name != 0) return name

        val id = left.clip.authorId.orEmpty().compareTo(right.clip.authorId.orEmpty())
        if (id != 0) return id

        val savedAt = right.clip.savedAt.compareTo(left.clip.savedAt)
        if (savedAt != 0) return savedAt

        return left.clip.id.compareTo(right.clip.id)
    }

    fun compareBaseSort(): Int = when (sort.baseOrder) {
        ClassifiedSortBase.Default -> 0
        ClassifiedSortBase.LikeCount -> {
            val leftLike = left.clip.likeCount.sortableLikeCount()
            val rightLike = right.clip.likeCount.sortableLikeCount()
            if (sort.likeCountDescending) rightLike.compareTo(leftLike) else leftLike.compareTo(rightLike)
        }
        ClassifiedSortBase.PostTime -> {
            val leftPost = left.clip.postTimeMillisOrNull().sortablePostTime()
            val rightPost = right.clip.postTimeMillisOrNull().sortablePostTime()
            if (sort.postTimeDescending) rightPost.compareTo(leftPost) else leftPost.compareTo(rightPost)
        }
    }

    val first = when {
        sort.tagEnabled && sort.userEnabled -> when (sort.priority) {
            ClassifiedSortPriority.TagFirst -> compareTagSort().takeIf { it != 0 } ?: compareUserSort()
            ClassifiedSortPriority.UserFirst -> compareUserSort().takeIf { it != 0 } ?: compareTagSort()
        }
        sort.tagEnabled -> compareTagSort()
        sort.userEnabled -> compareUserSort()
        else -> 0
    }
    if (first != 0) return first

    val base = compareBaseSort()
    if (base != 0) return base

    val savedAt = right.clip.savedAt.compareTo(left.clip.savedAt)
    if (savedAt != 0) return savedAt

    return left.clip.id.compareTo(right.clip.id)
}

private fun compareNullableInt(left: Int?, right: Int?, descending: Boolean): Int = when {
    left == null && right == null -> 0
    left == null -> if (descending) -1 else 1
    right == null -> if (descending) 1 else -1
    descending -> right.compareTo(left)
    else -> left.compareTo(right)
}

private fun ClassifiedClipItem.representativeTagOrder(
    tagOrderIndexById: Map<Long, Int>,
    candidateTagIds: Set<Long>,
): Int? {
    val candidateTags = tags.filter { candidateTagIds.isEmpty() || it.id in candidateTagIds }
    if (candidateTags.isEmpty()) return null
    val representativeTag = candidateTags.minByOrNull { tagOrderIndexById[it.id] ?: Int.MAX_VALUE } ?: return null
    return tagOrderIndexById[representativeTag.id]
}

private fun TweetFilterState.sortTagCandidateIds(hierarchy: TagHierarchy): Set<Long> {
    val relevant = tagFilters.filterValues { it == TagFilterState.INCLUDED || it == TagFilterState.REQUIRED }
    if (relevant.isEmpty()) return emptySet()
    return buildSet {
        relevant.forEach { (ref, _) ->
            when (ref.type) {
                TagNodeType.TAG -> add(ref.id)
                TagNodeType.GROUP -> addAll(hierarchy.descendantTagIdsByGroup[ref.id].orEmpty())
            }
        }
    }
}

private fun TagHierarchy.tagDisplayOrderIndexById(): Map<Long, Int> {
    val result = mutableMapOf<Long, Int>()
    var index = 0
    fun visit(parentGroupId: Long?) {
        children(parentGroupId).forEach { node ->
            when (node) {
                is TagGroupNode -> visit(node.id)
                is TagLeafNode -> result[node.id] = index++
            }
        }
    }
    visit(null)
    return result
}

private fun Long?.sortableLikeCount(): Long = this ?: Long.MIN_VALUE

private fun Long?.sortablePostTime(): Long = this ?: Long.MIN_VALUE

private fun ClipEntity.postTimeMillisOrNull(): Long? = runCatching { Instant.parse(xCreatedAt).toEpochMilli() }.getOrNull()

internal fun sortConditionSummary(sort: ClassifiedSortState): String {
    val parts = mutableListOf<String>()
    val tagLabel = if (sort.tagDescending) "タグ下から" else "タグ上から"
    val userLabel = if (sort.userDescending) "ユーザー件数多い順" else "ユーザー件数少ない順"
    val likeLabel = if (sort.likeCountDescending) "いいね多い順" else "いいね少ない順"
    val postLabel = if (sort.postTimeDescending) "投稿時間新しい順" else "投稿時間古い順"

    when {
        sort.tagEnabled && sort.userEnabled -> when (sort.priority) {
            ClassifiedSortPriority.TagFirst -> {
                parts += tagLabel
                parts += userLabel
            }
            ClassifiedSortPriority.UserFirst -> {
                parts += userLabel
                parts += tagLabel
            }
        }
        sort.tagEnabled -> parts += tagLabel
        sort.userEnabled -> parts += userLabel
    }

    when (sort.baseOrder) {
        ClassifiedSortBase.Default -> if (parts.isEmpty()) parts += "保存順"
        ClassifiedSortBase.LikeCount -> parts += likeLabel
        ClassifiedSortBase.PostTime -> parts += postLabel
    }

    return "並び:${parts.joinToString(" → ")}"
}

internal fun classifiedConditionSummary(
    filters: TweetFilterState,
    sort: ClassifiedSortState,
    hierarchy: TagHierarchy,
    authors: List<TweetAuthorOption>,
): String = listOf(
    filterConditionSummary(filters, hierarchy, authors),
    sortConditionSummary(sort),
).joinToString(" / ")

private fun ClipEntity.authorKey(): TweetAuthorKey =
    TweetAuthorKey(authorId = authorId?.takeIf { it.isNotBlank() }, username = authorUsername.lowercase())

private fun ClipEntity.postedLocalDate(): LocalDate? =
    try {
        Instant.parse(xCreatedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (_: DateTimeParseException) {
        null
    }

@Composable
internal fun StorageMoveEstimateDialog(
    estimate: PostStorageEstimate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("post_storage_estimate_dialog"),
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
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("post_storage_estimate_confirm"),
            ) { Text("移動する") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("post_storage_estimate_cancel"),
            ) { Text("キャンセル") }
        },
    )
}

@Composable
internal fun StorageProgressDialog(title: String, message: String) {
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

private fun screenTitle(
    tab: AppTab,
    uiState: MainUiState,
): String = when {
    uiState.storageState.isMigrating -> "投稿データ移動中"
    !uiState.storageState.isAvailable -> "投稿データ利用不可"
    tab == AppTab.Unclassified -> "未分類 ${uiState.unclassified.size}件"
    tab == AppTab.Classified -> "分類済み"
    tab == AppTab.Tags -> "タグ管理"
    else -> tab.label
}

private fun MainUiState.classifiedScrollKey(): String {
    val filterKey = filters.tagFilters.entries
        .sortedWith(compareBy({ it.key.type.name }, { it.key.id }))
        .joinToString("|") { "${it.key.type.name}:${it.key.id}:${it.value.name}" }
    val authors = filters.selectedAuthors.sortedWith(compareBy({ it.authorId.orEmpty() }, { it.username }))
        .joinToString("|") { "${it.authorId}:${it.username}" }
    val sortKey = listOf(
        sort.tagEnabled,
        sort.tagDescending,
        sort.userEnabled,
        sort.userDescending,
        sort.priority.name,
        sort.baseOrder.name,
        sort.likeCountDescending,
        sort.postTimeDescending,
    ).joinToString("|")
    return "classified:${filters.query.trim()}:${filters.searchMode.name}:${filters.searchTargets.sortedBy { it.name }}:${filters.startDate}:${filters.endDate}:$authors:${filters.taggedOnly}:$filterKey:$sortKey"
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
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    val pendingTagIds = remember { mutableStateMapOf<Long, Set<Long>>() }
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
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
                        onOcrSave = onOcrSave,
                        onOcrDetect = onOcrDetect,
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
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
) {
    val expandedGroups = remember { mutableStateMapOf<Long, Boolean>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
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
                        onOcrSave = onOcrSave,
                        onOcrDetect = onOcrDetect,
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
    onOcrSave: (ClipEntity, String) -> Unit,
    onOcrDetect: (ClipWithDetails, (String) -> Unit, (String) -> Unit) -> Unit,
    onDelete: (ClipEntity) -> Unit,
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
    val ocrPreviewPaths = remember(clip.assets) {
        clip.assets
            .filter { it.type == "photo" || it.type == "video_thumbnail" }
            .mapNotNull { asset -> asset.localPath?.takeIf { File(it).exists() } }
    }
    val hasOcrAction = ocrPreviewPaths.isNotEmpty()
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
                MediaGrid(clip.assets.mapNotNull { it.localPath ?: it.previewUrl ?: it.remoteUrl })
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
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (requireTagConfirmation) {
                    Button(
                        onClick = onTagConfirmation,
                        enabled = hierarchy.tags.isNotEmpty() && selectedTagIds.isNotEmpty(),
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

    if (deleteOpen) {
        ConfirmDialog(
            title = "投稿を削除",
            message = "この投稿をアプリから削除します。X上の投稿は削除されません。",
            onDismiss = { deleteOpen = false },
            onConfirm = {
                onDelete(clip.clip)
                deleteOpen = false
            },
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
}


@Composable
fun MediaGrid(urls: List<String>) {
    val shown = urls.take(4)
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        when (shown.size) {
            1 -> MediaCell(shown[0], Modifier.fillMaxWidth().aspectRatio(16f / 10f), testTag = "media_grid_cell_0")
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                shown.forEachIndexed { index, url -> MediaCell(url, Modifier.weight(1f).aspectRatio(1f), testTag = "media_grid_cell_$index") }
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.take(2).forEachIndexed { index, url -> MediaCell(url, Modifier.weight(1f).aspectRatio(1f), testTag = "media_grid_cell_$index") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    shown.drop(2).forEachIndexed { index, url -> MediaCell(url, Modifier.weight(1f).aspectRatio(1f), testTag = "media_grid_cell_${index + 2}") }
                    if (shown.size == 3) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun MediaCell(url: String, modifier: Modifier, testTag: String? = null) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
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
        Text("タグリストにタグを追加すると、ここから選べます", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onCreateTag: (String, Long?, String) -> Unit,
    onCreateGroup: (String, Long?, String) -> Unit,
    onRenameTag: (TagEntity, String, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String, String) -> Unit,
    onDeleteTag: (TagEntity) -> Unit,
    onDeleteGroup: (TagGroupEntity) -> Unit,
    onMove: (TagNodeRef, Long?) -> Unit,
    onReorder: (Long?, List<TagNodeRef>) -> Unit,
    onAddAll: (TagEntity, TagEntity) -> Unit,
) {
    var createType by remember { mutableStateOf<TagNodeType?>(null) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
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
        CreateNodeDialog(type, null, onDismiss = { createType = null }, onCreate = { name, colorId ->
            if (type == TagNodeType.TAG) onCreateTag(name, null, colorId) else onCreateGroup(name, null, colorId)
            createType = null
        })
    }
}

@Composable
private fun TagManagementChildren(
    hierarchy: TagHierarchy,
    parentId: Long?,
    depth: Int,
    expanded: MutableMap<Long, Boolean>,
    onCreateTag: (String, Long?, String) -> Unit,
    onCreateGroup: (String, Long?, String) -> Unit,
    onRenameTag: (TagEntity, String, String) -> Unit,
    onRenameGroup: (TagGroupEntity, String, String) -> Unit,
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
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(tagColor(tag.colorId)))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(node.name, fontWeight = FontWeight.SemiBold)
                        Text("${node.count}件", style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("操作") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (node is TagGroupNode) {
                                DropdownMenuItem(text = { Text("グループを追加") }, onClick = { menuOpen = false; createType = TagNodeType.GROUP })
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
                CreateNodeDialog(type, (node as? TagGroupNode)?.id ?: parentId, onDismiss = { createType = null }, onCreate = { name, colorId ->
                    val parent = (node as? TagGroupNode)?.id ?: parentId
                    if (type == TagNodeType.TAG) onCreateTag(name, parent, colorId) else onCreateGroup(name, parent, colorId)
                    createType = null
                })
            }
            if (renameOpen) RenameNodeDialog(node.name, initialColorId = if (node is TagGroupNode) node.group.colorId else (node as TagLeafNode).tag.colorId, onDismiss = { renameOpen = false }) { name, colorId ->
                when (node) {
                    is TagGroupNode -> onRenameGroup(node.group, name, colorId)
                    is TagLeafNode -> onRenameTag(node.tag, name, colorId)
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
fun CreateNodeDialog(
    type: TagNodeType,
    parentId: Long?,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    initialColorId: String = TagColorId.STANDARD.id,
) {
    var name by remember(type, parentId, initialColorId) { mutableStateOf("") }
    var colorId by remember(type, parentId, initialColorId) { mutableStateOf(initialColorId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (type == TagNodeType.TAG) "タグを追加" else "グループを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("名前") },
                    modifier = Modifier.testTag("create_node_name"),
                )
                Spacer(Modifier.height(4.dp))
                TagColorPalettePicker(selectedColorId = colorId, onColorSelected = { colorId = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name, colorId) }, modifier = Modifier.testTag("create_node_confirm")) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("create_node_cancel")) { Text("閉じる") } },
    )
}

@Composable
fun RenameNodeDialog(
    initialName: String,
    initialColorId: String = TagColorId.STANDARD.id,
    onDismiss: () -> Unit,
    onRename: (String, String) -> Unit,
) {
    var name by remember(initialName, initialColorId) { mutableStateOf(initialName) }
    var colorId by remember(initialName, initialColorId) { mutableStateOf(initialColorId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("名前を変更") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.testTag("rename_node_name"),
                )
                Spacer(Modifier.height(4.dp))
                TagColorPalettePicker(selectedColorId = colorId, onColorSelected = { colorId = it })
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name, colorId) }, modifier = Modifier.testTag("rename_node_save")) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("rename_node_cancel")) { Text("閉じる") } },
    )
}

@Composable
fun MoveNodeDialog(node: TagTreeNode, groups: List<TagGroupEntity>, onDismiss: () -> Unit, onMove: (Long?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${node.name}」を移動") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    AssistChip(
                        onClick = { onMove(null) },
                        modifier = Modifier.testTag("move_node_target_root"),
                        label = { Text("ルート") },
                    )
                }
                items(groups, key = { it.id }) { group ->
                    AssistChip(
                        onClick = { onMove(group.id) },
                        modifier = Modifier.testTag("move_node_target_group_${group.id}"),
                        label = { Text(group.name) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("move_node_cancel")) { Text("閉じる") } },
    )
}

fun TagTreeNode.ref(): TagNodeRef = TagNodeRef(
    if (this is TagGroupNode) TagNodeType.GROUP else TagNodeType.TAG,
    id,
)

fun TagFilterState.shortLabel(): String = when (this) {
    TagFilterState.NONE -> ""
    TagFilterState.INCLUDED -> "含: "
    TagFilterState.REQUIRED -> "必: "
    TagFilterState.EXCLUDED -> "除: "
}

@Composable
fun AddAllTagsDialog(source: TagEntity, targets: List<TagEntity>, onDismiss: () -> Unit, onAddAll: (TagEntity) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("タグ内容を別タグへ追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("「${source.name}」の全ツイートに追加するタグを選びます。元のタグは残ります。")
                targets.forEach { target ->
                    AssistChip(
                        onClick = { onAddAll(target) },
                        modifier = Modifier.testTag("add_all_target_tag_${target.id}"),
                        label = { Text(target.name) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("add_all_cancel")) { Text("閉じる") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dialogTestTag: String? = null,
    confirmTestTag: String? = null,
    dismissTestTag: String? = null,
) {
    AlertDialog(
        modifier = dialogTestTag?.let { Modifier.testTag(it) } ?: Modifier,
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = confirmTestTag?.let { Modifier.testTag(it) } ?: Modifier,
            ) { Text("実行") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = dismissTestTag?.let { Modifier.testTag(it) } ?: Modifier,
            ) { Text("戻る") }
        },
    )
}

@Composable
fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().testTag("empty_state"), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
