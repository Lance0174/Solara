package io.github.akudamatata.solara.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.akudamatata.solara.MainActivity
import io.github.akudamatata.solara.SolaraApplication
import io.github.akudamatata.solara.data.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun Song.mediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(key)
    .setUri(localUri.ifBlank { "solara://track?data=${Uri.encode(toJson().toString())}" })
    .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setArtist(artist).setAlbumTitle(album)
        .setExtras(Bundle().apply { putString("song", toJson(includeLocal = true).toString()) }).build())
    .build()

fun MediaItem.song(): Song? = mediaMetadata.extras?.getString("song")?.let { Song.fromJson(JSONObject(it), allowLocal = true) }

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var artworkJob: Job? = null
    private val app get() = application as SolaraApplication

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpDataSource.Factory(app.client)
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme != "solara") spec else {
                val song = Song.fromJson(JSONObject(requireNotNull(spec.uri.getQueryParameter("data"))))
                val audio = app.api.resolve(song, spec.key?.substringAfterLast(':') ?: app.store.settings.value.quality)
                spec.withUri(Uri.parse(audio.url.toString()))
            }
        }
        val cached = app.audioCache.dataSourceFactory(resolving) { app.store.settings.value.quality }
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(cached)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = if (app.store.playMode == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
            shuffleModeEnabled = app.store.playMode == 2
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    return if (controller.packageName == packageName || controller.isTrusted) super.onConnect(session, controller)
                    else MediaSession.ConnectionResult.reject()
                }
            }).build()

        val saved = app.store.library.value.queue
        if (saved.isNotEmpty()) {
            player.setMediaItems(saved.map { it.mediaItem() }, app.store.savedIndex.coerceIn(saved.indices), app.store.savedPosition)
        }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                pauseIfSleepTimerExpired()
                if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                    app.store.saveQueue((0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).song() })
                    if (player.mediaItemCount == 0) player.stop()
                }
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) savePosition()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.mediaMetadata.artworkUri == null)) loadArtwork()
                if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED) || events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                    app.store.playMode = if (player.shuffleModeEnabled) 2 else if (player.repeatMode == Player.REPEAT_MODE_ONE) 1 else 0
                }
            }
        })
        scope.launch {
            while (isActive) {
                pauseIfSleepTimerExpired()
                val remaining = app.sleepTimer.remainingMillis()
                delay(if (remaining > 0) minOf(remaining, 5000L) else 5000L)
                if (player.mediaItemCount > 0) savePosition()
            }
        }
    }

    private fun pauseIfSleepTimerExpired() {
        if (app.sleepTimer.expireIfDue()) {
            player.pause()
            savePosition()
        }
    }

    private fun loadArtwork() {
        artworkJob?.cancel()
        val song = player.currentMediaItem?.song() ?: return
        artworkJob = scope.launch {
            try {
                val url = withContext(Dispatchers.IO) { app.api.cover(song) }
                val current = player.currentMediaItem ?: return@launch
                if (current.mediaId == song.key && url.isNotBlank()) {
                    player.replaceMediaItem(player.currentMediaItemIndex, current.buildUpon()
                        .setMediaMetadata(current.mediaMetadata.buildUpon().setArtworkUri(Uri.parse(url)).build()).build())
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { Log.w("Solara播放", "封面暂不可用，继续播放") }
        }
    }

    private fun savePosition() { app.store.savePosition(player.currentMediaItemIndex, player.currentPosition) }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition()
        app.sleepTimer.cancel()
        scope.cancel()
        session?.release()
        player.release()
        super.onDestroy()
    }
}
