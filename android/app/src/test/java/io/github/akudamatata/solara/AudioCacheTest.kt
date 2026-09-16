package io.github.akudamatata.solara

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.akudamatata.solara.data.MusicApi
import io.github.akudamatata.solara.data.Settings
import io.github.akudamatata.solara.data.Song
import io.github.akudamatata.solara.playback.AudioCache
import io.github.akudamatata.solara.playback.mediaItem
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class AudioCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = RuntimeEnvironment.getApplication()
    private val song = Song("42", "缓存歌曲", listOf("歌手"))
    private val payload = ByteArray(4096) { (it % 251).toByte() }
    private val time = AtomicLong(1000)
    private var limit = AudioCache.GB
    private lateinit var directory: File
    private lateinit var cache: AudioCache

    @Before fun setup() {
        directory = temporary.newFolder("audio-cache")
        cache = AudioCache(context, { limit }, directory, time::get)
    }

    @After fun tearDown() { cache.release() }

    private fun spec(song: Song = this.song): DataSpec = DataSpec.Builder().setUri(song.mediaItem().localConfiguration!!.uri).build()
    private fun online(): DataSource.Factory = DataSource.Factory { ByteArrayDataSource(payload) }
    private fun offline(): DataSource.Factory = ResolvingDataSource.Factory(online()) { throw IOException("无网络") }
    private fun factory(upstream: DataSource.Factory = online(), quality: String = "320") = cache.dataSourceFactory(upstream) { quality }

    private fun read(factory: DataSource.Factory, spec: DataSpec = spec()): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(spec)
            return remaining(source)
        } finally { source.close() }
    }

    private fun remaining(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, count)
        }
    }

    @Test fun completeAudioSurvivesRestartBackend502AndDisconnectedNetwork() {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().connectTimeout(300, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build()
        try {
            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            val stream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(client)) { it.withUri(Uri.parse(server.url("/audio").toString())) }
            assertArrayEquals(payload, read(factory(stream)))
            cache.release()
            cache = AudioCache(context, { limit }, directory, time::get)
            val api = MusicApi(client) { Settings(api = server.url("/api").toString()) }
            val resolving = ResolvingDataSource.Factory(online()) {
                it.withUri(Uri.parse(api.resolve(song, "320").url.toString()))
            }
            server.enqueue(MockResponse().setResponseCode(502))
            assertArrayEquals(payload, read(factory(resolving)))
            assertEquals(1, server.requestCount)
            val failure = assertThrows(IOException::class.java) { read(factory(resolving), spec(song.copy(id = "new"))) }
            assertTrue(failure.message.orEmpty().contains("502"))
            assertEquals(2, server.requestCount)
            server.shutdown()
            assertArrayEquals(payload, read(factory(resolving)))
            assertEquals(payload.size.toLong(), cache.bytes.value)
        } finally { server.close() }
    }

    @Test fun partialCacheDoesNotPretendTheUnfetchedRemainderIsAvailableOffline() {
        val source = factory().createDataSource()
        try {
            source.open(spec())
            assertEquals(32, source.read(ByteArray(32), 0, 32))
        } finally { source.close() }
        assertEquals(32L, cache.bytes.value)
        assertThrows(IOException::class.java) { read(factory(offline())) }
    }

    @Test fun stableKeysIgnoreMetadataChangesButSeparateSourcesAndQualities() {
        read(factory())
        assertArrayEquals(payload, read(factory(offline()), spec(song.copy(name = "更新歌名"))))
        assertThrows(IOException::class.java) { read(factory(offline(), "999")) }
        assertThrows(IOException::class.java) { read(factory(offline()), spec(song.copy(source = "kuwo"))) }
    }

    @Test fun sevenDayExpirySurvivesRestartAndDoesNotResetWhenRead() {
        read(factory())
        time.addAndGet(TimeUnit.DAYS.toMillis(6))
        assertArrayEquals(payload, read(factory(offline())))
        cache.release()
        cache = AudioCache(context, { limit }, directory, time::get)
        time.addAndGet(TimeUnit.DAYS.toMillis(1))
        assertThrows(IOException::class.java) { read(factory(offline())) }
        assertEquals(0L, cache.bytes.value)
    }

    @Test fun reachingAndReducingCapacityAutomaticallyReclaimsCachedAudio() {
        limit = 8192
        read(factory(), spec(song.copy(id = "one")))
        read(factory(), spec(song.copy(id = "two")))
        assertEquals(8192L, cache.bytes.value)
        read(factory(), spec(song.copy(id = "three")))
        assertTrue(cache.bytes.value!! <= limit)
        assertArrayEquals(payload, read(factory(offline()), spec(song.copy(id = "three"))))
        limit = 4096
        cache.clean()
        assertTrue(cache.bytes.value!! <= limit)
        limit = 1024
        cache.clean()
        assertEquals(0L, cache.bytes.value)
    }

    @Test fun manualClearDefersActiveReadersAndPreservesUserFiles() {
        val downloaded = temporary.newFile("user-download.mp3").apply { writeBytes(payload) }
        read(factory())
        read(factory(), spec(song.copy(id = "another")))
        val source = factory(offline()).createDataSource()
        try {
            source.open(spec())
            assertTrue(cache.clear())
            assertEquals(payload.size.toLong(), cache.bytes.value)
            assertArrayEquals(payload, remaining(source))
        } finally { source.close() }
        assertEquals(0L, cache.bytes.value)
        assertArrayEquals(payload, downloaded.readBytes())
        assertArrayEquals(payload, read(factory(offline()), DataSpec(Uri.fromFile(downloaded))))
        assertEquals(0L, cache.bytes.value)
    }

    @Test fun httpErrorsAreNotSavedAsCachedAudio() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(502).setBody("Bad Gateway"))
            val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(OkHttpClient())) {
                it.withUri(Uri.parse(server.url("/audio").toString()))
            }
            assertThrows(IOException::class.java) { read(factory(upstream)) }
            assertEquals(0L, cache.bytes.value)
        }
    }
}
