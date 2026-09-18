package io.github.akudamatata.solara.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// 签名直链通常在 1–2 小时后过期，15 分钟的缓存窗口与网页保持一致，足够覆盖一次连续播放。
private const val AUDIO_URL_TTL_MS = 15 * 60 * 1000L

class MusicApi(val client: OkHttpClient, private val settings: () -> Settings) {
    private val neteasePublic by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
    private val covers = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    fun endpoint(): HttpUrl = settings().let {
        if (it.site.isBlank()) it.api.toHttpUrl() else (it.site.trimEnd('/') + "/proxy").toHttpUrl()
    }

    fun requestUrl(type: String, params: Map<String, String>): HttpUrl = endpoint().newBuilder()
        .addQueryParameter("types", type).apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()

    private fun json(request: Request): Any = client.newCall(request).execute().use(::jsonResponse)

    private fun jsonResponse(response: Response): Any {
        // /api/login 自身的成功响应会落在 /login 结尾的路径上，但那不是未登录，必须排除。
        val path = response.request.url.encodedPath.trimEnd('/')
        val isLoginCall = path.endsWith("/api/login")
        // 站点代理在未携带有效 auth cookie 时会把请求重定向到 /login 登录页；OkHttp 跟随重定向后的最终页面即为登录页。
        if (!isLoginCall && (path.endsWith("/login") || response.code in setOf(302, 303)
                && response.header("Location").orEmpty().contains("/login"))) throw IOException("请在设置中登录 Solara 站点")
        if (!isLoginCall && response.code == 401) throw IOException("请在设置中登录 Solara 站点")
        // 登录接口自身的失败（口令错误等）返回 401 + JSON，交给 login() 解析成清晰的错误提示。
        if (!isLoginCall && !response.isSuccessful) throw IOException("服务返回 HTTP ${response.code}，请稍后重试或切换音源")
        val text = response.body?.string().orEmpty().trim()
        if (text.startsWith("<")) {
            if (isLoginCall) throw IOException("登录接口返回了网页，请检查站点地址或口令")
            throw IOException("接口返回了网页，请检查站点地址、接口可用性，或重新登录 Solara 站点")
        }
        val result = try { JSONTokener(text).nextValue() } catch (_: Exception) {
            throw IOException("接口未返回有效音乐数据")
        }
        if (result is JSONObject && (result.has("error") || result.optInt("status", 1) == 0)) {
            throw IOException("音乐接口暂时不可用，请重试或切换音源")
        }
        return result
    }

    private fun get(type: String, params: Map<String, String>): Any = json(Request.Builder()
        .url(requestUrl(type, params)).header("Accept", "application/json").build())

    fun search(keyword: String, source: String, page: Int = 1): List<Song> {
        val result = get("search", mapOf("source" to source, "name" to keyword, "count" to "20", "pages" to page.toString()))
        if (result !is JSONArray) throw IOException("搜索结果格式不正确，请切换音源后重试")
        return (0 until result.length()).map { index ->
            val song = result.getJSONObject(index)
            if (song.optString("source").isBlank()) song.put("source", source)
            Song.fromJson(song)
        }.distinctBy { it.key }
    }

    suspend fun neteasePlaylist(input: String, onProgress: suspend (Int, Int?) -> Unit = { _, _ -> }): NeteasePlaylist {
        var url = NeteasePlaylists.inputUrl(input)
        // 短链只在后台解析受信任的网易云跳转，不带 Solara 会话，也不打开网页。
        for (hop in 0..5) {
            NeteasePlaylists.playlistId(url)?.let { id ->
                val request = Request.Builder().url(requestUrl("playlist", mapOf("id" to id, "source" to "netease")))
                    .header("Accept", "application/json").build()
                val root = playlistRequest(request, client, ::jsonResponse) as? JSONObject
                    ?: throw IOException("接口没有返回有效的网易云歌单")
                var playlist = NeteasePlaylists.parse(root, id)
                onProgress(playlist.songs.size, playlist.totalCount)
                val existing = playlist.songs.mapTo(HashSet()) { it.id }
                val missing = playlist.trackIds.filter { it !in existing }
                // 详情接口每批读取 200 首，避免歌单接口的前 1000 首截断；只补缺失项并保持原顺序。
                for (batch in missing.chunked(200)) {
                    currentCoroutineContext().ensureActive()
                    val detailUrl = "https://music.163.com/api/song/detail".toHttpUrl().newBuilder()
                        .addQueryParameter("ids", batch.joinToString(separator = ",", prefix = "[", postfix = "]")).build()
                    val detailRequest = Request.Builder().url(detailUrl).header("Referer", "https://music.163.com/")
                        .header("Accept", "application/json").build()
                    try {
                        val details = playlistRequest(detailRequest, neteasePublic, ::jsonResponse) as? JSONObject
                            ?: throw IOException("网易云未返回有效歌曲详情")
                        val requested = batch.toHashSet()
                        playlist = playlist.add(NeteasePlaylists.details(details).filter { it.id in requested })
                    } catch (error: IOException) {
                        throw IOException("补齐歌单失败（已读取 ${playlist.songs.size}/${playlist.totalCount ?: playlist.trackIds.size} 首），请重试", error)
                    }
                    onProgress(playlist.songs.size, playlist.totalCount)
                }
                return playlist
            }
            if (hop == 5) break
            val next = playlistRequest(Request.Builder().url(url).build(), neteasePublic) { response ->
                if (response.code !in setOf(301, 302, 303, 307, 308)) {
                    throw IOException("无法解析分享短链，请改用完整歌单链接或歌单 ID")
                }
                response.header("Location") ?: throw IOException("分享短链已失效，请重新复制歌单链接")
            }
            url = NeteasePlaylists.checkedUrl(url.resolve(next))
        }
        throw IOException("分享链接跳转过多，请改用完整歌单链接或歌单 ID")
    }

