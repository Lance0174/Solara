package io.github.akudamatata.solara.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import io.github.akudamatata.solara.MusicViewModel
import io.github.akudamatata.solara.data.QUALITIES
import io.github.akudamatata.solara.data.Song
import io.github.akudamatata.solara.downloadQuality
import io.github.akudamatata.solara.downloadSong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadsScreen(vm: MusicViewModel, onAddToPlaylist: (Song) -> Unit) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text("下载到手机", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Music/Solara · 随时离线聆听", Modifier.padding(top = 6.dp, bottom = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (downloads.isEmpty()) EmptyState(Icons.Rounded.Download, "把音乐带在身边", "在歌曲菜单选择下载音质，进度和完成结果会显示在这里", Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            items(downloads.sortedWith(compareBy<WorkInfo> { it.state.isFinished }.thenByDescending { info ->
                info.tags.firstOrNull { it.startsWith("created:") }?.removePrefix("created:")?.toLongOrNull() ?: 0L
            }), key = { it.id }) { info ->
                val song = downloadSong(info)
                val quality = downloadQuality(info)
                val percent = info.progress.getInt("percent", -1)
                Card(shape = solaraShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (info.state == WorkInfo.State.SUCCEEDED) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                                null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(song?.name ?: "音乐下载", fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(song?.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        val status = when (info.state) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "等待网络或下载资源"
                            WorkInfo.State.RUNNING -> if (percent < 0) "正在准备或下载音频" else "正在下载 $percent%"
                            WorkInfo.State.SUCCEEDED -> "已保存 · ${info.outputData.getString("filename").orEmpty()}"
                            WorkInfo.State.FAILED -> info.outputData.getString("error") ?: "下载失败，请重试"
                            WorkInfo.State.CANCELLED -> "已取消，未完成文件已清理"
                        }
                        Text("${QUALITIES[quality]} · $status", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                            color = if (info.state == WorkInfo.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        val bitrate = info.outputData.getInt("bitrate", 0)
                        if (info.state == WorkInfo.State.SUCCEEDED && bitrate > 0) Text("音源返回：$bitrate kbps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (info.state == WorkInfo.State.RUNNING) {
                            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, Modifier.fillMaxWidth().padding(top = 12.dp))
                            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        }
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            when {
                                info.state == WorkInfo.State.SUCCEEDED -> {
                                    ActionIcon(Icons.Rounded.LibraryMusic, "加入本地歌单") { vm.useDownloadedSong(info, onAddToPlaylist) }
                                    TextButton(onClick = { song?.let { vm.download(it, quality) } }) { Text("重新下载") }
                                    TextButton(onClick = { vm.playDownloaded(info) }) { Icon(Icons.Rounded.PlayArrow, null); Text("离线播放") }
                                }
                                info.state.isFinished -> TextButton(onClick = { song?.let { vm.download(it, quality) } }) { Icon(Icons.Rounded.Refresh, null); Text("重新下载") }
                                else -> TextButton(onClick = { vm.cancelDownload(info.id) }) { Text("取消下载") }
                            }
                        }
                    }
                }
            }
        }
    }
}
