package com.lyco256.llm

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyco256.llm.data.ApiSettings
import com.lyco256.llm.data.LikeCountRefreshEstimate
import com.lyco256.llm.data.PostStorageEstimate
import com.lyco256.llm.data.PostStorageLocation
import com.lyco256.llm.data.PostStorageType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val AppDataColor = Color(0xFF42A5F5)
private val FreeSpaceColor = Color(0xFF424242)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onLogin: (ApiSettings) -> Unit,
) {
    LaunchedEffect(Unit) {
        viewModel.refreshSettingsSnapshot()
    }

    val settings = uiState.settingsSnapshot
    val storageState = uiState.storageState
    var clientIdDraft by remember(uiState.apiSettings.clientId) { mutableStateOf(uiState.apiSettings.clientId) }
    var changeClientIdConfirm by remember { mutableStateOf(false) }
    var likeRefreshEstimate by remember { mutableStateOf<LikeCountRefreshEstimate?>(null) }
    var storageEstimate by remember { mutableStateOf<PostStorageEstimate?>(null) }
    var storageEstimating by remember { mutableStateOf(false) }
    var storageMoveInProgress by remember { mutableStateOf(false) }
    var messageTitle by remember { mutableStateOf<String?>(null) }
    var messageBody by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("settings_screen"),
        topBar = {
            TopAppBar(
                title = { Text("設定", fontSize = 22.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp).testTag("settings_content"),
            contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            item {
                SettingsSection(title = "X API設定", testTag = "settings_x_api_section") {
                    val trimmedClientId = clientIdDraft.trim()
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        OutlinedTextField(
                            value = clientIdDraft,
                            onValueChange = { clientIdDraft = it },
                            modifier = Modifier.fillMaxWidth().testTag("settings_client_id_input"),
                            label = { Text("OAuth 2.0 Client ID") },
                            singleLine = true,
                        )
                        Text(uiState.oauthSession?.let { "@${it.username} でログイン中" } ?: "未ログイン")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                onClick = {
                                    if (uiState.oauthSession != null && trimmedClientId.isNotBlank() && trimmedClientId != uiState.apiSettings.clientId) {
                                        changeClientIdConfirm = true
                                    } else if (trimmedClientId.isNotBlank()) {
                                        viewModel.saveApiSettings(ApiSettings(trimmedClientId))
                                    }
                                },
                                enabled = trimmedClientId.isNotBlank(),
                                modifier = Modifier.testTag("settings_client_id_save"),
                            ) { Text("保存") }
                            Button(
                                onClick = {
                                    clientIdDraft = ""
                                    viewModel.clearApiSettings()
                                },
                                modifier = Modifier.testTag("settings_client_id_clear"),
                                colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFD32F2F), contentColor = androidx.compose.ui.graphics.Color.White),
                            ) {
                                Text("消去")
                            }
                            Button(
                                onClick = {
                                    if (uiState.oauthSession != null) {
                                        viewModel.logout { message ->
                                            messageTitle = "ログアウト"
                                            messageBody = message
                                        }
                                    } else {
                                        onLogin(ApiSettings(trimmedClientId))
                                    }
                                },
                                enabled = if (uiState.oauthSession != null) true else !BuildConfig.TEST_HARNESS && trimmedClientId.isNotBlank(),
                                modifier = Modifier.testTag("settings_login_logout"),
                            ) {
                                Text(
                                    if (uiState.oauthSession != null) "Xからログアウト" else "保存してXにログイン",
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "同期", testTag = "settings_sync_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("最終同期時刻: ${settings.lastSyncAt ?: "未同期"}")
                        Text("15分rate limit: ${rateLimitText(settings.rateLimitRemaining, settings.rateLimitLimit)}")
                        Text("15分rate limitリセット: ${formatResetTime(settings.rateLimitResetEpochSeconds)}")
                        RateLimitBar(
                            remaining = settings.rateLimitRemaining,
                            limit = settings.rateLimitLimit,
                            modifier = Modifier.fillMaxWidth().testTag("settings_rate_limit_progress"),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { viewModel.syncNow { message -> messageTitle = "同期"; messageBody = message } },
                                modifier = Modifier.testTag("settings_sync_now"),
                            ) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Download, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("同期する", maxLines = 1, softWrap = false)
                                }
                            }
                            Button(
                                onClick = {
                                    viewModel.estimateLikeCountRefresh { result ->
                                        result.onSuccess { estimate ->
                                            likeRefreshEstimate = estimate
                                        }.onFailure {
                                            messageTitle = "いいね数を更新"
                                            messageBody = it.message ?: "対象件数を確認できませんでした"
                                        }
                                    }
                                },
                                modifier = Modifier.testTag("settings_like_refresh"),
                            ) {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("いいね数を更新", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            item {
                SettingsSection(title = "使用量", testTag = "settings_usage_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("今月のAPI使用量: ${settings.monthlyApiUsage ?: 0L} / ${settings.monthlyStopLimit}")
                        LinearProgressIndicator(
                            progress = usageProgress(settings.monthlyApiUsage, settings.monthlyStopLimit),
                            modifier = Modifier.fillMaxWidth().testTag("settings_monthly_api_usage_progress"),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("推定今月料金: ${formatUsd((settings.monthlyApiUsage ?: 0L) * 0.001)}")
                        Text("累計API使用量: ${settings.cumulativeApiUsage}")
                        Text("推定累計料金: ${formatUsd(settings.cumulativeApiUsage * 0.001)}")
                    }
                }
            }

            item {
                SettingsSection(
                    title = "データ管理",
                    testTag = "settings_data_management_section",
                    showDivider = false,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (storageState.isRefreshing || storageState.isMigrating) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(storageState.migrationMessage ?: "計算中")
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text("保存件数: ${settings.saveCount ?: 0}")
                            Text("画像枚数: ${settings.imageCount ?: 0}")
                        }
                        Spacer(Modifier.height(6.dp))
                        val currentLocation = storageState.locations.firstOrNull { it.isCurrent }
                        if (currentLocation != null) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("保存先の使用状況", style = MaterialTheme.typography.titleSmall)
                            }
                            currentLocation.usedBytes?.let { usedBytes ->
                                SegmentedStorageUsageBar(
                                    appBytes = usedBytes,
                                    totalBytes = currentLocation.totalBytes,
                                    freeBytes = currentLocation.freeBytes,
                                    modifier = Modifier.fillMaxWidth().testTag("settings_storage_usage_progress"),
                                )
                            } ?: Text("保存量: 計算中")
                            StorageUsageLegend(
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        var sdCardIndex = 0
                        storageState.locations.forEach { location ->
                            val locationIndex = if (location.type == PostStorageType.EXTERNAL) sdCardIndex++ else 0
                            StorageLocationRow(
                                location = location,
                                index = locationIndex,
                                enabled = !storageEstimating && !storageState.isMigrating && !storageMoveInProgress,
                                onMove = { target ->
                                    storageEstimating = true
                                    viewModel.estimateStorageMove(target.id) { result ->
                                        storageEstimating = false
                                        result.onSuccess {
                                            storageEstimate = it
                                        }.onFailure { error ->
                                            messageTitle = "保存先変更"
                                            messageBody = error.message ?: "移動量を確認できませんでした"
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (changeClientIdConfirm) {
        AlertDialog(
            onDismissRequest = { changeClientIdConfirm = false },
            title = { Text("Client IDを変更") },
            text = { Text("ログイン中にClient IDを変更すると、現在のログイン情報は削除されます。続行しますか？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        changeClientIdConfirm = false
                        viewModel.replaceApiSettings(ApiSettings(clientIdDraft.trim()))
                    },
                ) { Text("変更する") }
            },
            dismissButton = {
                TextButton(onClick = { changeClientIdConfirm = false }) { Text("やめる") }
            },
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
                        "容量: ${formatBytes(estimate.totalBytes)}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetId = estimate.target.id
                        storageEstimate = null
                        storageMoveInProgress = true
                        viewModel.movePostStorage(targetId) { message ->
                            storageMoveInProgress = false
                            messageTitle = "保存先変更"
                            messageBody = message
                        }
                    },
                    modifier = Modifier.testTag("settings_storage_move_confirm"),
                ) { Text("移動する") }
            },
            dismissButton = {
                TextButton(
                    onClick = { storageEstimate = null },
                    modifier = Modifier.testTag("settings_storage_move_cancel"),
                ) { Text("キャンセル") }
            },
        )
    }

    if (storageEstimating) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("移動するデータを確認しています") },
            text = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("投稿件数とファイル容量を計算しています")
                }
            },
            confirmButton = {},
        )
    }

    likeRefreshEstimate?.let { estimate ->
        AlertDialog(
            onDismissRequest = { likeRefreshEstimate = null },
            title = { Text("いいね数を更新しますか？") },
            text = {
                Text(
                    "対象: ${estimate.totalTargets}件\n" +
                        "今回実行: ${estimate.executableTargets}件\n" +
                        "推定API料金: ${formatUsd(estimate.estimatedCostUsd)}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        likeRefreshEstimate = null
                        viewModel.refreshLikeCounts { message ->
                        messageTitle = "いいね数を更新"
                            messageBody = message
                        }
                    },
                    modifier = Modifier.testTag("settings_like_refresh_confirm"),
                    enabled = estimate.executableTargets > 0,
                ) { Text("再取得する") }
            },
            dismissButton = {
                TextButton(
                    onClick = { likeRefreshEstimate = null },
                    modifier = Modifier.testTag("settings_like_refresh_cancel"),
                ) { Text("キャンセル") }
            },
        )
    }

    if (messageTitle != null && messageBody != null) {
        AlertDialog(
            onDismissRequest = {
                messageTitle = null
                messageBody = null
            },
            title = { Text(requireNotNull(messageTitle)) },
            text = { Text(requireNotNull(messageBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        messageTitle = null
                        messageBody = null
                    },
                ) { Text("閉じる") }
            },
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    testTag: String,
    showDivider: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().testTag(testTag)) {
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Normal))
        Spacer(Modifier.height(40.dp))
        content()
        if (showDivider) {
            Spacer(Modifier.height(36.dp))
            Divider()
        }
    }
}

