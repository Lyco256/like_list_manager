package com.lyco256.llm

import android.content.Context
import com.lyco256.llm.data.MediaGridImagePreparer
import com.lyco256.llm.data.MediaGridPreviewNotifier
import coil.ImageLoader
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel

/** Owns classified media-grid state for longer than any one screen composition. */
internal class MediaGridSessionCoordinator(
    private val context: Context,
    private val preparer: MediaGridImagePreparer,
    private val imageLoader: ImageLoader,
    private val onPersistentPreviewError: suspend (Long, com.lyco256.llm.data.MediaGridPreparedCandidate) -> Unit,
) {
    private val retainedImageStore = MediaGridRetainedImageStore(imageLoader.memoryCache)
    private val recoveredPreviewIdentities = ConcurrentHashMap.newKeySet<String>()
    private data class Session(
        val key: MediaGridSessionKey,
        var dataKey: MediaGridDataKey? = null,
        var frame: MediaGridFrameData? = null,
        var columnCount: Int = ClassifiedMediaGridDefaultColumnCount,
        var requestedColumnCount: Int = ClassifiedMediaGridDefaultColumnCount,
        var controller: MediaGridSteadyLoadController? = null,
        var collectJob: Job? = null,
        var buildJob: Job? = null,
        var anchor: ClassifiedMediaGridScrollAnchor? = null,
        var visible: Boolean = false,
        var pendingRevision: Long? = null,
        var pendingDataKey: MediaGridDataKey? = null,
        var pendingColumnCount: Int? = null,
        var refreshGeneration: Long = 0L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = LinkedHashMap<MediaGridSessionKey, Session>(3, .75f, true)
    private var activeKey: MediaGridSessionKey? = null
    private val _state = MutableStateFlow(MediaGridSessionUiState())
    val state: StateFlow<MediaGridSessionUiState> = _state.asStateFlow()

    init {
        context.applicationContext.registerComponentCallbacks(retainedImageStore)
        scope.launch {
            MediaGridPreviewNotifier.previewChanged.collect { assetId ->
                synchronized(this@MediaGridSessionCoordinator) {
                    sessions.values.forEach { it.controller?.invalidate(assetId) }
                }
            }
        }
    }

    @Synchronized fun update(source: ClassifiedMediaGridState, requestedColumns: Int, visible: Boolean = false) {
        val dataKey = source.dataKey ?: return
        val key = mediaGridSessionKey(dataKey)
        val session = sessions.getOrPut(key) { Session(key, requestedColumnCount = requestedColumns) }
        activeKey = key
        session.requestedColumnCount = requestedColumns.coerceIn(2, 12)
        session.visible = visible
        session.controller?.let { if (visible) it.resume() else it.pause() }
        if (source.status != MediaGridLoadStatus.Ready) {
            publish(session)
            trimLru()
            return
        }
        val needsFrame = session.frame == null || session.dataKey != dataKey
        val needsColumns = session.frame != null && session.columnCount != session.requestedColumnCount
        if (needsFrame || needsColumns) scheduleBuild(session, source, dataKey, session.requestedColumnCount)
        publish(session)
        trimLru()
    }

    @Synchronized fun setVisible(visible: Boolean) {
        val session = activeSession() ?: return
        session.visible = visible
        session.controller?.let { if (visible) it.resume() else it.pause() }
        publish(session)
    }

    @Synchronized fun saveAnchor(anchor: ClassifiedMediaGridScrollAnchor) {
        val session = activeSession() ?: return
        session.anchor = anchor
    }

    @Synchronized fun invalidate(assetId: Long) { activeSession()?.controller?.invalidate(assetId) }

    @Synchronized fun dispose() {
        sessions.values.forEach { disposeSession(it) }
        sessions.clear()
        context.applicationContext.unregisterComponentCallbacks(retainedImageStore)
        retainedImageStore.clear()
        scope.coroutineContext.cancel()
    }

    private fun scheduleBuild(session: Session, source: ClassifiedMediaGridState, dataKey: MediaGridDataKey, columns: Int) {
        if (session.buildJob?.isActive == true && session.pendingDataKey == dataKey && session.pendingColumnCount == columns) return
        session.buildJob?.cancel()
        val generation = ++session.refreshGeneration
        session.pendingDataKey = dataKey
        session.pendingColumnCount = columns
        session.buildJob = scope.launch {
            val next = withContext(Dispatchers.Default) {
                buildMediaGridFrameData(source.entries, dataKey.sort, columns, dataKey)
            }
            if (session.refreshGeneration != generation || sessions[session.key] !== session) return@launch
            val controller = session.controller
            if (controller == null) {
                val created = MediaGridSteadyLoadController(
                    context = context,
                    frame = next,
                    preparer = preparer,
                    imageLoader = imageLoader,
                    onPreviewCandidateError = { assetId, candidate ->
                        if (recoveredPreviewIdentities.add(candidate.sourceIdentity)) {
                            onPersistentPreviewError(assetId, candidate)
                        }
                    },
                    retainedImageStore = retainedImageStore,
                    ownerToken = retainedImageStore.newOwnerToken(),
                )
                session.controller = created
                session.collectJob = scope.launch {
                    created.uiState.collect { value ->
                        if (sessions[session.key] === session) publish(session, value)
                    }
                }
                created.start()
                if (!session.visible) created.pause()
            } else {
                controller.updateFrame(next)
            }
            session.dataKey = dataKey
            session.frame = next
            session.columnCount = columns
            session.pendingRevision = null
            session.pendingDataKey = null
            session.pendingColumnCount = null
            publish(session)
        }
    }

    private fun activeSession(): Session? = activeKey?.let { sessions[it] }

    @Synchronized private fun publish(session: Session, controllerState: MediaGridControllerUiState? = session.controller?.uiState?.value) {
        if (activeKey != session.key) return
        val frame = session.frame
        val startup = controllerState?.startup ?: if (frame == null) MediaGridStartupState.PreparingFrame else MediaGridStartupState.PreparingInitialWindow
        _state.value = MediaGridSessionUiState(
            sessionKey = session.key,
            frame = frame,
            columnCount = session.columnCount,
            requestedColumnCount = session.requestedColumnCount,
            controller = session.controller,
            controllerState = controllerState ?: MediaGridControllerUiState(startup),
            showInitialProgress = frame == null || startup != MediaGridStartupState.Ready && session.controller?.hasCompletedInitialWarmup != true,
            anchor = session.anchor,
            visible = session.visible,
            refreshGeneration = session.refreshGeneration,
            retainedImageStore = retainedImageStore,
        )
    }

    private fun trimLru() {
        while (sessions.size > 2) {
            val eldest = sessions.entries.firstOrNull { it.key != activeKey } ?: sessions.entries.first()
            sessions.remove(eldest.key)?.let(::disposeSession)
        }
    }

    private fun disposeSession(session: Session) {
        session.buildJob?.cancel()
        session.collectJob?.cancel()
        session.controller?.dispose()
    }
}

internal data class MediaGridSessionKey(
    val filter: TweetFilterState,
    val sort: ClassifiedSortState,
)

internal fun mediaGridSessionKey(dataKey: MediaGridDataKey): MediaGridSessionKey =
    MediaGridSessionKey(dataKey.filter, dataKey.sort)

internal data class MediaGridSessionUiState(
    val sessionKey: MediaGridSessionKey? = null,
    val frame: MediaGridFrameData? = null,
    val columnCount: Int = ClassifiedMediaGridDefaultColumnCount,
    val requestedColumnCount: Int = ClassifiedMediaGridDefaultColumnCount,
    val controller: MediaGridSteadyLoadController? = null,
    val controllerState: MediaGridControllerUiState = MediaGridControllerUiState(),
    val showInitialProgress: Boolean = true,
    val anchor: ClassifiedMediaGridScrollAnchor? = null,
    val visible: Boolean = false,
    val refreshGeneration: Long = 0L,
    val retainedImageStore: MediaGridRetainedImageStore? = null,
)
