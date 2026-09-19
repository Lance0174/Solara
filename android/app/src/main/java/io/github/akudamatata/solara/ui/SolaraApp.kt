package io.github.akudamatata.solara.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.akudamatata.solara.MusicViewModel
import io.github.akudamatata.solara.PlaybackState
import io.github.akudamatata.solara.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolaraApp(vm: MusicViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val player by vm.playback.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    SolaraTheme(settings) {
        val colors = MaterialTheme.colorScheme
        val endfield = LocalEndfieldTheme.current
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var searchOrigin by rememberSaveable { mutableIntStateOf(0) }
        val pageState = rememberSaveableStateHolder()
        val focus = LocalFocusManager.current
        var actions by remember { mutableStateOf<Song?>(null) }
        var qualitySong by remember { mutableStateOf<Song?>(null) }
        var playlistSongs by remember { mutableStateOf<List<Song>?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        fun requestNotifications() { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        fun openSearch() { if (tab != 1) { searchOrigin = tab; tab = 1 } }
        LaunchedEffect(vm) { vm.notices.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(tab) { focus.clearFocus(); if (tab == 2) vm.ensureDefaultPlaylist() }
        BackHandler(enabled = tab != 0) { tab = if (tab == 1) searchOrigin else 0 }

        Scaffold(
            containerColor = colors.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Solara", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text(if (endfield) "音乐终端 · 终末地风格" else "光域 · 音乐随行", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    } },
                    navigationIcon = {
                        when (tab) {
                            0 -> ActionIcon(Icons.Rounded.Radar, "探索雷达", enabled = !busy && player.ready) { vm.radar() }
                            1 -> ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") { tab = searchOrigin }
                            else -> Icon(Icons.Rounded.GraphicEq, null, Modifier.padding(start = 22.dp), tint = colors.primary)
                        }
                    },
                    actions = { if (tab != 1) ActionIcon(Icons.Rounded.Search, "搜索", action = ::openSearch) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    if (tab != 0 && player.song != null) MiniPlayer(player, vm, onOpen = { tab = 0 })
                    NavigationBar(containerColor = colors.surface.copy(alpha = 0.96f)) {
                        listOf(Triple(0, "播放", Icons.Rounded.Album),
                            Triple(2, "音乐库", Icons.AutoMirrored.Rounded.QueueMusic),
                            Triple(3, "下载", Icons.Rounded.Download),
                            Triple(4, "设置", Icons.Rounded.Settings)).forEach { (index, title, icon) ->
                            NavigationBarItem(selected = tab == index || (tab == 1 && searchOrigin == index), onClick = { tab = index },
                                icon = { Icon(icon, null) }, label = { Text(title) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).background(Brush.verticalGradient(
                if (endfield) listOf(colors.background, colors.background) else listOf(colors.primaryContainer.copy(alpha = 0.4f), colors.background)))) {
                pageState.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> PlayerScreen(vm, onQueue = { pageState.removeState(2); tab = 2 },
                            onDownload = { qualitySong = it }, onPlay = { requestNotifications(); vm.togglePlayback() },
                            onAddToPlaylist = { playlistSongs = it })
                        1 -> SearchScreen(vm, onSong = { requestNotifications(); vm.play(it) }, onActions = { actions = it })
                        2 -> LibraryScreen(vm, onSong = { requestNotifications(); vm.play(it) }, onActions = { actions = it },
                            onPlayList = { songs, song -> requestNotifications(); vm.playAll(songs, song) },
                            onAddToPlaylist = { playlistSongs = it }, onSearch = ::openSearch)
                        3 -> DownloadsScreen(vm, onAddToPlaylist = { playlistSongs = listOf(it) })
                        4 -> SettingsScreen(vm)
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        actions?.let { song ->
            ModalBottomSheet(onDismissRequest = { actions = null }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(song.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(song.artist, color = colors.onSurfaceVariant)
                }
                SheetAction(Icons.Rounded.PlayArrow, "立即播放") { actions = null; requestNotifications(); vm.play(song) }
                SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "加入播放列表") { vm.addSongs(listOf(song)); actions = null }
                SheetAction(Icons.Rounded.LibraryMusic, "加入本地歌单") { actions = null; playlistSongs = listOf(song) }
                val saved = library.favorites.any { it.key == song.key }
                SheetAction(if (saved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (saved) "取消收藏" else "收藏歌曲") { vm.favorite(song); actions = null }
                SheetAction(Icons.Rounded.Download, "下载到手机") { actions = null; qualitySong = song }
                if (library.queue.any { it.key == song.key }) {
                    SheetAction(Icons.Rounded.ArrowUpward, "在播放列表中上移") { vm.moveUp(song); actions = null }
                    SheetAction(Icons.Rounded.RemoveCircleOutline, "从播放列表移除") { vm.remove(song); actions = null }
                }
                Spacer(Modifier.height(16.dp))
                }
            }
        }
        qualitySong?.let { song ->
            ModalBottomSheet(onDismissRequest = { qualitySong = null }) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text("下载音质", style = MaterialTheme.typography.titleLarge)
                    Text(song.name, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("保存到 Music/Solara，实际音质取决于音源", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                QUALITIES.forEach { (value, label) ->
                    SheetAction(Icons.Rounded.Download, label) {
                        qualitySong = null; requestNotifications(); vm.download(song, value); tab = 3
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        playlistSongs?.let { songs -> LocalPlaylistPicker(vm, songs, onDismiss = { playlistSongs = null }) }
    }
}

@Composable
fun ActionIcon(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    IconButton(onClick = action, enabled = enabled) { Icon(icon, contentDescription = label) }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, action: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = action), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}

@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f))
        Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        Text(detail, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun SongRow(song: Song, favorite: Boolean, playing: Boolean, vm: MusicViewModel, onPlay: () -> Unit, onActions: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(solaraShape(18.dp))
        .background(if (playing) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
        .clickable(onClick = onPlay).padding(start = 10.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        SongArtwork(song, vm, Modifier.size(48.dp).clip(solaraShape(12.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(song.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (playing) FontWeight.Bold else FontWeight.Medium)
            Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (song.localUri.isNotBlank()) "本地音频" else SOURCES[song.source].orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        ActionIcon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (favorite) "取消收藏" else "收藏") { vm.favorite(song) }
        ActionIcon(Icons.Rounded.MoreVert, "歌曲操作", action = onActions)
    }
}

@Composable
private fun SearchScreen(vm: MusicViewModel, onSong: (Song) -> Unit, onActions: (Song) -> Unit) {
    val state by vm.search.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val player by vm.playback.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Text("找到下一首心动", Modifier.padding(vertical = 14.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = query, onValueChange = { query = it }, Modifier.fillMaxWidth(),
            singleLine = true, placeholder = { Text("歌曲、歌手或专辑") }, shape = solaraShape(24.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { ActionIcon(Icons.AutoMirrored.Rounded.ArrowForward, "搜索", enabled = query.isNotBlank()) { vm.search(query); focus.clearFocus() } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search(query); focus.clearFocus() }))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceMenu(SOURCES[settings.source].orEmpty(), SOURCES, vm::selectSource)
            Spacer(Modifier.weight(1f))
            if (state.songs.isNotEmpty()) TextButton(onClick = { vm.addSongs(state.songs) }) { Text("全部加入") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.error.isNotBlank()) item {
                ErrorCard(state.error) { vm.search(state.query, nextPage = state.songs.isNotEmpty()) }
            }
            if (state.songs.isEmpty() && !state.busy && state.error.isBlank()) item {
                EmptyState(Icons.Rounded.Search, if (state.page == 0) "音乐，从一次搜索开始" else "暂时没有找到歌曲",
                    if (state.page == 0) "选择音源，搜索你想听的声音" else "可以换个关键词或切换音源")
            }
            items(state.songs, key = { it.key }) { song ->
                SongRow(song, library.favorites.any { it.key == song.key }, player.song?.key == song.key, vm, { onSong(song) }, { onActions(song) })
            }
            if (state.hasMore && state.error.isBlank()) item {
                TextButton(onClick = { vm.search(state.query, nextPage = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
            }
        }
    }
}

@Composable
fun ChoiceMenu(label: String, options: Map<String, String>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text(label); Icon(Icons.Rounded.ExpandMore, null, Modifier.size(18.dp)) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChoose(key) }) }
        }
    }
}

@Composable
fun ErrorCard(message: String, retry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = solaraShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = retry) { Text("重试") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    vm: MusicViewModel,
    onSong: (Song) -> Unit,
    onActions: (Song) -> Unit,
    onPlayList: (List<Song>, Song?) -> Unit,
    onAddToPlaylist: (List<Song>) -> Unit,
    onSearch: () -> Unit,
) {
    val library by vm.library.collectAsStateWithLifecycle()
    val player by vm.playback.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableIntStateOf(0) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    val playlist = playlists.firstOrNull { it.id == playlistId }
    val favorites = section == 1
    val overview = section == 2 && playlist == null
    var importingNetease by rememberSaveable { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var playlistActionSong by remember { mutableStateOf<Song?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var documentFavorites by rememberSaveable { mutableStateOf(false) }
    var documentPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { vm.importList(it, documentFavorites, documentPlaylistId) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { vm.exportList(it, documentFavorites, documentPlaylistId) } }
    // 多选本地音频文件导入歌单。
    val localAudioImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) playlist?.let { vm.importLocalAudio(uris, it.id) }
    }
    val songs = playlist?.songs ?: if (favorites) library.favorites else library.queue
    var query by rememberSaveable(section, playlistId) { mutableStateOf("") }
    val filteredSongs = remember(songs, query) { SongLists.search(songs, query) }
    val listState = key(section, playlistId, query) { rememberLazyListState() }
    val focus = LocalFocusManager.current
    BackHandler(enabled = playlist != null) { playlistId = null }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (playlist != null) ActionIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回本地歌单") { playlistId = null }
            Text(playlist?.name ?: "音乐库", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (overview) ActionIcon(Icons.Rounded.Add, "新建本地歌单") { naming = true }
            else Box {
                ActionIcon(Icons.Rounded.MoreHoriz, "歌单管理") { menu = true }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("导入歌单") }, onClick = { menu = false; documentFavorites = favorites; documentPlaylistId = playlist?.id; importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    if (playlist != null) DropdownMenuItem(text = { Text("导入网易云歌单") }, onClick = { menu = false; vm.clearPlaylistImport(); importingNetease = true })
                    if (playlist != null) DropdownMenuItem(text = { Text("导入本地歌曲") }, onClick = { menu = false; localAudioImporter.launch(arrayOf("audio/*")) })
                    DropdownMenuItem(text = { Text("导出歌单") }, onClick = { menu = false; documentFavorites = favorites; documentPlaylistId = playlist?.id; exporter.launch("solara-${if (favorites) "favorites" else "playlist"}.json") }, enabled = songs.isNotEmpty())
                    DropdownMenuItem(text = { Text("保存到本地歌单") }, onClick = { menu = false; onAddToPlaylist(songs) }, enabled = songs.isNotEmpty())
                    if (favorites || playlist != null) DropdownMenuItem(text = { Text("全部加入播放列表") }, onClick = { menu = false; vm.addSongs(songs) }, enabled = songs.isNotEmpty())
                    if (playlist != null) {
                        DropdownMenuItem(text = { Text("重命名歌单") }, onClick = { menu = false; naming = true })
                        DropdownMenuItem(text = { Text("删除歌单") }, onClick = { menu = false; confirmDelete = true })
                    } else DropdownMenuItem(text = { Text("清空当前列表") }, onClick = { menu = false; confirmClear = true }, enabled = songs.isNotEmpty())
                }
            }
        }
        if (playlist == null) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            listOf("播放队列", "收藏", "本地歌单").forEachIndexed { index, title ->
                SegmentedButton(selected = section == index, onClick = { section = index; playlistId = null; menu = false }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(title, maxLines = 1) }
            }
        }
        if (!overview) OutlinedTextField(value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
            placeholder = { Text("搜索歌单内的歌曲、歌手或专辑") }, shape = solaraShape(24.dp),
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) ActionIcon(Icons.Rounded.Close, "清空歌单搜索") { query = "" } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (overview) "${playlists.size} 个本地歌单" else if (query.isNotBlank()) "匹配 ${filteredSongs.size} / ${songs.size} 首"
                else "${songs.size} 首歌曲", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!overview) TextButton(onClick = { onPlayList(songs, null) }, enabled = songs.isNotEmpty() && player.ready) { Icon(Icons.Rounded.PlayArrow, null); Text("全部播放") }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 20.dp)) {
            if (overview) {
                item {
                    OutlinedButton(onClick = { vm.clearPlaylistImport(); importingNetease = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text("导入网易云歌单") }
                }
                if (playlists.isEmpty()) item {
                    EmptyState(Icons.Rounded.LibraryMusic, "为喜欢的音乐建个歌单", "歌单保存在本机，可从搜索、收藏、播放队列和下载中添加歌曲")
                    OutlinedButton(onClick = { naming = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text("新建本地歌单") }
                }
                items(playlists, key = { it.id }) { local ->
                    ListItem(headlineContent = { Text(local.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${local.songs.size} 首歌曲") }, leadingContent = { Icon(Icons.Rounded.LibraryMusic, null) },
                        trailingContent = { ActionIcon(Icons.Rounded.PlayArrow, "播放${local.name}", enabled = local.songs.isNotEmpty() && player.ready) { onPlayList(local.songs, null) } },
                        modifier = Modifier.clip(solaraShape(16.dp)).clickable { playlistId = local.id },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            } else if (songs.isEmpty()) item {
                if (playlist != null) {
                    EmptyState(Icons.Rounded.LibraryMusic, "这份歌单还是空的", "在歌曲菜单选择“加入本地歌单”，也可从右上角导入已有歌单或导入手机里的本地歌曲")
                    OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) { Text("去搜索歌曲") }
                } else {
                    EmptyState(if (favorites) Icons.Rounded.FavoriteBorder else Icons.AutoMirrored.Rounded.QueueMusic,
                        if (favorites) "把喜欢的歌留在这里" else "播放列表还是空的", "从搜索添加歌曲，或导入网页版导出的歌单")
                    if (!favorites) OutlinedButton(onClick = { vm.radar() }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                        Icon(Icons.Rounded.Radar, null); Spacer(Modifier.width(8.dp)); Text("探索雷达 · 找点新音乐")
                    }
                }
            } else if (filteredSongs.isEmpty()) item {
                EmptyState(Icons.Rounded.Search, "歌单内没有匹配的歌曲", "试试其他歌名、歌手或专辑，或清空搜索查看全部歌曲")
            }
            if (!overview) items(filteredSongs, key = { it.key }) { song ->
                SongRow(song, library.favorites.any { it.key == song.key }, player.song?.key == song.key, vm,
                    onPlay = { focus.clearFocus(); if (favorites || playlist != null) onPlayList(songs, song) else onSong(song) },
                    onActions = { if (playlist != null) playlistActionSong = song else onActions(song) })
            }
        }
    }
    if (importingNetease) NeteasePlaylistDialog(vm, onDismiss = { importingNetease = false },
        onImported = { id -> section = 2; playlistId = id }, initialPlaylistId = playlist?.id)
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清空${if (favorites) "收藏" else "播放"}列表？") },
        text = { Text(if (favorites) "将移除本机的全部收藏。" else "将移除本机播放队列并停止播放。已下载的音乐仍保留。") },
        confirmButton = { TextButton(onClick = { vm.clear(favorites); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
    if (naming) PlaylistNameDialog(playlists, busy, onDismiss = { naming = false }, playlist = playlist, onSave = { name ->
        if (playlist == null) vm.createPlaylist(name) { id -> naming = false; playlistId = id }
        else vm.renamePlaylist(playlist.id, name) { naming = false }
    })
    if (confirmDelete && playlist != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除「${playlist.name}」？") },
        text = { Text("只删除这份本地歌单。当前播放队列、收藏和已下载的音乐文件都会保留。") },
        confirmButton = { TextButton(onClick = { vm.deletePlaylist(playlist.id); confirmDelete = false; playlistId = null }, enabled = !busy) { Text("删除歌单") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    playlistActionSong?.let { song ->
        if (playlist != null) ModalBottomSheet(onDismissRequest = { playlistActionSong = null }) {
            Text(song.name, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val index = playlist.songs.indexOfFirst { it.key == song.key }
            if (index > 0) SheetAction(Icons.Rounded.ArrowUpward, "在此歌单中上移") { vm.movePlaylistSong(playlist.id, song.key, -1); playlistActionSong = null }
            if (index in 0 until playlist.songs.lastIndex) SheetAction(Icons.Rounded.ArrowDownward, "在此歌单中下移") { vm.movePlaylistSong(playlist.id, song.key, 1); playlistActionSong = null }
            SheetAction(Icons.Rounded.RemoveCircleOutline, "从此歌单移除") { vm.removeFromPlaylist(playlist.id, song.key); playlistActionSong = null }
            SheetAction(Icons.Rounded.MoreHoriz, "其他歌曲操作") { playlistActionSong = null; onActions(song) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
