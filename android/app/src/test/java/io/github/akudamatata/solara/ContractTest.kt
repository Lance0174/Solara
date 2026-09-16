package io.github.akudamatata.solara

import io.github.akudamatata.solara.data.*
import io.github.akudamatata.solara.download.AudioTransfer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ContractTest {
    private val song = Song("42", "测试 / 曲目", listOf("作者 A", "作者 B"), source = "netease", picId = "pic42", lyricId = "lrc42")

    @Test fun webPlaylistRoundTripPreservesContract() {
        val text = SongLists.export(listOf(song), favorites = true)
        val json = JSONObject(text)
        assertEquals("Solara", json.getJSONObject("meta").getString("app"))
        assertEquals("favorites", json.getJSONObject("meta").getString("type"))
        assertEquals(listOf(song), SongLists.parse(text))
    }

    @Test fun importedNumbersAndArtistStringsMatchWebKeys() {
        val parsed = SongLists.parse("""[{"id":42,"name":"Track","artist":"A / B","source":"kuwo"}]""")
        assertEquals("kuwo:42", parsed.single().key)
        assertEquals("A / B", parsed.single().artist)
    }

    @Test fun mergingKeepsExistingOrderAndDifferentSources() {
        assertEquals(listOf(song, song.copy(source = "kuwo")), SongLists.merge(listOf(song), listOf(song, song.copy(source = "kuwo"))))
    }

    @Test fun importedPlaylistsCannotInjectLocalMediaUris() {
        val local = song.copy(localUri = "content://media/external/audio/media/7")
        val raw = SongLists.array(listOf(local), includeLocal = true).toString()
        assertEquals("", SongLists.parse(raw).single().localUri)
        assertEquals(local, SongLists.parse(raw, allowLocal = true).single())
    }

    @Test fun lyricsSupportRepeatedTimesTranslationAndOffset() {
        val parsed = Lyrics.parse("[offset:-100]\n[00:01.25][00:02.500]Hello", "[00:01.150]你好")
        assertEquals(listOf(1150L, 2400L), parsed.map { it.timeMs })
        assertEquals("你好", parsed.first().translation)
    }

    @Test fun untimedLyricsRemainReadable() {
        assertEquals(listOf("纯音乐", "请欣赏"), Lyrics.parse("纯音乐\n请欣赏").map { it.text })
    }

    @Test fun downloadDetectsActualFormatInsteadOfRequestedQuality() {
        assertEquals("flac", AudioTransfer.identify("fLaC1234".toByteArray(), "application/octet-stream").extension)
        assertEquals("mp3", AudioTransfer.identify("ID3mp3body".toByteArray(), "audio/mpeg").extension)
        assertEquals("m4a", AudioTransfer.identify(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray(), "audio/mp4").extension)
    }

    @Test fun downloadRejectsHtmlEvenWhenServerCallsItAudio() {
        assertThrows(IOException::class.java) { AudioTransfer.identify("<!doctype html><title>Login".toByteArray(), "audio/mpeg") }
        assertThrows(IOException::class.java) { AudioTransfer.identify("{\"error\":true}".toByteArray(), "application/json") }
    }

    @Test fun downloadRejectsTruncatedResponses() {
        assertThrows(IOException::class.java) { AudioTransfer.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 50) { } }
    }

    @Test fun unknownLengthStreamsReportRealBytes() {
        val bytes = ByteArray(150000) { (it % 255).toByte() }
        val output = ByteArrayOutputStream()
        var last = 0L
        assertEquals(bytes.size.toLong(), AudioTransfer.copy(ByteArrayInputStream(bytes), output, -1) { last = it })
        assertEquals(bytes.size.toLong(), last)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun transferPropagatesCancellationBeforeWritingMore() {
        val output = ByteArrayOutputStream()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            AudioTransfer.copy(ByteArrayInputStream(ByteArray(150000)), output, -1) { copied ->
                if (copied > 0) throw java.util.concurrent.CancellationException()
            }
        }
        assertTrue(output.size() < 150000)
    }

    @Test fun filenamesCannotEscapeMusicDirectory() {
        val result = AudioTransfer.filename("../name:*?", "artist/\\test", "mp3")
        assertFalse(result.contains('/'))
        assertFalse(result.contains('\\'))
        assertTrue(result.endsWith(".mp3"))
    }

    @Test fun searchUsesExactUpstreamQueryContract() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(SongLists.array(listOf(song)).toString()))
            val api = MusicApi(OkHttpClient()) { Settings(api = server.url("/api.php").toString()) }
            assertEquals(listOf(song), api.search("A & B 中文", "netease", 2))
            val query = server.takeRequest().requestUrl!!
            assertEquals("A & B 中文", query.queryParameter("name"))
            assertEquals("2", query.queryParameter("pages"))
            assertEquals("20", query.queryParameter("count"))
        }
    }

    @Test fun signedAudioUrlsAreResolvedFreshForEveryRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"url":"https://example.com/audio.mp3?sign=one","br":128}"""))
            server.enqueue(MockResponse().setBody("""{"url":"https://example.com/audio.mp3?sign=two","br":320}"""))
            val api = MusicApi(OkHttpClient()) { Settings(api = server.url("/api.php").toString()) }
            assertEquals(128, api.resolve(song, "128").bitrate)
            assertEquals("two", api.resolve(song, "320").url.queryParameter("sign"))
            assertEquals("128", server.takeRequest().requestUrl!!.queryParameter("br"))
            assertEquals("320", server.takeRequest().requestUrl!!.queryParameter("br"))
        }
    }

    @Test fun apiReturnsLoginErrorWithoutOpeningBrowser() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login"))
            server.enqueue(MockResponse().setBody("<html>Login</html>"))
            val api = MusicApi(OkHttpClient()) { Settings(api = server.url("/api.php").toString()) }
            val error = assertThrows(IOException::class.java) { api.search("anything", "netease") }
            assertTrue(error.message!!.contains("登录"))
        }
    }

    @Test fun loginEndpointSuccessIsNotMisreadAsNeedingLogin() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true}"""))
            val api = MusicApi(OkHttpClient()) { Settings(site = server.url("/").toString()) }
            api.login(server.url("/").toString(), "secret")
            assertEquals("/api/login", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun loginEndpointWrongPasswordSurfacesClearError() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false}"""))
            val api = MusicApi(OkHttpClient()) { Settings(site = server.url("/").toString()) }
            val error = assertThrows(IOException::class.java) { api.login(server.url("/").toString(), "wrong") }
            assertTrue(error.message!!.contains("登录失败"))
        }
    }

    @Test fun musicApiRejectsNonHttpAudioSchemes() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"url":"intent://browser","br":320}"""))
            val api = MusicApi(OkHttpClient()) { Settings(api = server.url("/api.php").toString()) }
            assertThrows(IOException::class.java) { api.resolve(song, "320") }
        }
    }

    @Test fun onlyKuwoAudioKeepsRequiredCleartextTransport() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"url":"http://m.music.126.net/a.mp3","br":320}"""))
            server.enqueue(MockResponse().setBody("""{"url":"http://cdn.kuwo.cn/a.mp3","br":320}"""))
            val api = MusicApi(OkHttpClient()) { Settings(api = server.url("/api.php").toString()) }
            assertTrue(api.resolve(song, "320").url.isHttps)
            assertFalse(api.resolve(song.copy(source = "kuwo"), "320").url.isHttps)
        }
    }
}
