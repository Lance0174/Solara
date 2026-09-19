package io.github.akudamatata.solara.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.time.Instant

// 仅保留本次进程最近的事件，不写音频、歌曲地址、口令或设备名称；由用户主动导出。
class PlaybackDiagnostics(private val now: () -> Long = System::currentTimeMillis) {
    private val entries = ArrayDeque<String>()

    @Synchronized fun record(event: String) {
        if (entries.size == MAX_EVENTS) entries.removeFirst()
        entries.addLast("${Instant.ofEpochMilli(now())} $event")
    }

    @Synchronized fun snapshot(): String = entries.joinToString("\n")

    companion object { const val MAX_EVENTS = 200 }
}

// Media3 的音频焦点压低不会改变 Player.volume；在输出端观察最终增益，并原样传给音频输出。
@OptIn(UnstableApi::class)
class DiagnosticAudioSink(sink: AudioSink, private val diagnostics: PlaybackDiagnostics) : ForwardingAudioSink(sink) {
    override fun setVolume(volume: Float) {
        super.setVolume(volume)
        diagnostics.record("输出增益=$volume")
    }
}
