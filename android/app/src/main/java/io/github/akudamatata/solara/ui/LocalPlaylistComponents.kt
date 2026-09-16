package io.github.akudamatata.solara.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akudamatata.solara.MusicViewModel
import io.github.akudamatata.solara.data.LocalPlaylist
import io.github.akudamatata.solara.data.LocalPlaylists
import io.github.akudamatata.solara.data.Song

@Composable
fun PlaylistNameDialog(
    playlists: List<LocalPlaylist>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    playlist: LocalPlaylist? = null,
) {
    var name by rememberSaveable(playlist?.id) { mutableStateOf(playlist?.name.orEmpty()) }
    val error = LocalPlaylists.nameError(name, playlists, playlist?.id)
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (playlist == null) "新建本地歌单" else "重命名歌单") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, enabled = !busy,
                label = { Text("歌单名称") }, placeholder = { Text("例如：通勤路上") },
                isError = name.isNotBlank() && error != null,
                supportingText = { Text(if (name.isNotBlank() && error != null) error else "保存在本机，最多 ${LocalPlaylists.MAX_NAME_LENGTH} 个字符") })
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = !busy && error == null) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistPicker(vm: MusicViewModel, songs: List<Song>, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("加入本地歌单", style = MaterialTheme.typography.titleLarge)
            Text(if (songs.size == 1) songs.first().name else "已选择 ${songs.size} 首歌曲", Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            OutlinedButton(onClick = { creating = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("新建歌单并加入")
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (playlists.isEmpty()) item { Text("还没有本地歌单，可以先创建一个。", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(playlists, key = { it.id }) { playlist ->
                val added = songs.all { song -> playlist.songs.any { it.key == song.key } }
                ListItem(
                    headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${playlist.songs.size} 首歌曲${if (added) " · 已包含所选歌曲" else ""}") },
                    leadingContent = { Icon(Icons.Rounded.LibraryMusic, null) },
                    modifier = Modifier.clickable(enabled = !busy) { vm.addToPlaylist(playlist.id, songs, onDismiss) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
    if (creating) PlaylistNameDialog(playlists, busy, onDismiss = { creating = false }, onSave = { name ->
        vm.createPlaylist(name, songs) { creating = false; onDismiss() }
    })
}