@Composable
fun XApiSettingsSection() = Unit

@Composable
fun SyncSettingsSection() = Unit

@Composable
fun UsageSettingsSection() = Unit

@Composable
fun DataManagementSection() = Unit

@Composable
fun SegmentedStorageUsageBar(
    appBytes: Long,
    totalBytes: Long,
    freeBytes: Long,
    modifier: Modifier = Modifier,
) {
    val otherBytes = max(totalBytes - appBytes - freeBytes, 0L)
    if (totalBytes <= 0L) {
        Text("計算中")
        return
    }
    Row(
        modifier
            .height(14.dp)
            .clip(MaterialTheme.shapes.large),
    ) {
        StorageSegment(otherBytes, totalBytes, Color.White)
        StorageSegment(appBytes, totalBytes, AppDataColor)
        StorageSegment(freeBytes, totalBytes, FreeSpaceColor)
    }
}

@Composable
fun StorageUsageLegend(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendRow(Color.White, "他のデータ")
        LegendRow(AppDataColor, "アプリデータ")
        LegendRow(FreeSpaceColor, "空き容量")
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("●", color = color, fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun RowScope.StorageSegment(bytes: Long, totalBytes: Long, color: Color) {
    val fraction = if (bytes <= 0L || totalBytes <= 0L) 0f else bytes.toFloat() / totalBytes.toFloat()
    if (fraction <= 0f) return
    Box(Modifier.weight(fraction).fillMaxHeight().background(color))
}

@Composable
private fun StorageLocationRow(
    location: PostStorageLocation,
    index: Int,
    enabled: Boolean,
    onMove: (PostStorageLocation) -> Unit,
) {
    val buttonTag = when (location.type) {
        PostStorageType.INTERNAL -> "settings_storage_move_internal"
        PostStorageType.EXTERNAL -> "settings_storage_move_sd_$index"
    }
    Surface(
        tonalElevation = 1.dp,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                location.displayName + if (location.isCurrent) "（現在地）" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(if (location.isAvailable) location.path else "未装着または読み取り不可")
            if (location.isAvailable) {
                Text("使用中: ${location.usedBytes?.let(::formatBytes) ?: "計算中"}")
                Text("空き容量: ${formatBytes(location.freeBytes)}")
            }
            Button(
                onClick = { onMove(location) },
                enabled = enabled && location.isAvailable && !location.isCurrent,
                modifier = Modifier.testTag(buttonTag),
            ) { Text("ここへ移動") }
        }
    }
}

@Composable
private fun RateLimitBar(remaining: Int?, limit: Int?, modifier: Modifier = Modifier) {
    val progress = if (remaining != null && limit != null && limit > 0) {
        (limit - remaining).coerceAtLeast(0).toFloat() / limit.toFloat()
    } else {
        0f
    }
    LinearProgressIndicator(progress = progress, modifier = modifier)
}

private fun usageProgress(current: Long?, limit: Int): Float {
    if (current == null || limit <= 0) return 0f
    return current.toFloat().coerceAtMost(limit.toFloat()) / limit.toFloat()
}

private fun rateLimitText(remaining: Int?, limit: Int?): String =
    if (remaining == null || limit == null) "未取得"
    else "$remaining / $limit"

private fun formatUsd(value: Double): String = "$${"%.3f".format(value)}"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

private fun formatResetTime(epochSeconds: Long?): String =
    epochSeconds?.let {
        Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    } ?: "未取得"
