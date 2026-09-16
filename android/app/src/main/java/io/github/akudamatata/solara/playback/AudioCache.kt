package io.github.akudamatata.solara.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import io.github.akudamatata.solara.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.TreeSet
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class AudioCache(
    private val context: Context,
    private val limitBytes: () -> Long,
    directory: File = File(context.filesDir, "playback-cache"),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableBytes = MutableStateFlow<Long?>(null)
    val bytes = mutableBytes.asStateFlow()
    private val evictor = Evictor()
    private val cache by lazy { SimpleCache(directory, evictor, StandaloneDatabaseProvider(context)) }

    // 在地址解析之前查缓存；固定一次加载的音质，避免拖动时混读不同码率的字节。
    fun dataSourceFactory(upstream: DataSource.Factory, quality: () -> String): DataSource.Factory = DataSource.Factory {
        val selectedQuality = quality()
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(FRAGMENT_BYTES))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val source = DefaultDataSource(context, cached.createDataSource())
        object : DataSource by source {
            private var activeKey: String? = null

            override fun open(dataSpec: DataSpec): Long {
                if (dataSpec.uri.scheme != "solara") return source.open(dataSpec)
                val song = Song.fromJson(JSONObject(requireNotNull(dataSpec.uri.getQueryParameter("data"))))
                val key = "audio-v1:${song.key}:$selectedQuality"
                synchronized(cache) {
                    evictor.clean(cache)
                    evictor.inUse[key] = (evictor.inUse[key] ?: 0) + 1
                    activeKey = key
                }
                return source.open(dataSpec.buildUpon().setKey(key)
                    .setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build())
            }

            override fun close() {
                try { source.close() } finally {
                    synchronized(cache) {
                        activeKey?.let { key ->
                            val remaining = evictor.inUse.getValue(key) - 1
                            if (remaining == 0) {
                                evictor.inUse.remove(key)
                                if (evictor.pendingClear.remove(key)) cache.removeResource(key)
                            } else evictor.inUse[key] = remaining
                            activeKey = null
                        }
                        evictor.clean(cache)
                    }
                }
            }
        }
    }

    fun clean() = synchronized(cache) { evictor.clean(cache) }

    // 正在读取的缓存延后至加载器释放再清理，避免用户清理时打断当前读取。
    fun clear(): Boolean = synchronized(cache) {
        evictor.pendingClear.addAll(evictor.inUse.keys)
        cache.keys.filterNot { it in evictor.inUse }.forEach(cache::removeResource)
        mutableBytes.value = cache.cacheSpace
        evictor.pendingClear.isNotEmpty()
    }

    fun release() { cache.release() }

    private inner class Evictor : CacheEvictor {
        private val spans = TreeSet(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var size = 0L
        val inUse = mutableMapOf<String, Int>()
        val pendingClear = mutableSetOf<String>()

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() { mutableBytes.value = size }

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            clean(cache, if (length == C.LENGTH_UNSET.toLong()) FRAGMENT_BYTES else length)
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            spans.add(span)
            size += span.length
            if (cache.getContentMetadata(span.key).get(CREATED_AT, -1L) < 0) {
                cache.applyContentMetadataMutations(span.key, ContentMetadataMutations().set(CREATED_AT, now()))
            }
            trim(cache, 0)
            mutableBytes.value = size
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            spans.remove(span)
            size -= span.length
            mutableBytes.value = size
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            spans.remove(oldSpan)
            spans.add(newSpan)
        }

        fun clean(cache: Cache, incoming: Long = 0) {
            val time = now()
            cache.keys.filterNot { it in inUse }.forEach { key ->
                val created = cache.getContentMetadata(key).get(CREATED_AT, -1L)
                if (created >= 0 && time - created >= MAX_AGE_MS) cache.removeResource(key)
            }
            trim(cache, incoming)
            mutableBytes.value = size
        }

        private fun trim(cache: Cache, incoming: Long) {
            while (size + incoming > limitBytes() && spans.isNotEmpty()) {
                val oldest = spans.firstOrNull { it.key !in inUse } ?: spans.first()
                cache.removeSpan(oldest)
            }
        }
    }

    companion object {
        const val GB = 1_000_000_000L
        val MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(7)
        private const val FRAGMENT_BYTES = 2L * 1024 * 1024
        private const val CREATED_AT = "solara.createdAt"
    }
}
