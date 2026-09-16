package io.github.akudamatata.solara

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.akudamatata.solara.data.*
import io.github.akudamatata.solara.download.DownloadWorker
import io.github.akudamatata.solara.playback.PlaybackService
import io.github.akudamatata.solara.playback.SleepTimer
import io.github.akudamatata.solara.playback.mediaItem
import io.github.akudamatata.solara.playback.song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.FileNotFoundException

data class PlaybackState(
    val ready: Boolean = false, val song: Song? = null, val playing: Boolean = false,
    val buffering: Boolean = false, val position: Long = 0, val duration: Long = 0,
    val mode: Int = 0, val artwork: String = "", val error: String = "",
    val sleepTimerRemainingMs: Long = 0,
)
data class SearchState(
    val query: String = "", val songs: List<Song> = emptyList(), val busy: Boolean = false,
    val page: Int = 0, val hasMore: Boolean = false, val error: String = "",
)
data class LyricsState(val lines: List<LyricLine> = emptyList(), val busy: Boolean = false, val error: String = "")
data class PlaylistImportState(
    val playlist: NeteasePlaylist? = null, val busy: Boolean = false, val error: String = "",
    val loaded: Int = 0, val total: Int? = null,
)

@OptIn(UnstableApi::class)
class MusicViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as SolaraApplication
    val library = app.store.library
    val playlists = app.store.playlists
    val settings = app.store.settings
    val cacheBytes = app.audioCache.bytes
    private val mutablePlayback = MutableStateFlow(PlaybackState())
    val playback = mutablePlayback.asStateFlow()
    private val mutableSearch = MutableStateFlow(SearchState())
    val search = mutableSearch.asStateFlow()
    private val mutableLyrics = MutableStateFlow(LyricsState())
    val lyrics = mutableLyrics.asStateFlow()
    private val mutablePlaylistImport = MutableStateFlow(PlaylistImportState())
    val playlistImport = mutablePlaylistImport.asStateFlow()
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val notices = messages.asSharedFlow()
    private val work = WorkManager.getInstance(app)
    val downloads = work.getWorkInfosByTagFlow("solara-download").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var lyricJob: Job? = null
    private var playlistImportJob: Job? = null
    private var loadedLyricsKey: String? = null
    private val controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refreshPlayer() }
        override fun onPlayerError(error: PlaybackException) {
            mutablePlayback.value = mutablePlayback.value.copy(error = "播放失败，请重试或切换音质、音源（${error.errorCodeName}）")
        }
    }

    init {
        controllerFuture.addListener({
            try {
                controller = controllerFuture.get().also { it.addListener(listener) }
                refreshPlayer()
            } catch (_: Exception) { notice("播放器连接失败，请重新打开应用") }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (isActive) { delay(300); refreshPlayer() }
        }
        viewModelScope.launch {
            // 清理进程被终止后留下的未完成下载，已完成音乐不在此记录中。
            work.getWorkInfosByTagFlow("solara-download").collect { infos ->
                withContext(Dispatchers.IO) {
                    val pending = app.getSharedPreferences("solara-pending-downloads", Application.MODE_PRIVATE)
                    infos.filter { it.state.isFinished }.forEach { info ->
                        pending.getString(info.id.toString(), null)?.let { uri ->
                            app.contentResolver.delete(Uri.parse(uri), null, null)
                            pending.edit().remove(info.id.toString()).commit()
                        }
                    }
                }
            }
        }
    }

    fun notice(text: String) { messages.tryEmit(text) }

    private fun refreshPlayer() {
        val player = controller ?: return
        val song = player.currentMediaItem?.song()
        mutablePlayback.value = PlaybackState(
            ready = true, song = song, playing = player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_ENDED,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            position = player.currentPosition.coerceAtLeast(0), duration = player.duration.coerceAtLeast(0),
            mode = if (player.shuffleModeEnabled) 2 else if (player.repeatMode == Player.REPEAT_MODE_ONE) 1 else 0,
            artwork = player.mediaMetadata.artworkUri?.toString().orEmpty(),
            error = if (player.playerError == null) "" else mutablePlayback.value.error,
            sleepTimerRemainingMs = app.sleepTimer.remainingMillis(),
        )
        if (song?.key != loadedLyricsKey) {
            loadedLyricsKey = song?.key
            if (song != null) loadLyrics(song) else { lyricJob?.cancel(); mutableLyrics.value = LyricsState() }
        }
    }

    fun loadLyrics(song: Song? = playback.value.song) {
        if (song == null) return
        lyricJob?.cancel()
        lyricJob = viewModelScope.launch {
            mutableLyrics.value = LyricsState(busy = true)
            try { mutableLyrics.value = LyricsState(lines = withContext(Dispatchers.IO) { app.api.lyrics(song) }) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableLyrics.value = LyricsState(error = "歌词暂不可用，点击重试") }
        }
    }

    fun search(query: String, nextPage: Boolean = false) {
        val text = query.trim()
        if (text.isBlank()) return
        val source = settings.value.source
        val old = mutableSearch.value
        val page = if (nextPage) old.page + 1 else 1
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            mutableSearch.value = if (nextPage) old.copy(busy = true, error = "") else SearchState(query = text, busy = true)
            try {
                val songs = withContext(Dispatchers.IO) { app.api.search(text, source, page) }
                mutableSearch.value = SearchState(text, if (nextPage) SongLists.merge(old.songs, songs) else songs,
                    page = page, hasMore = songs.size >= 20)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableSearch.value = mutableSearch.value.copy(busy = false, error = error.message ?: "搜索失败，请重试")
            }
        }
    }

    fun selectSource(source: String) {
        app.store.saveSettings(settings.value.copy(source = source))
        if (search.value.query.isNotBlank()) search(search.value.query)
    }

    fun play(song: Song) {
        val player = controller ?: return notice("播放器正在连接，请稍候")
        if (player.currentMediaItem?.mediaId == song.key && player.currentMediaItem?.song()?.localUri == song.localUri) {
            togglePlayback()
            return
        }
        var index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == song.key }
        if (index == null) { player.addMediaItem(song.mediaItem()); index = player.mediaItemCount - 1 }
        else if (player.getMediaItemAt(index).song()?.localUri != song.localUri) player.replaceMediaItem(index, song.mediaItem())
        player.seekTo(index, 0)
        player.prepare()
        player.play()
    }

    fun togglePlayback() {
        val player = controller ?: return
        if (player.playWhenReady && player.playerError == null && player.playbackState != Player.STATE_ENDED) player.pause() else {
            if (player.mediaItemCount == 0) return notice("先搜索并选择一首歌曲")
            player.prepare()
            player.play()
        }
    }

    fun setSleepTimer(minutes: Int) {
        if (controller == null) return notice("播放器正在连接，请稍候")
        if (minutes !in 1..SleepTimer.MAX_MINUTES) return notice("请输入 1–${SleepTimer.MAX_MINUTES} 分钟")
        app.sleepTimer.start(minutes)
        refreshPlayer()
        notice("将在 $minutes 分钟后暂停音乐")
    }

    fun cancelSleepTimer() {
        app.sleepTimer.cancel()
        refreshPlayer()
        notice("已取消定时关闭")
    }

    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun next() { controller?.seekToNextMediaItem() }

    fun changeMode() {
        val player = controller ?: return
        val mode = (playback.value.mode + 1) % 3
        player.shuffleModeEnabled = mode == 2
        player.repeatMode = if (mode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
    }

    fun quality(quality: String) {
        app.store.saveSettings(settings.value.copy(quality = quality))
        controller?.let { player ->
            if (player.mediaItemCount == 0 || playback.value.song?.localUri?.isNotBlank() == true) return@let
            val position = player.currentPosition
            val playing = player.playWhenReady
            player.stop()
            player.prepare()
            player.seekTo(position)
            player.playWhenReady = playing
        }
    }

    fun addSongs(songs: List<Song>) {
        val player = controller ?: return notice("播放器正在连接，请稍候")
        val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val newSongs = songs.distinctBy { it.key }.filter { it.key !in existing }
        player.addMediaItems(newSongs.map { it.mediaItem() })
        notice(if (newSongs.isEmpty()) "歌曲已在播放列表中" else "已加入 ${newSongs.size} 首歌曲")
    }

    fun playAll(songs: List<Song>, startSong: Song? = null) {
        if (songs.isEmpty()) return
        val player = controller ?: return notice("播放器正在连接，请稍候")
        val ordered = songs.distinctBy { it.key }
        val index = ordered.indexOfFirst { it.key == startSong?.key }.coerceAtLeast(0)
        val currentQueue = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).song() }
        if (startSong != null && currentQueue == ordered && player.currentMediaItem?.mediaId == startSong.key) {
            togglePlayback()
            return
        }
        // 收藏或命名歌单选歌时，同时带入列表顺序，下一首继续播放该歌单。
        player.setMediaItems(ordered.map { it.mediaItem() }, index, 0)
        player.prepare()
        player.play()
    }

    fun remove(song: Song) {
        val player = controller ?: return
        (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == song.key }?.let { player.removeMediaItem(it) }
    }

    fun moveUp(song: Song) {
        val player = controller ?: return
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == song.key } ?: return
        if (index > 0) player.moveMediaItem(index, index - 1)
    }

    fun clear(favorites: Boolean) {
        if (favorites) app.store.saveFavorites(emptyList()) else controller?.clearMediaItems()
    }

    fun favorite(song: Song) {
        val saved = library.value.favorites
        app.store.saveFavorites(if (saved.any { it.key == song.key }) saved.filterNot { it.key == song.key } else saved + song)
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList(), onCreated: (String) -> Unit) = operation {
        val playlist = app.store.createPlaylist(name, songs)
        notice("已创建「${playlist.name}」")
        onCreated(playlist.id)
    }

    // 首次进入音乐库时确保有默认「我喜欢」歌单；之后保持用户已有歌单不变。
    fun ensureDefaultPlaylist() { app.store.ensureDefaultPlaylist() }

    fun readNeteasePlaylist(input: String) {
        playlistImportJob?.cancel()
        playlistImportJob = viewModelScope.launch {
            mutablePlaylistImport.value = PlaylistImportState(busy = true)
            try {
                val playlist = withContext(Dispatchers.IO) {
                    app.api.neteasePlaylist(input) { loaded, total ->
                        withContext(Dispatchers.Main) {
                            mutablePlaylistImport.value = PlaylistImportState(busy = true, loaded = loaded, total = total)
                        }
                    }
                }
                mutablePlaylistImport.value = PlaylistImportState(playlist = playlist)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutablePlaylistImport.value = PlaylistImportState(error = error.message ?: "读取歌单失败，请重试")
            }
        }
    }

    fun clearPlaylistImport() {
        playlistImportJob?.cancel()
        mutablePlaylistImport.value = PlaylistImportState()
    }

    fun renamePlaylist(id: String, name: String, onRenamed: () -> Unit) = operation {
        app.store.renamePlaylist(id, name)
        onRenamed()
    }

    fun deletePlaylist(id: String) = operation {
        app.store.deletePlaylist(id)
        notice("歌单已删除，歌曲文件仍保留")
    }

    fun addToPlaylist(id: String, songs: List<Song>, onAdded: () -> Unit) = operation {
        val before = playlists.value.firstOrNull { it.id == id } ?: error("歌单已被删除")
        val updated = before.add(songs)
        app.store.updatePlaylist(id) { updated }
        val added = updated.songs.size - before.songs.size
        notice(when {
            added > 0 -> "已向「${before.name}」加入 $added 首歌曲"
            updated != before -> "已更新歌单中的本地音频"
            else -> "这些歌曲已在歌单中"
        })
        onAdded()
    }

    fun removeFromPlaylist(id: String, songKey: String) = operation {
        app.store.updatePlaylist(id) { it.remove(songKey) }
    }

    fun movePlaylistSong(id: String, songKey: String, offset: Int) = operation {
        app.store.updatePlaylist(id) { it.move(songKey, offset) }
    }

    fun radar() = operation {
        val keyword = settings.value.genres.ifEmpty { GENRES.toSet() }.random()
        val songs = withContext(Dispatchers.IO) { app.api.search(keyword, listOf("netease", "kuwo").random()) }
        if (songs.isEmpty()) notice("探索雷达暂未找到歌曲，请稍后再试") else addSongs(songs)
    }

    fun download(song: Song, quality: String) {
        val data = song.toJson().toString()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf("song" to data, "quality" to quality))
            .addTag("solara-download").addTag("song:$data").addTag("quality:$quality")
            .addTag("created:${System.currentTimeMillis()}")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .keepResultsForAtLeast(365, TimeUnit.DAYS).build()
        work.enqueueUniqueWork("solara:${song.key}:$quality", ExistingWorkPolicy.KEEP, request)
        notice("已加入下载列表，文件将保存到 Music/Solara")
    }

    fun cancelDownload(id: UUID) { work.cancelWorkById(id) }

    fun playDownloaded(info: WorkInfo) = useDownloadedSong(info, ::play)

    fun useDownloadedSong(info: WorkInfo, action: (Song) -> Unit) = operation {
        val uri = info.outputData.getString("uri") ?: error("下载记录缺少文件地址，请重新下载")
        val song = downloadSong(info) ?: error("下载记录缺少歌曲信息，请重新下载")
        withContext(Dispatchers.IO) {
            try {
                app.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { } ?: error("文件已被移除，请重新下载")
            } catch (_: FileNotFoundException) { error("文件已被移除，请重新下载") }
        }
        action(song.copy(localUri = uri))
    }

    fun saveSettings(value: Settings, password: String, onSaved: () -> Unit) = operation {
        val api = value.api.trim().toHttpUrlOrNull()
        require(api != null && api.isHttps && api.username.isBlank() && api.password.isBlank()) { "音乐 API 请填写不含账号口令的 HTTPS 地址" }
        val site = value.site.trim().trimEnd('/')
        if (site.isNotBlank()) {
            val url = site.toHttpUrlOrNull()
            require(url != null && url.isHttps && url.query == null && url.fragment == null && url.username.isBlank() && url.password.isBlank()) {
                "Solara 站点请填写 HTTPS 地址，例如 https://music.example.com"
            }
            if (password.isNotEmpty()) withContext(Dispatchers.IO) { app.api.login(site, password) }
        }
        app.store.saveSettings(value.copy(site = site, api = api.toString()))
        notice("设置已保存")
        onSaved()
    }

    fun refreshAudioCache() = operation { withContext(Dispatchers.IO) { app.audioCache.clean() } }

    fun setCacheLimit(gigabytes: Int) = operation {
        app.store.saveSettings(settings.value.copy(cacheLimitGb = gigabytes))
        withContext(Dispatchers.IO) { app.audioCache.clean() }
        notice("缓存上限已设为 ${settings.value.cacheLimitGb} GB")
    }

    fun clearAudioCache() = operation {
        val deferred = withContext(Dispatchers.IO) { app.audioCache.clear() }
        notice(if (deferred) "已清理可释放缓存，正在使用的部分会在释放后清理" else "播放缓存已清理")
    }

    fun logout() { app.cookies.clear(); notice("已退出站点登录") }

    fun sync() = operation {
        val remote = withContext(Dispatchers.IO) { app.api.readRemote() }
        val current = library.value
        val merged = Library(SongLists.merge(current.queue, remote.queue), SongLists.merge(current.favorites, remote.favorites))
        withContext(Dispatchers.IO) { app.api.writeRemote(merged) }
        addSongs(remote.queue)
        app.store.saveFavorites(SongLists.merge(library.value.favorites, remote.favorites))
        notice("播放列表和收藏已合并同步")
    }

    fun importList(uri: Uri, favorites: Boolean, playlistId: String? = null) = operation {
        val songs = withContext(Dispatchers.IO) {
            val text = app.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = ByteArray(4 * 1024 * 1024 + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read == -1) break
                    count += read
                }
                require(count < bytes.size) { "歌单文件不能超过 4 MB" }
                String(bytes, 0, count, Charsets.UTF_8)
            } ?: error("无法读取歌单文件")
            SongLists.parse(text)
        }
        when {
            playlistId != null -> app.store.updatePlaylist(playlistId) { it.add(songs) }
            favorites -> app.store.saveFavorites(SongLists.merge(library.value.favorites, songs))
            else -> addSongs(songs)
        }
        notice("已合并导入 ${songs.size} 首歌曲")
    }

    fun exportList(uri: Uri, favorites: Boolean, playlistId: String? = null) = operation {
        val playlist = playlistId?.let { id -> playlists.value.firstOrNull { it.id == id } ?: error("歌单已被删除") }
        val songs = playlist?.songs ?: if (favorites) library.value.favorites else library.value.queue
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(SongLists.export(songs, favorites)) }
                ?: error("无法写入歌单文件")
        }
        notice("已导出 ${songs.size} 首歌曲")
    }

    private fun operation(block: suspend () -> Unit) {
        if (mutableBusy.value) return notice("上一项操作仍在处理中，请稍候再试")
        viewModelScope.launch {
            mutableBusy.value = true
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                Log.w("Solara操作", "操作未完成：${error.javaClass.simpleName}")
                notice(error.message ?: "操作失败，请重试")
            } finally { mutableBusy.value = false }
        }
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}

fun downloadSong(info: WorkInfo): Song? = info.tags.firstOrNull { it.startsWith("song:") }?.removePrefix("song:")?.let { Song.fromJson(JSONObject(it)) }
fun downloadQuality(info: WorkInfo): String = info.tags.firstOrNull { it.startsWith("quality:") }?.removePrefix("quality:") ?: "320"
