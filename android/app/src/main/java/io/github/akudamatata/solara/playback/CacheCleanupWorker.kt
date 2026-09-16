package io.github.akudamatata.solara.playback

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.akudamatata.solara.SolaraApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class CacheCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            (applicationContext as SolaraApplication).audioCache.clean()
            Result.success()
        } catch (_: IOException) {
            Log.w("Solara缓存", "自动清理暂未完成，稍后重试")
            Result.retry()
        }
    }
}
