package io.github.akudamatata.solara

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import io.github.akudamatata.solara.playback.DiagnosticAudioSink
import io.github.akudamatata.solara.playback.PlaybackDiagnostics
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(UnstableApi::class)
class PlaybackDiagnosticsTest {
    @Test fun diagnosticsKeepRecentEventsInOrderAndStayBounded() {
        val diagnostics = PlaybackDiagnostics { 0L }
        repeat(PlaybackDiagnostics.MAX_EVENTS + 10) { diagnostics.record("事件=$it") }
        val lines = diagnostics.snapshot().lines()
        assertEquals(PlaybackDiagnostics.MAX_EVENTS, lines.size)
        assertEquals("1970-01-01T00:00:00Z 事件=10", lines.first())
        assertTrue(lines.last().endsWith("事件=209"))
    }

    @Test fun observingAudioDoesNotChangeMuteDuckingOrRestoredGain() {
        val received = mutableListOf<Float>()
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            if (method.name == "setVolume") received.add(args!![0] as Float)
            null
        } as AudioSink
        val diagnostics = PlaybackDiagnostics { 0L }
        val observed = DiagnosticAudioSink(sink, diagnostics)
        val volumes = listOf(1f, 0.2f, 1f, 0f, 0.6f)
        volumes.forEach(observed::setVolume)
        assertEquals(volumes, received)
        assertEquals(volumes.map { "1970-01-01T00:00:00Z 输出增益=$it" }, diagnostics.snapshot().lines())
    }
}
