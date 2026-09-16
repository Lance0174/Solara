package io.github.akudamatata.solara.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akudamatata.solara.MusicViewModel
import io.github.akudamatata.solara.data.LocalPlaylists
import io.github.akudamatata.solara.data.SongLists

@Composable
fun NeteasePlaylistDialog(vm: MusicViewModel, onDismiss: () -> Unit, onImported: (String) -> Unit, initialPlaylistId: String? = null) {
    val state by vm.playlistImport.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val saving by vm.busy.collectAsStateWithLifecycle()
    val result = state.playlist
    var input by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable(result?.name) { mutableStateOf(result?.name.orEmpty().take(LocalPlaylists.MAX_NAME_LENGTH)) }
    var destination by rememberSaveable { mutableStateOf(initialPlaylistId) }
    var choosing by remember { mutableStateOf(false) }
    val target = playlists.firstOrNull { it.id == destination }
    val keys = target?.songs?.map { it.key }?.toSet().orEmpty()
    val added = result?.songs?.count { it.key !in keys } ?: 0
    val error = when {
        destination == null -> LocalPlaylists.nameError(name, playlists)
        target == null -> "请选择本地歌单"
        target.songs.size + added > SongLists.MAX_SONGS -> "合并后超过 ${SongLists.MAX_SONGS} 首，请选择其他歌单或新建歌单"
        else -> null
    }
    val close = { vm.clearPlaylistImport(); onDismiss() }
    AlertDialog(
        onDismissRequest = { if (!saving) close() },
        title = { Text("导入网易云歌单") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (result == null) {
                    Text("粘贴网易云公开歌单的分享链接或数字 ID，读取后保存到本地歌单。")
                    OutlinedTextField(value = input, onValueChange = { input = it; vm.clearPlaylistImport() },
                        modifier = Modifier.fillMaxWidth(), enabled = !state.busy, maxLines = 4,
                        label = { Text("歌单链接或 ID") }, placeholder = { Text("例如：3778678") },
                        isError = state.error.isNotBlank())
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (state.total == null) "正在读取歌单…" else "已读取 ${state.loaded} / ${state.total} 首，正在补齐…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.error.isNotBlank()) Text(state.error, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(result.name, style = MaterialTheme.typography.titleMedium)
                    Text(when {
                        result.partial && result.unreadableCount > 0 ->
                            "原歌单 ${result.totalCount} 首，可读取 ${result.songs.size} 首，${result.unreadableCount} 首暂不可读取（可能已下架或受版权限制）。确认后仅导入可读取的歌曲。"
                        result.partial -> "原歌单 ${result.totalCount} 首，当前可读取 ${result.songs.size} 首。确认后仅导入可读取的歌曲。"
                        result.totalCount == null -> "已读取 ${result.songs.size} 首，暂无法确认原歌单总数。"
                        else -> "已读取全部 ${result.songs.size} 首歌曲"
                    }, color = if (result.partial) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result.unreadableCount > 0) Text("${result.unreadableCount} 首歌曲导入失败，未计入上方数量。",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (result.songs.isEmpty()) Text("这份歌单暂时没有可导入的歌曲。")
                    result.songs.take(3).forEach { song ->
                        Text("${song.name} · ${song.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    if (result.songs.isNotEmpty()) {
                        if (playlists.isNotEmpty() || destination != null) Box {
                            OutlinedButton(onClick = { choosing = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                                Text(target?.let { "合并到：${it.name}" } ?: "新建本地歌单", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                                DropdownMenuItem(text = { Text("新建本地歌单") }, onClick = { destination = null; choosing = false })
                                playlists.forEach { playlist ->
                                    DropdownMenuItem(text = { Text("合并到：${playlist.name}") }, onClick = { destination = playlist.id; choosing = false })
                                }
                            }
                        }
                        if (destination == null) OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !saving,
                            modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("本地歌单名称") },
                            isError = error != null, supportingText = { Text(error ?: "导入后可在本机编辑，重复歌曲自动去重") })
                        else {
                            Text(error ?: "将新增 $added 首，跳过 ${result.songs.size - added} 首重复歌曲，保留已有歌曲及顺序。",
                                color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { vm.clearPlaylistImport() }, enabled = !saving) { Text("换一个歌单") }
                }
            }
        },
        confirmButton = {
            if (result == null) TextButton(onClick = { vm.readNeteasePlaylist(input) }, enabled = input.isNotBlank() && !state.busy && !saving) {
                Text(if (state.error.isBlank()) "读取歌单" else "重试")
            } else TextButton(enabled = !saving && result.songs.isNotEmpty() && error == null, onClick = {
                if (destination == null) vm.createPlaylist(name, result.songs) { id -> close(); onImported(id) }
                else target?.let { vm.addToPlaylist(it.id, result.songs) { close(); onImported(it.id) } }
            }) { Text(if (result.partial || result.totalCount == null) "导入这 ${result.songs.size} 首" else "确认导入") }
        },
        dismissButton = { TextButton(onClick = close, enabled = !saving) { Text("取消") } },
    )
}
