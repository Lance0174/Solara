package io.github.akudamatata.solara.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.akudamatata.solara.MusicViewModel
import io.github.akudamatata.solara.PlaybackState
import io.github.akudamatata.solara.data.QUALITIES
import io.github.akudamatata.solara.data.SOURCES
import io.github.akudamatata.solara.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun SongArtwork(song: Song?, vm: MusicViewModel, modifier: Modifier = Modifier, resolvedUrl: String = "", resolve: Boolean = false) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val url by produceState("", song?.key, resolvedUrl, settings.site, settings.api, resolve) {
        value = resolvedUrl.ifBlank { song?.picId?.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty() }
        // 本地音频没有封面且不需要向接口解析封面。
        if (value.isBlank() && song != null && resolve && song.localUri.isBlank()) {
            try { value = withContext(Dispatchers.IO) { vm.app.api.cover(song) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { value = "" }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.42f), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
        if (url.isNotBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun PlayerScreen(vm: MusicViewModel, onQueue: () -> Unit, onDownload: (Song) -> Unit, onPlay: () -> Unit, onAddToPlaylist: (List<Song>) -> Unit) {
    val player by vm.playback.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showSleepTimer by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val sleepTimerRemaining = player.sleepTimerRemainingMs.takeIf { it > 0 }?.let { timeLabel(it + 999) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 700.dp
        // 封面即页面：封面与歌词是同一页面的两个状态，点按封面切换到歌词，不做浮层叠放；歌词直接铺在页面上。
        val cover: @Composable () -> Unit = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (showLyrics && player.song != null) {
                    Column(Modifier.fillMaxSize()) {
                        TextButton(onClick = { showLyrics = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp)); Text("返回封面") }
                        LyricsPanel(vm, Modifier.weight(1f))
                    }
                } else {
                    SongArtwork(player.song, vm,
                        Modifier.fillMaxSize(0.86f).aspectRatio(1f).sizeIn(maxWidth = 340.dp, maxHeight = 340.dp)
                            .clip(solaraShape(28.dp))
                            .clickable(enabled = player.song != null, onClickLabel = "查看歌词") { showLyrics = true },
                        player.artwork, resolve = true)
                }
            }
        }
        val controls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(player.song?.name ?: "选择一首歌曲开始播放", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(player.song?.artist ?: "搜索喜欢的声音，或试试探索雷达", Modifier.padding(top = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val favorite = library.favorites.any { it.key == player.song?.key }
                    ActionIcon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "收藏当前歌曲", enabled = player.song != null) { player.song?.let(vm::favorite) }
                    ActionIcon(Icons.Rounded.LibraryMusic, "加入本地歌单", enabled = player.song != null) { player.song?.let { onAddToPlaylist(listOf(it)) } }
                    Box {
                        ActionIcon(Icons.Rounded.MoreVert, "播放器菜单") { menu = true }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("复制歌曲详情") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                                onClick = {
                                    menu = false
                                    player.song?.let { song ->
                                        val details = buildString {
                                            appendLine("歌曲：${song.name}")
                                            appendLine("歌手：${song.artist}")
                                            appendLine("专辑：${song.album.ifBlank { "未知专辑" }}")
                                            appendLine("音源：${SOURCES[song.source] ?: song.source}")
                                            append("歌曲 ID：${song.id}")
                                        }
                                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("歌曲详情", details))
                                        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "歌曲详情已复制", Toast.LENGTH_SHORT).show()
                                    }
                                }, enabled = player.song != null)
                            DropdownMenuItem(text = { Text("加入本地歌单") }, leadingIcon = { Icon(Icons.Rounded.LibraryMusic, null) },
                                onClick = { menu = false; player.song?.let { onAddToPlaylist(listOf(it)) } }, enabled = player.song != null)
                            DropdownMenuItem(text = { Text(sleepTimerRemaining?.let { "定时关闭音乐 · $it" } ?: "定时关闭音乐") },
                                leadingIcon = { Icon(Icons.Rounded.Timer, null) },
                                onClick = { menu = false; showSleepTimer = true }, enabled = player.ready)
                            DropdownMenuItem(text = { Text("探索雷达") }, leadingIcon = { Icon(Icons.Rounded.Radar, null) },
                                onClick = { menu = false; vm.radar() }, enabled = !busy && player.ready)
                            DropdownMenuItem(text = { Text("下载") }, leadingIcon = { Icon(Icons.Rounded.Download, null) },
                                onClick = { menu = false; player.song?.let(onDownload) }, enabled = player.song != null && player.song!!.localUri.isBlank())
                        }
                    }
                }
                ChoiceMenu(QUALITIES[settings.quality].orEmpty(), QUALITIES, vm::quality)
                var dragging by remember { mutableStateOf<Float?>(null) }
                Slider(value = dragging ?: player.position.toFloat().coerceAtMost(player.duration.toFloat()),
                    onValueChange = { dragging = it }, onValueChangeFinished = { dragging?.let { vm.seek(it.toLong()) }; dragging = null },
                    valueRange = 0f..player.duration.coerceAtLeast(1).toFloat(), enabled = player.duration > 0, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth()) {
                    Text(timeLabel(dragging?.toLong() ?: player.position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(timeLabel(player.duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    ActionIcon(if (player.mode == 1) Icons.Rounded.RepeatOne else if (player.mode == 2) Icons.Rounded.Shuffle else Icons.Rounded.Repeat,
                        listOf("列表循环", "单曲循环", "随机播放")[player.mode]) { vm.changeMode() }
                    ActionIcon(Icons.Rounded.SkipPrevious, "上一首", enabled = player.song != null) { vm.previous() }
                    FilledIconButton(onClick = onPlay, enabled = player.ready, modifier = Modifier.size(72.dp)) {
                        if (player.buffering) CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                        else Icon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (player.playing) "暂停" else "播放", Modifier.size(38.dp))
                    }
                    ActionIcon(Icons.Rounded.SkipNext, "下一首", enabled = player.song != null) { vm.next() }
                    ActionIcon(Icons.AutoMirrored.Rounded.QueueMusic, "查看播放列表", action = onQueue)
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            // 固定播放页：封面与歌词占满剩余高度，控制区始终位于底部，不因报错提示等内容的增减而整体滑动。
            if (wide) Row(Modifier.fillMaxSize().padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(end = 28.dp)) { cover() }
                Box(Modifier.weight(1f)) { controls() }
            } else Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp)) {
                Box(Modifier.weight(1f)) { cover() }
                controls()
            }
            if (player.error.isNotBlank()) Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
                color = MaterialTheme.colorScheme.errorContainer, shape = solaraShape(16.dp)) {
                Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(player.error, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { vm.togglePlayback() }) { Text("重试") }
                }
            }
        }
    }
    if (showSleepTimer) SleepTimerDialog(remainingLabel = sleepTimerRemaining, enabled = player.ready,
        onSet = { vm.setSleepTimer(it); showSleepTimer = false },
        onCancel = { vm.cancelSleepTimer(); showSleepTimer = false }, onDismiss = { showSleepTimer = false })
}

