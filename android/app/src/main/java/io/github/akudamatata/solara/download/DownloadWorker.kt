package io.github.akudamatata.solara.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.akudamatata.solara.R
import io.github.akudamatata.solara.SolaraApplication
import io.github.akudamatata.solara.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.coroutineContext

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext as SolaraApplication
    private val pending = context.getSharedPreferences("solara-pending-downloads", Context.MODE_PRIVATE)

    // 此处在 IO 线程同步持久化半成品 URI，保证进程被杀后仍可回收。
    @SuppressLint("ApplySharedPref")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val songText = inputData.getString("song") ?: return@withContext Result.failure()
        val song = Song.fromJson(JSONObject(songText))
        val quality = inputData.getString("quality") ?: "320"
        var target: Uri? = null
        var complete = false
        try {
            setForeground(notification(song.name, 0))
            // 同一任务被系统重启时，先回收之前的不可见半成品。
            pending.getString(id.toString(), null)?.let { applicationContext.contentResolver.delete(Uri.parse(it), null, null) }
            pending.edit().remove(id.toString()).commit()
            val audio = app.api.resolve(song, quality)
            val call = app.client.newCall(Request.Builder().url(audio.url).build())
            val job = coroutineContext[kotlinx.coroutines.Job]!!
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("下载返回 HTTP ${response.code}，请重试")
                    val body = response.body ?: throw IOException("音频内容为空")
                    val source = body.source()
                    source.request(16)
                    val prefix = source.peek().readByteArray(minOf(16L, source.buffer.size))
                    val format = AudioTransfer.identify(prefix, body.contentType()?.toString().orEmpty())
                    val filename = AudioTransfer.filename(song.name, song.artist, format.extension)
                    val resolver = applicationContext.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
                        put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Solara")
                        put(MediaStore.Audio.Media.IS_PENDING, 1)
                        put(MediaStore.Audio.Media.TITLE, song.name)
                        put(MediaStore.Audio.Media.ARTIST, song.artist)
                        put(MediaStore.Audio.Media.ALBUM, song.album)
                    }
                    target = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("无法创建音乐文件")
                    if (!pending.edit().putString(id.toString(), target.toString()).commit()) throw IOException("无法保存下载记录，请检查存储空间")
                    val total = body.contentLength()
                    var lastUpdate = 0L
                    resolver.openOutputStream(target!!)?.use { output ->
                        AudioTransfer.copy(source.inputStream(), output, total) { bytes ->
                            job.ensureActive()
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUpdate >= 750) {
                                val percent = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else -1
                                setProgressAsync(workDataOf("percent" to percent, "bytes" to bytes))
                                setForegroundAsync(notification(song.name, percent))
                                lastUpdate = now
                            }
                        }
                    } ?: throw IOException("无法写入音乐文件")
                    coroutineContext.ensureActive()
                    if (resolver.update(target!!, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null) != 1) {
                        throw IOException("音乐文件保存失败，请重试")
                    }
                    complete = true
                    Result.success(workDataOf("uri" to target.toString(), "filename" to filename, "bitrate" to audio.bitrate,
                        "song" to songText, "quality" to quality))
                }
            } finally { cancellation.cancel() }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            Result.failure(workDataOf("error" to (error.message ?: "下载失败，请重试"), "song" to songText, "quality" to quality))
        } finally {
            if (!complete) target?.let { applicationContext.contentResolver.delete(it, null, null) }
            pending.edit().remove(id.toString()).commit()
        }
    }

    private fun notification(title: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("solara-downloads", "音乐下载", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "solara-downloads")
            .setSmallIcon(R.drawable.ic_solara).setContentTitle(title)
            .setContentText(if (percent <= 0) "正在下载到 Music/Solara" else "已下载 $percent%")
            .setProgress(100, percent.coerceAtLeast(0), percent < 0).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return ForegroundInfo(id.hashCode() and Int.MAX_VALUE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
