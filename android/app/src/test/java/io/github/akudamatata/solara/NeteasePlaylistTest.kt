package io.github.akudamatata.solara

import io.github.akudamatata.solara.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NeteasePlaylistTest {
    private val response = """{"code":200,"playlist":{"id":3778678,"name":"通勤音乐","trackCount":2,
        "trackIds":[{"id":3399839173},{"id":1973665667}],"tracks":[
        {"id":1973665667,"name":"曲目 B","ar":[{"name":"歌手 B"}],"al":{"name":"专辑 B","pic_str":"109951170483249998"}},
        {"id":3399839173,"name":"曲目 A","ar":[{"name":"歌手 A"},{"name":"歌手 C"}],"al":{"name":"专辑 A","picUrl":"http://p1.music.126.net/cover.jpg"}}
    ]}}"""

    @Test fun acceptsDesktopMobileAndPastedShareLinks() {
        listOf("3778678", "https://music.163.com/playlist?id=3778678&userid=12",
            "http://music.163.com/#/playlist?id=3778678",
            "分享歌单《通勤》 https://y.music.163.com/m/playlist?id=3778678&uct2=abc （来自网易云音乐）",
            "https://music.163.com/playlist/3778678/", "https://music.163.com/m/playlist/3778678").forEach {
            val url = NeteasePlaylists.inputUrl(it)
            assertEquals("3778678", NeteasePlaylists.playlistId(url))
            assertTrue(url.isHttps)
        }
        assertEquals("163cn.tv", NeteasePlaylists.inputUrl("分享歌单 https://163cn.tv/example。").host)
    }

    @Test fun rejectsSongsAmbiguousLinksCredentialsAndUntrustedHosts() {
        listOf("", "0", "-123", "abc123", "https://music.163.com/song?id=3778678",
            "https://music.163.com/artist?id=3778678", "https://music.163.com/playlist?id=1&id=2",
            "https://music.163.com.evil.example/playlist?id=1", "https://music.163.com@evil.example/playlist?id=1",
            "https://user:pass@music.163.com/playlist?id=1", "https://music.163.com:8080/playlist?id=1",
            "http://127.0.0.1/playlist?id=1", "intent://playlist?id=1",
            "https://music.163.com/playlist?id=1 https://music.163.com/playlist?id=2").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { NeteasePlaylists.inputUrl(it) }
        }
    }

    @Test fun mapsMetadataAndKeepsDeclaredOrderWithoutLosingLargeIds() {
        val playlist = NeteasePlaylists.parse(JSONObject(response), "3778678")
        assertEquals("通勤音乐", playlist.name)
        assertEquals(listOf("3399839173", "1973665667"), playlist.songs.map { it.id })
        assertEquals(listOf("歌手 A", "歌手 C"), playlist.songs.first().artists)
        assertEquals("专辑 A", playlist.songs.first().album)
        assertEquals("https://p1.music.126.net/cover.jpg", playlist.songs.first().picId)
        assertEquals("109951170483249998", playlist.songs.last().picId)
        assertTrue(playlist.songs.all { it.source == "netease" && it.lyricId == it.id && it.localUri.isBlank() })
        assertFalse(playlist.partial)
    }

    @Test fun partialAndUnreadableTracksRemainVisibleInCounts() {
        val root = JSONObject(response)
        root.getJSONObject("playlist").put("trackCount", 200)
        root.getJSONObject("playlist").getJSONArray("tracks").getJSONObject(0).remove("name")
        val playlist = NeteasePlaylists.parse(root, "3778678")
        assertEquals(200, playlist.totalCount)
        assertEquals(1, playlist.songs.size)
        assertEquals(199, playlist.unreadableCount)
        assertTrue(playlist.partial)
    }

    @Test fun unreadableCountRecountsAfterDetailFillAndStaysZeroWhenComplete() {
        // 声明 3 首、已读 2 首 → 1 首不可读取；补齐到与总数一致后归零。
        val partiallyFilled = NeteasePlaylist("测试", listOf(
            Song("1", "曲目一", listOf("歌手一")), Song("2", "曲目二", listOf("歌手二"))),
            totalCount = 3, trackIds = listOf("1", "2", "3"), unreadableCount = 1)
        assertEquals(1, partiallyFilled.unreadableCount)
        assertTrue(partiallyFilled.partial)
        val completed = partiallyFilled.add(listOf(Song("3", "曲目三", listOf("歌手三"))))
        assertEquals(0, completed.unreadableCount)
        assertFalse(completed.partial)
        // 完全读取时 parse 得到的不可读取数量为 0。
        val complete = NeteasePlaylists.parse(JSONObject(response), "3778678")
        assertEquals(0, complete.unreadableCount)
        assertEquals(2, complete.songs.size)
    }

    @Test fun unknownTotalsAreNotPresentedAsCompleteAndDuplicatesAreRemoved() {
        val root = JSONObject(response)
        val data = root.getJSONObject("playlist")
        data.put("trackCount", JSONObject.NULL).remove("trackIds")
        data.getJSONArray("tracks").put(data.getJSONArray("tracks").getJSONObject(0))
        val playlist = NeteasePlaylists.parse(root, "3778678")
        assertNull(playlist.totalCount)
        assertEquals(2, playlist.songs.size)
    }

    @Test fun distinguishesEmptyPlaylistsFromPrivateMissingAndMalformedResponses() {
        val root = JSONObject(response)
        root.getJSONObject("playlist").put("tracks", JSONArray()).put("trackIds", JSONArray()).put("trackCount", 0)
        assertTrue(NeteasePlaylists.parse(root, "3778678").songs.isEmpty())
        assertThrows(IOException::class.java) { NeteasePlaylists.parse(JSONObject("""{"code":404}"""), "3778678") }
        assertThrows(IOException::class.java) { NeteasePlaylists.parse(root, "999") }
        root.getJSONObject("playlist").put("privacy", 10)
        assertThrows(IOException::class.java) { NeteasePlaylists.parse(root, "3778678") }
        root.getJSONObject("playlist").put("privacy", 0).remove("tracks")
        assertThrows(IOException::class.java) { NeteasePlaylists.parse(root, "3778678") }
    }

    @Test fun requestsPlaylistOnceThroughConfiguredSiteWithoutFakePagination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            val api = MusicApi(OkHttpClient()) { Settings(site = server.url("/").toString(), source = "kuwo") }
            assertEquals(2, api.neteasePlaylist("3778678").songs.size)
            val url = server.takeRequest().requestUrl!!
            assertEquals("/proxy", url.encodedPath)
            assertEquals("playlist", url.queryParameter("types"))
            assertEquals("3778678", url.queryParameter("id"))
            assertEquals("netease", url.queryParameter("source"))
            assertNull(url.queryParameter("offset"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun resolvesShortLinksInternallyBeforeRequestingMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            var redirects = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                if (chain.request().url.host == "163cn.tv") {
                    redirects++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                        .header("Location", "http://music.163.com/#/playlist?id=3778678").body("".toResponseBody()).build()
                } else chain.proceed(chain.request())
            }.build()
            val api = MusicApi(client) { Settings(api = server.url("/api.php").toString()) }
            assertEquals(2, api.neteasePlaylist("分享歌单 https://163cn.tv/example").songs.size)
            assertEquals(1, redirects)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun rejectsExternalRedirectsAndBoundsShortLinkLoops() {
        listOf("https://example.com/playlist?id=3778678", "https://163cn.tv/loop").forEach { location ->
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", location).body("".toResponseBody()).build()
            }.build()
            val api = MusicApi(client) { Settings() }
            assertThrows(Exception::class.java) { runBlocking { api.neteasePlaylist("https://163cn.tv/example") } }
            assertEquals(if (location.contains("loop")) 5 else 1, requests)
        }
    }

    @Test fun httpFailuresAndHtmlDoNotBecomeEmptySuccessfulImports() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("<html>Login</html>"))
            val api = MusicApi(OkHttpClient()) { Settings(api = server.url("/api.php").toString()) }
            repeat(2) { assertThrows(IOException::class.java) { runBlocking { api.neteasePlaylist("3778678") } } }
        }
    }

    @Test fun cancellingReadCancelsTheHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response).setBodyDelay(3, TimeUnit.SECONDS))
            val failed = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
            }).build()
            val api = MusicApi(client) { Settings(api = server.url("/api.php").toString()) }
            val task = async(Dispatchers.IO) { api.neteasePlaylist("3778678") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            task.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { failed.await(2, TimeUnit.SECONDS) })
            assertTrue(task.isCancelled)
        }
    }

    @Test fun fills3165SongsInBatchesAndPreservesOrderWithoutSendingSessionCookies() = runBlocking {
        MockWebServer().use { server ->
            val batches = mutableListOf<List<String>>()
            val progress = mutableListOf<Pair<Int, Int?>>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl!!.queryParameter("types") == "playlist") {
                        assertTrue(request.getHeader("Cookie").orEmpty().contains("session=local-test"))
                        return MockResponse().setBody(largePlaylist(total = 3165, loaded = 1000).toString())
                    }
                    assertEquals("/api/song/detail", request.requestUrl!!.encodedPath)
                    assertNull(request.getHeader("Cookie"))
                    assertEquals("https://music.163.com/", request.getHeader("Referer"))
                    val ids = JSONArray(request.requestUrl!!.queryParameter("ids"))
                    val batch = (0 until ids.length()).map { ids.optString(it) }
                    batches.add(batch)
                    return MockResponse().setBody(JSONObject().put("code", 200).put("songs",
                        JSONArray().apply { batch.reversed().forEach { put(detail(it)) } }).toString())
                }
            }
            val client = detailClient(server).newBuilder().cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder()
                    .name("session").value("local-test").hostOnlyDomain(url.host).build())
            }).build()
            val api = MusicApi(client) { Settings(api = server.url("/api.php").toString()) }
            val result = api.neteasePlaylist("3778678") { loaded, total -> progress.add(loaded to total) }
            val orderedIds = (1..3165).map { trackId(it) }
            assertEquals(orderedIds, result.songs.map { it.id })
            assertEquals(orderedIds.drop(1000), batches.flatten())
            assertEquals(List(10) { 200 } + 165, batches.map { it.size })
            assertEquals(12, server.requestCount)
            assertEquals(1000 to 3165, progress.first())
            assertEquals(3165 to 3165, progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> a.first <= b.first })
            assertFalse(result.partial)
            assertEquals(listOf("歌手甲", "歌手乙"), result.songs.last().artists)
            assertEquals("专辑", result.songs.last().album)
            assertTrue(result.songs.all { it.localUri.isBlank() && it.source == "netease" && it.lyricId == it.id })
        }
    }

    @Test fun unavailableSongsRemainPartialAndUnrequestedDetailsAreIgnored() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(largePlaylist(total = 4, loaded = 1).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("code", 200).put("songs", JSONArray()
                .put(detail(trackId(3))).put(detail(trackId(3))).put(detail("999")).put(detail(trackId(2)))).toString()))
            val api = MusicApi(detailClient(server)) { Settings(api = server.url("/api.php").toString()) }
            val result = api.neteasePlaylist("3778678")
            assertEquals((1..3).map { trackId(it) }, result.songs.map { it.id })
            assertEquals(4, result.totalCount)
            assertTrue(result.partial)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedLaterBatchReportsActualProgressInsteadOfReturningSuccessfulPartialImport() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(largePlaylist(total = 401, loaded = 1).toString()))
            server.enqueue(MockResponse().setBody(JSONObject().put("code", 200).put("songs", JSONArray().apply {
                (2..201).forEach { put(detail(trackId(it))) }
            }).toString()))
            server.enqueue(MockResponse().setResponseCode(503))
            val api = MusicApi(detailClient(server)) { Settings(api = server.url("/api.php").toString()) }
            val error = assertThrows(IOException::class.java) { runBlocking { api.neteasePlaylist("3778678") } }
            assertTrue(error.message.orEmpty().contains("201/401"))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun cancellingDetailBatchStopsItsCallAndAllRemainingBatches() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(largePlaylist(total = 401, loaded = 1).toString()))
            server.enqueue(MockResponse().setBody("""{"code":200,"songs":[]}""").setBodyDelay(3, TimeUnit.SECONDS))
            val cancelled = CountDownLatch(1)
            val client = detailClient(server).newBuilder().eventListener(object : EventListener() {
                override fun callFailed(call: Call, ioe: IOException) { cancelled.countDown() }
            }).build()
            val api = MusicApi(client) { Settings(api = server.url("/api.php").toString()) }
            val task = async(Dispatchers.IO) { api.neteasePlaylist("3778678") }
            repeat(2) { assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }) }
            task.cancelAndJoin()
            assertTrue(withContext(Dispatchers.IO) { cancelled.await(2, TimeUnit.SECONDS) })
            assertEquals(2, server.requestCount)
            assertTrue(task.isCancelled)
        }
    }

    @Test fun rejectsOversizedPlaylistsBeforeFetchingMoreDetails() {
        MockWebServer().use { server ->
            val root = largePlaylist(total = 1, loaded = 1)
            root.getJSONObject("playlist").put("trackCount", SongLists.MAX_SONGS + 1)
            server.enqueue(MockResponse().setBody(root.toString()))
            val api = MusicApi(detailClient(server)) { Settings(api = server.url("/api.php").toString()) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { api.neteasePlaylist("3778678") } }
            assertEquals(1, server.requestCount)
        }
    }

    private fun trackId(index: Int) = (3000000000L + index).toString()

    private fun detail(id: String): JSONObject = JSONObject().put("id", id).put("name", "曲目 $id")
        .put("artists", JSONArray().put(JSONObject().put("name", "歌手甲")).put(JSONObject().put("name", "歌手乙")))
        .put("album", JSONObject().put("name", "专辑").put("picUrl", "http://p1.music.126.net/cover.jpg"))
        .put("local_uri", "content://media/external/audio/media/1")

    private fun largePlaylist(total: Int, loaded: Int): JSONObject = JSONObject().put("code", 200).put("playlist",
        JSONObject().put("id", 3778678).put("name", "大歌单").put("trackCount", total)
            .put("trackIds", JSONArray().apply { (1..total).forEach { put(JSONObject().put("id", trackId(it))) } })
            .put("tracks", JSONArray().apply { (1..loaded).forEach { put(detail(trackId(it))) } }))

    private fun detailClient(server: MockWebServer): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        if (request.url.host == "music.163.com") {
            val url = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
            chain.proceed(request.newBuilder().url(url).build())
        } else chain.proceed(request)
    }.build()
}
