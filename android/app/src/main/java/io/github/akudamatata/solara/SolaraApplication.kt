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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class SolaraApplication : Application() {
    val store by lazy { LibraryStore(this) }
    val cookies by lazy { SessionCookies(this) }
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().cookieJar(cookies).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val builder = request.newBuilder().header("User-Agent", "Solara-Android/1.0")
                if (host == "kuwo.cn" || host.endsWith(".kuwo.cn")) builder.header("Referer", "https://www.kuwo.cn/")
                chain.proceed(builder.build())
            }.build()
    }
    val api by lazy { MusicApi(client) { store.settings.value } }
    val audioCache by lazy { AudioCache(this, { store.settings.value.cacheLimitGb * AudioCache.GB }) }
    val sleepTimer = SleepTimer()

    override fun onCreate() {
        super.onCreate()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("solara-cache-cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheCleanupWorker>(6, TimeUnit.HOURS).build())
    }
}
