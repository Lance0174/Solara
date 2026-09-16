package io.github.akudamatata.solara.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class NeteasePlaylist(
    val name: String,
    val songs: List<Song>,
    val totalCount: Int?,
    val trackIds: List<String> = emptyList(),
    // 歌单声明存在、但当前无法读取的曲目数（下架、版权限制或字段缺失）。明确展示，避免静默少导。
    val unreadableCount: Int = 0,
) {
    val partial: Boolean get() = totalCount != null && songs.size < totalCount

    fun add(songs: List<Song>): NeteasePlaylist {
        val byId = SongLists.merge(this.songs, songs).associateBy { it.id }
        val order = (trackIds + byId.keys).distinct()
        val merged = order.mapNotNull { byId[it] }
        // 补齐后再按声明总数重算不可读取数量，避免沿用补齐前的旧值。
        return copy(songs = merged, unreadableCount = (totalCount?.minus(merged.size))?.coerceAtLeast(0) ?: 0)
    }
}

object NeteasePlaylists {
    private val idPattern = Regex("[1-9][0-9]{0,19}")
    private val pagePattern = Regex("/(?:m/)?playlist(?:/([0-9]+))?/?")
    private val linkPattern = Regex("https?://[^\\s<>\"'，。；、（）【】《》]+", RegexOption.IGNORE_CASE)
    private val hosts = setOf("music.163.com", "y.music.163.com", "163cn.tv")

    fun inputUrl(input: String): HttpUrl {
        val text = input.trim()
        if (idPattern.matches(text)) return "https://music.163.com/playlist?id=$text".toHttpUrl()
        val links = linkPattern.findAll(text).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.toList()
        require(links.size == 1) { "请粘贴一个网易云歌单链接或数字歌单 ID" }
        val url = checkedUrl(links.single().toHttpUrlOrNull())
        require(playlistId(url) != null || url.host == "163cn.tv") { "这不是歌单链接，请在网易云歌单页面复制分享链接" }
        return url
    }

    fun checkedUrl(url: HttpUrl?): HttpUrl {
        require(url != null && url.host in hosts && url.port == HttpUrl.defaultPort(url.scheme)
            && url.username.isBlank() && url.password.isBlank()) { "仅支持网易云音乐的歌单链接" }
        // 分享文字中的旧 HTTP 链接也通过 HTTPS 读取，不放宽 Android 的明文网络策略。
        return url.newBuilder().scheme("https").port(443).build()
    }

    fun playlistId(url: HttpUrl): String? {
        if (url.host !in hosts || url.host == "163cn.tv") return null
        val page = url.fragment?.takeIf { it.startsWith('/') }?.let { url.resolve(it) } ?: url
        if (page.host != url.host) return null
        val path = pagePattern.matchEntire(page.encodedPath) ?: return null
        val id = path.groupValues[1].ifBlank { page.queryParameterValues("id").singleOrNull().orEmpty() }
        return id.takeIf { idPattern.matches(it) }
    }

    fun parse(root: JSONObject, id: String): NeteasePlaylist {
        if (root.optInt("code", 200) != 200) throw IOException("无法读取歌单，请确认歌单公开且链接有效后重试")
        val playlist = root.optJSONObject("playlist") ?: throw IOException("当前音乐接口不支持读取网易云歌单")
        if (playlist.text("id") != id || playlist.optInt("privacy", 0) != 0) {
            throw IOException("无法读取这份公开歌单，请检查歌单 ID 和分享设置")
        }
        val tracks = playlist.optJSONArray("tracks") ?: throw IOException("接口没有返回歌单歌曲，请稍后重试")
        val songs = parseSongs(tracks)
        val trackIds = playlist.optJSONArray("trackIds")
        val ids = (0 until (trackIds?.length() ?: 0)).mapNotNull {
            trackIds?.optJSONObject(it)?.text("id")?.takeIf(idPattern::matches)
        }.distinct()
        val byId = songs.associateBy { it.id }
        val ordered = (ids.mapNotNull { byId[it] } + songs).distinctBy { it.key }
        // 上游可能忽略 limit/offset，或只返回部分 tracks；保留完整 ID 供分批补齐，数量不足时仍明确展示缺失。
        val declaredCount = playlist.optInt("trackCount", -1).takeIf { it >= 0 }
        val total = if (declaredCount != null || trackIds != null) {
            maxOf(declaredCount ?: 0, ids.size, ordered.size)
        } else null
        require((total ?: ordered.size) <= SongLists.MAX_SONGS) { "单次导入最多 ${SongLists.MAX_SONGS} 首歌曲" }
        // 声明存在但读取不到的曲目 = 总数 - 已解析歌曲数；总数未知时无法判定，按 0 处理。
        val unreadable = if (total != null) (total - ordered.size).coerceAtLeast(0) else 0
        return NeteasePlaylist(playlist.text("name").ifBlank { "网易云歌单 $id" }, ordered, total, ids, unreadable)
    }

    fun details(root: JSONObject): List<Song> {
        if (root.optInt("code") != 200) throw IOException("网易云暂时无法返回歌曲详情")
        val songs = root.optJSONArray("songs") ?: throw IOException("网易云未返回有效歌曲详情")
        return parseSongs(songs)
    }

    private fun parseSongs(tracks: JSONArray): List<Song> {
        require(tracks.length() <= SongLists.MAX_SONGS) { "单次导入最多 ${SongLists.MAX_SONGS} 首歌曲" }
        return (0 until tracks.length()).mapNotNull { index ->
            val track = tracks.optJSONObject(index) ?: return@mapNotNull null
            val songId = track.text("id").takeIf { idPattern.matches(it) } ?: return@mapNotNull null
            val name = track.text("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val artists = track.optJSONArray("ar") ?: track.optJSONArray("artists")
            val album = track.optJSONObject("al") ?: track.optJSONObject("album") ?: JSONObject()
            val picture = album.text("picUrl").toHttpUrlOrNull()?.newBuilder()?.scheme("https")?.build()?.toString()
                ?: album.text("pic_str").ifBlank { album.text("pic") }
            Song(id = songId, name = name,
                artists = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.text("name")?.takeIf(String::isNotBlank) },
                album = album.text("name"), source = "netease", picId = picture)
        }.distinctBy { it.key }
    }

    private fun JSONObject.text(key: String): String = optString(key).takeUnless { it == "null" }.orEmpty().trim()
}
