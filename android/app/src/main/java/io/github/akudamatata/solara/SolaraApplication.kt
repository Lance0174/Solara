package io.github.akudamatata.solara

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.akudamatata.solara.data.LibraryStore
import io.github.akudamatata.solara.data.MusicApi
import io.github.akudamatata.solara.data.SessionCookies
import io.github.akudamatata.solara.playback.AudioCache
import io.github.akudamatata.solara.playback.CacheCleanupWorker
import io.github.akudamatata.solara.playback.SleepTimer
import io.github.akudamatata.solara.playback.PlaybackDiagnostics
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

// 音源接口与音频 CDN 只对浏览器 UA 放行（网页端即浏览器 UA），统一携带 Chrome 移动版标识。
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

class SolaraApplication : Application() {
    val store by lazy { LibraryStore(this) }
    val cookies by lazy { SessionCookies(this) }
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().cookieJar(cookies).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                // 音源接口与音频 CDN 会拒绝陌生客户端 UA，网页端始终以浏览器 UA 访问，这里保持一致。
                val builder = request.newBuilder().header("User-Agent", BROWSER_USER_AGENT)
                if (host == "kuwo.cn" || host.endsWith(".kuwo.cn")) builder.header("Referer", "https://www.kuwo.cn/")
                chain.proceed(builder.build())
            }.build()
    }
    val api by lazy { MusicApi(client) { store.settings.value } }
    val audioCache by lazy { AudioCache(this, { store.settings.value.cacheLimitGb * AudioCache.GB }) }
    val sleepTimer = SleepTimer()
    val playbackDiagnostics = PlaybackDiagnostics()

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("solara-cache-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(6, TimeUnit.HOURS).build())
    }
}
