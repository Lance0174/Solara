package io.github.akudamatata.solara.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

val SOURCES = linkedMapOf("netease" to "网易云音乐", "kuwo" to "酷我音乐", "joox" to "JOOX音乐", "bilibili" to "哔哩哔哩", "local" to "本地音频")
val QUALITIES = linkedMapOf("128" to "标准 · 128K", "192" to "高品 · 192K", "320" to "极高 · 320K", "999" to "无损 · FLAC")
val THEME_STYLES = linkedMapOf("default" to "Solara · 默认", "endfield" to "终末地风格")
val GENRES = listOf("流行", "摇滚", "古典音乐", "民谣", "电子", "爵士", "说唱", "乡村", "蓝调", "R&B", "金属", "嘻哈", "轻音乐")
const val DEFAULT_API = "https://music-api.gdstudio.xyz/api.php"
const val MAX_AUDIO_CACHE_GB = 5

data class Song(
    val id: String,
    val name: String,
    val artists: List<String>,
    val album: String = "",
    val source: String = "netease",
    val picId: String = "",
    val lyricId: String = id,
    val urlId: String = id,
    val localUri: String = "",
) {
    val key: String get() = "$source:$id"
    val artist: String get() = artists.joinToString(" / ").ifBlank { "未知艺术家" }

    fun toJson(includeLocal: Boolean = false): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("artist", JSONArray(artists))
        .put("album", album).put("source", source).put("pic_id", picId)
        .put("lyric_id", lyricId).put("url_id", urlId).apply {
            if (includeLocal && localUri.isNotBlank()) put("local_uri", localUri)
            // 便携导出不携带本机文件地址：source=local 的歌曲 id/歌词/url 字段都是本机 Uri，替换为不含路径的稳定占位。
            if (!includeLocal && source == "local") {
                val placeholder = "local-${(name.hashCode().toLong() and 0x7fffffff)}"
                put("id", placeholder)
                put("lyric_id", placeholder)
                put("url_id", placeholder)
            }
        }

    companion object {
        fun fromJson(json: JSONObject, allowLocal: Boolean = false): Song {
            val id = json.optString("id").takeUnless { it == "null" }.orEmpty()
            require(id.isNotBlank()) { "歌曲缺少 id" }
            val rawArtists = json.opt("artist")
            val artists = if (rawArtists is JSONArray) {
                (0 until rawArtists.length()).map { rawArtists.optString(it) }
            } else listOf(json.optString("artist", ""))
            val source = json.optString("source", "netease").ifBlank { "netease" }
            require(source in SOURCES) { "不支持的音源：$source" }
            return Song(
                id = id, name = json.optString("name", "未命名歌曲"), artists = artists,
                album = json.optString("album", ""), source = source,
                picId = json.optString("pic_id", ""), lyricId = json.optString("lyric_id", id),
                urlId = json.optString("url_id", id),
                // 系统文件选择器可能返回 media 以外的 content:// 文件，均视为本机可播放的本地音频。
                localUri = if (allowLocal) json.optString("local_uri", "").takeIf { it.startsWith("content://") }.orEmpty() else "",
            )
        }

        // 本机音频文件的歌曲：source 固定为 local，id 使用稳定的 Uri 字符串。
        fun local(name: String, artists: List<String> = emptyList(), album: String = "", uri: String): Song =
            Song(id = uri, name = name, artists = artists, album = album, source = "local", localUri = uri)
    }
}

object SongLists {
    const val MAX_SONGS = 10000
    // 与网页的 {meta, items} 导入导出保持兼容，合并时保留既有顺序。
    fun parse(text: String, allowLocal: Boolean = false): List<Song> {
        val root = JSONTokener(text).nextValue()
        val items = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("items") ?: error("文件缺少 items 歌曲列表")
            else -> error("请选择 Solara 导出的 JSON 歌单")
        }
        require(items.length() <= MAX_SONGS) { "单次导入最多 $MAX_SONGS 首歌曲" }
        return (0 until items.length()).map { Song.fromJson(items.getJSONObject(it), allowLocal) }.distinctBy { it.key }
    }

    fun array(songs: List<Song>, includeLocal: Boolean = false): JSONArray =
        JSONArray().apply { songs.forEach { put(it.toJson(includeLocal)) } }

    fun export(songs: List<Song>, favorites: Boolean): String = JSONObject()
        .put("meta", JSONObject().put("app", "Solara").put("version", 1)
            .put("exportedAt", java.time.Instant.now().toString()).put("itemCount", songs.size)
            .put("type", if (favorites) "favorites" else "playlist"))
        .put("items", array(songs)).toString(2)

    fun merge(existing: List<Song>, incoming: List<Song>): List<Song> = (existing + incoming).distinctBy { it.key }

    fun search(songs: List<Song>, query: String): List<Song> {
        if (query.isBlank()) return songs
        val terms = query.trim().split(Regex("\\s+"))
        return songs.filter { song ->
            terms.all { term ->
                song.name.contains(term, ignoreCase = true) || song.album.contains(term, ignoreCase = true) ||
                    song.artists.any { it.contains(term, ignoreCase = true) }
            }
        }
    }
}

data class LyricLine(val timeMs: Long, val text: String, val translation: String = "")

object Lyrics {
    private val timestamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private fun timed(raw: String): List<Pair<Long, String>> {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return raw.lineSequence().flatMap { line ->
            val matches = timestamp.findAll(line).toList()
            val text = line.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: line.length).trim()
            matches.map { match ->
                val (min, sec, fraction) = match.destructured
                val ms = min.toLong() * 60000 + sec.toLong() * 1000 + fraction.padEnd(3, '0').toLong() + offset
                ms.coerceAtLeast(0) to text
            }
        }.sortedBy { it.first }.toList()
    }

    fun parse(original: String, translated: String = ""): List<LyricLine> {
        val translations = timed(translated).toMap()
        val lines = timed(original)
        if (lines.isEmpty()) return original.lines().filter { it.isNotBlank() }.map { LyricLine(0, it) }
        return lines.map { LyricLine(it.first, it.second, translations[it.first].orEmpty()) }
    }
}

data class Settings(
    val site: String = "",
    val api: String = DEFAULT_API,
    val source: String = "netease",
    val quality: String = "320",
    val theme: String = "system",
    val genres: Set<String> = GENRES.toSet(),
    // 播放缓存独立于用户下载文件，默认及最高容量均为 5 GB。
    val cacheLimitGb: Int = MAX_AUDIO_CACHE_GB,
    // 视觉风格独立于明暗模式；旧版本升级与首次安装均保留默认外观。
    val themeStyle: String = "default",
)

data class Library(val queue: List<Song> = emptyList(), val favorites: List<Song> = emptyList())
