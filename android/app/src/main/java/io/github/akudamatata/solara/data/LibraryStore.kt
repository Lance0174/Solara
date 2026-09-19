package io.github.akudamatata.solara.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class LibraryStore(context: Context) {
    private val prefs = context.getSharedPreferences("solara-library", Context.MODE_PRIVATE)
    private fun readSongs(key: String) = SongLists.parse(prefs.getString(key, "[]") ?: "[]", allowLocal = true)
    private val mutableLibrary = MutableStateFlow(Library(readSongs("playlistSongs"), readSongs("favoriteSongs")))
    val library = mutableLibrary.asStateFlow()
    // 独立存储命名歌单，升级时不迁移或覆盖已有播放队列、收藏及云同步字段。
    private val mutablePlaylists = MutableStateFlow(LocalPlaylists.decode(prefs.getString("localPlaylists.v1", "[]") ?: "[]"))
    val playlists = mutablePlaylists.asStateFlow()
    private val mutableSettings = MutableStateFlow(Settings(
        site = prefs.getString("site", "").orEmpty(),
        api = prefs.getString("api", DEFAULT_API) ?: DEFAULT_API,
        source = prefs.getString("searchSource", "netease") ?: "netease",
        quality = prefs.getString("playbackQuality", "320") ?: "320",
        theme = prefs.getString("theme", "system") ?: "system",
        themeStyle = prefs.getString("themeStyle", "default")?.takeIf { it in THEME_STYLES } ?: "default",
        genres = prefs.getStringSet("genres", GENRES.toSet())?.toSet() ?: GENRES.toSet(),
        cacheLimitGb = prefs.getInt("audioCacheLimitGb", MAX_AUDIO_CACHE_GB).coerceIn(1, MAX_AUDIO_CACHE_GB),
    ))
    val settings = mutableSettings.asStateFlow()

    fun saveSettings(value: Settings) {
        val saved = value.copy(cacheLimitGb = value.cacheLimitGb.coerceIn(1, MAX_AUDIO_CACHE_GB),
            themeStyle = value.themeStyle.takeIf { it in THEME_STYLES } ?: "default")
        prefs.edit().putString("site", value.site).putString("api", value.api)
            .putString("searchSource", value.source).putString("playbackQuality", value.quality)
            .putString("theme", value.theme).putStringSet("genres", value.genres)
            .putString("themeStyle", saved.themeStyle)
            .putInt("audioCacheLimitGb", saved.cacheLimitGb).apply()
        mutableSettings.value = saved
    }

    fun saveQueue(songs: List<Song>) {
        prefs.edit().putString("playlistSongs", SongLists.array(songs, includeLocal = true).toString()).apply()
        mutableLibrary.value = mutableLibrary.value.copy(queue = songs)
    }

    fun saveFavorites(songs: List<Song>) {
        prefs.edit().putString("favoriteSongs", SongLists.array(songs, includeLocal = true).toString()).apply()
        mutableLibrary.value = mutableLibrary.value.copy(favorites = songs)
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): LocalPlaylist {
        val existing = playlists.value
        LocalPlaylists.nameError(name, existing)?.let { throw IllegalArgumentException(it) }
        val playlist = LocalPlaylist(UUID.randomUUID().toString(), name.trim()).add(songs)
        savePlaylists(existing + playlist)
        return playlist
    }

    // 首次进入歌单相关入口时默认创建「我喜欢」，避免空歌单引导缺失；已有歌单则保持原样。
    fun ensureDefaultPlaylist(): List<LocalPlaylist> {
        val existing = playlists.value
        if (existing.isNotEmpty()) return existing
        val seeded = listOf(LocalPlaylist(UUID.randomUUID().toString(), "我喜欢"))
        savePlaylists(seeded)
        return seeded
    }

    fun renamePlaylist(id: String, name: String) {
        LocalPlaylists.nameError(name, playlists.value, id)?.let { throw IllegalArgumentException(it) }
        updatePlaylist(id) { it.copy(name = name.trim()) }
    }

    fun updatePlaylist(id: String, update: (LocalPlaylist) -> LocalPlaylist) {
        require(playlists.value.any { it.id == id }) { "歌单已被删除" }
        savePlaylists(playlists.value.map { if (it.id == id) update(it) else it })
    }

    fun deletePlaylist(id: String) { savePlaylists(playlists.value.filterNot { it.id == id }) }

    private fun savePlaylists(playlists: List<LocalPlaylist>) {
        prefs.edit().putString("localPlaylists.v1", LocalPlaylists.encode(playlists)).apply()
        mutablePlaylists.value = playlists
    }

    fun savePosition(index: Int, position: Long) {
        prefs.edit().putInt("index", index.coerceAtLeast(0)).putLong("position", position.coerceAtLeast(0)).apply()
    }
    val savedIndex get() = prefs.getInt("index", 0)
    val savedPosition get() = prefs.getLong("position", 0)
    var playMode: Int
        get() = prefs.getInt("playModeAndroid", 0)
        set(value) { prefs.edit().putInt("playModeAndroid", value).apply() }
}