    private suspend fun <T> playlistRequest(request: Request, httpClient: OkHttpClient, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("读取歌单失败，请检查网络后重试", error))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use(read)
                        continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }

    data class Audio(val url: HttpUrl, val bitrate: Int)

    private data class ResolvedAudio(val url: String, val bitrate: Int, val resolvedAt: Long)

    // 与网页一致的直链短期缓存：拖动/切缓存分段触发的重复解析不再请求接口，15 分钟后过期重新解析。
    private val resolvedAudios = HashMap<String, ResolvedAudio>()
    private val probeClient by lazy {
        client.newBuilder().cookieJar(CookieJar.NO_COOKIES).callTimeout(8, TimeUnit.SECONDS).build()
    }

    // 播放前的轻量探测：只取 2 字节确认直链可用，等价网页在播放前先加载音频元数据再选定地址。
    private fun reachable(url: HttpUrl): Boolean = try {
        probeClient.newCall(Request.Builder().url(url).header("Range", "bytes=0-1").build()).execute().use { response ->
            response.isSuccessful
        }
    } catch (_: Exception) { false }

    fun resolve(song: Song, quality: String, probe: (HttpUrl) -> Boolean = { url -> reachable(url) }): Audio {
        val cacheKey = "${endpoint()}|${song.source}:${song.id}:$quality"
        synchronized(resolvedAudios) {
            resolvedAudios[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.resolvedAt < AUDIO_URL_TTL_MS) return Audio(cached.url.toHttpUrl(), cached.bitrate)
                resolvedAudios.remove(cacheKey)
            }
        }
        val result = get("url", mapOf("id" to song.id, "source" to song.source, "br" to quality)) as? JSONObject
            ?: throw IOException("无法解析音频地址")
        val url = result.optString("url").toHttpUrlOrNull()
            ?: throw IOException("这首歌暂时没有可用音频，请切换音质或音源")
        // 网页会依次尝试 HTTPS 化与原始地址；这里同样优先 HTTPS，探测失败再回退上游原始地址。
        // 酷我保留上游要求的 HTTP，不升级。
        val candidates = when {
            url.isHttps -> listOf(url)
            url.host == "kuwo.cn" || url.host.endsWith(".kuwo.cn") -> listOf(url)
            else -> listOf(url.newBuilder().scheme("https").build(), url)
        }
        val streamUrl = candidates.firstOrNull(probe) ?: candidates.last()
        val audio = Audio(streamUrl, result.optInt("br", 0))
        synchronized(resolvedAudios) { resolvedAudios[cacheKey] = ResolvedAudio(streamUrl.toString(), audio.bitrate, System.currentTimeMillis()) }
        return audio
    }

    // 播放失败自动重试时调用：作废直链缓存，强制重新解析（等价网页重试的 nocache=true）。
    fun forgetResolvedAudio(song: Song) {
        synchronized(resolvedAudios) {
            val prefix = "${song.source}:${song.id}:"
            resolvedAudios.keys.filter { it.substringAfterLast('|').startsWith(prefix) }.forEach { resolvedAudios.remove(it) }
        }
    }

    fun cover(song: Song): String {
        if (song.picId.isBlank()) return ""
        song.picId.toHttpUrlOrNull()?.let { return it.toString() }
        val cacheKey = "${endpoint()}|${song.source}:${song.picId}"
        synchronized(covers) { covers[cacheKey]?.let { return it } }
        val result = get("pic", mapOf("id" to song.picId, "source" to song.source, "size" to "500")) as? JSONObject
        val url = result?.optString("url")?.toHttpUrlOrNull()?.toString().orEmpty()
        if (url.isNotBlank()) synchronized(covers) { covers[cacheKey] = url }
        return url
    }

    fun lyrics(song: Song): List<LyricLine> {
        val result = get("lyric", mapOf("id" to song.lyricId.ifBlank { song.id }, "source" to song.source)) as? JSONObject
            ?: throw IOException("歌词数据格式不正确")
        return Lyrics.parse(result.optString("lyric"), result.optString("tlyric"))
    }

    fun login(site: String, password: String) {
        val result = json(Request.Builder().url(site.trimEnd('/') + "/api/login")
            .post(JSONObject().put("password", password).toString().toRequestBody("application/json".toMediaType())).build()) as? JSONObject
        if (result?.optBoolean("success") != true) throw IOException("登录失败，请检查站点和口令")
    }

    fun readRemote(): Library {
        val site = settings().site
        require(site.isNotBlank()) { "请先填写 Solara 站点" }
        val url = (site.trimEnd('/') + "/api/storage").toHttpUrl().newBuilder()
            .addQueryParameter("keys", "playlistSongs,favoriteSongs").build()
        val result = json(Request.Builder().url(url).build()) as? JSONObject
        if (result?.optBoolean("d1Available") != true) throw IOException("此站点尚未启用歌单存储")
        val data = result.optJSONObject("data") ?: JSONObject()
        return Library(SongLists.parse(data.optString("playlistSongs", "[]")), SongLists.parse(data.optString("favoriteSongs", "[]")))
    }

    fun writeRemote(library: Library) {
        val data = JSONObject().put("playlistSongs", SongLists.array(library.queue).toString())
            .put("favoriteSongs", SongLists.array(library.favorites).toString())
        json(Request.Builder().url(settings().site.trimEnd('/') + "/api/storage")
            .post(JSONObject().put("data", data).toString().toRequestBody("application/json".toMediaType())).build())
    }
}