@Composable
private fun LyricsPanel(vm: MusicViewModel, modifier: Modifier) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val player by vm.playback.collectAsStateWithLifecycle()
    val state = rememberLazyListState()
    val dragging by state.interactionSource.collectIsDraggedAsState()
    var manualUntil by remember { mutableLongStateOf(0) }
    val current = lyrics.lines.indexOfLast { it.timeMs <= player.position }.coerceAtLeast(0)
    LaunchedEffect(dragging) { if (dragging) manualUntil = android.os.SystemClock.elapsedRealtime() + 3000 }
    LaunchedEffect(current, dragging, manualUntil) {
        if (!dragging && lyrics.lines.isNotEmpty()) {
            delay((manualUntil - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
            state.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    when {
        lyrics.busy -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        lyrics.error.isNotBlank() -> Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TextButton(onClick = { vm.loadLyrics() }) { Text(lyrics.error) } }
        lyrics.lines.isEmpty() -> EmptyState(Icons.Rounded.MusicNote, "暂无歌词", "让旋律继续", modifier)
        else -> LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(lyrics.lines) { index, line ->
                Column(Modifier.fillMaxWidth().clip(solaraShape(12.dp)).clickable { vm.seek(line.timeMs) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(line.text.ifBlank { "♪" }, color = if (index == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = if (index == current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center)
                    if (line.translation.isNotBlank()) Text(line.translation, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun MiniPlayer(player: PlaybackState, vm: MusicViewModel, onOpen: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(solaraShape(18.dp)).clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SongArtwork(player.song, vm, Modifier.size(44.dp).clip(solaraShape(12.dp)), player.artwork)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(player.song?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(player.song?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                ActionIcon(if (player.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.playing) "暂停" else "播放") { vm.togglePlayback() }
                ActionIcon(Icons.Rounded.SkipNext, "下一首") { vm.next() }
            }
            if (player.duration > 0) LinearProgressIndicator(progress = { (player.position.toFloat() / player.duration).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

private fun timeLabel(ms: Long): String = String.format(Locale.ROOT, "%d:%02d", ms / 60000, (ms / 1000) % 60)
