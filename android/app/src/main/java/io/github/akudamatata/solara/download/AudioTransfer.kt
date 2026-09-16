package io.github.akudamatata.solara.download

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

data class AudioFormat(val extension: String, val mimeType: String)

object AudioTransfer {
    // 按真实内容识别格式，防止把登录页或接口错误保存成“成功的 mp3”。
    fun identify(prefix: ByteArray, contentType: String): AudioFormat {
        if (contentType.contains("text/", ignoreCase = true) || contentType.contains("json", ignoreCase = true)) {
            throw IOException("下载地址返回了网页或错误信息，没有保存音频")
        }
        val start = prefix.take(12).toByteArray().toString(Charsets.ISO_8859_1)
        return when {
            start.startsWith("fLaC") -> AudioFormat("flac", "audio/flac")
            start.startsWith("ID3") -> AudioFormat("mp3", "audio/mpeg")
            start.startsWith("OggS") -> AudioFormat("ogg", "audio/ogg")
            start.startsWith("RIFF") && start.drop(8).startsWith("WAVE") -> AudioFormat("wav", "audio/wav")
            start.drop(4).startsWith("ftyp") -> AudioFormat("m4a", "audio/mp4")
            prefix.size >= 2 && prefix[0].toInt() and 255 == 255 && prefix[1].toInt() and 224 == 224 -> {
                if (prefix[1].toInt() and 246 == 240) AudioFormat("aac", "audio/aac") else AudioFormat("mp3", "audio/mpeg")
            }
            prefix.size >= 4 && prefix.take(4).map { it.toInt() and 255 } == listOf(26, 69, 223, 163) -> AudioFormat("webm", "audio/webm")
            else -> throw IOException("未识别到有效音频，请切换音质或音源后重试")
        }
    }

    fun filename(title: String, artist: String, extension: String): String =
        ("$title - $artist").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(100).ifBlank { "Solara" } + ".$extension"

    fun copy(input: InputStream, output: OutputStream, expectedSize: Long, onBytes: (Long) -> Unit): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            onBytes(copied)
            val count = input.read(buffer)
            if (count == -1) break
            output.write(buffer, 0, count)
            copied += count
        }
        if (copied == 0L || (expectedSize >= 0 && copied != expectedSize)) throw IOException("音频传输不完整，请重试")
        output.flush()
        onBytes(copied)
        return copied
    }
}
