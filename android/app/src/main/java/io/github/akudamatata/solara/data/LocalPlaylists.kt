package io.github.akudamatata.solara.data

import org.json.JSONArray
import org.json.JSONObject

data class LocalPlaylist(val id: String, val name: String, val songs: List<Song> = emptyList()) {
    fun add(songs: List<Song>): LocalPlaylist {
        // 同一曲目的新下载可补齐本地地址，保持原有顺序且不重复添加。
        val incoming = songs.associateBy { it.key }
        val existing = this.songs.map { song -> incoming[song.key]?.takeIf { it.localUri.isNotBlank() } ?: song }
        val merged = SongLists.merge(existing, songs)
        require(merged.size <= SongLists.MAX_SONGS) { "一份歌单最多保存 ${SongLists.MAX_SONGS} 首歌曲，请新建歌单继续添加" }
        return copy(songs = merged)
    }

    fun remove(songKey: String): LocalPlaylist = copy(songs = songs.filterNot { it.key == songKey })

    fun move(songKey: String, offset: Int): LocalPlaylist {
        val index = songs.indexOfFirst { it.key == songKey }
        if (index < 0 || index + offset !in songs.indices) return this
        return copy(songs = songs.toMutableList().apply { add(index + offset, removeAt(index)) })
    }
}

object LocalPlaylists {
    const val MAX_NAME_LENGTH = 40

    fun nameError(name: String, playlists: List<LocalPlaylist>, editingId: String? = null): String? {
        val normalized = name.trim()
        return when {
            normalized.isBlank() -> "请输入歌单名称"
            normalized.length > MAX_NAME_LENGTH -> "歌单名称最多 $MAX_NAME_LENGTH 个字符"
            playlists.any { it.id != editingId && it.name.equals(normalized, ignoreCase = true) } -> "已有同名歌单，请换一个名称"
            else -> null
        }
    }

    fun encode(playlists: List<LocalPlaylist>): String = JSONArray().apply {
        playlists.forEach { playlist ->
            put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                .put("items", SongLists.array(playlist.songs, includeLocal = true)))
        }
    }.toString()

    // 仅从应用自身的持久化数据读取本地 URI，外部导入仍使用 SongLists 的受限解析。
    fun decode(text: String): List<LocalPlaylist> {
        val array = JSONArray(text)
        return (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            LocalPlaylist(json.getString("id"), json.getString("name"), SongLists.parse(json.getJSONArray("items").toString(), allowLocal = true))
        }
    }
}
