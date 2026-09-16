package io.github.akudamatata.solara.playback

import android.os.SystemClock
import androidx.annotation.MainThread

@MainThread
class SleepTimer(private val now: () -> Long = SystemClock::elapsedRealtime) {
    private var deadlineMillis: Long? = null

    fun start(minutes: Int) {
        require(minutes in 1..MAX_MINUTES) { "请输入 1–$MAX_MINUTES 分钟" }
        deadlineMillis = now() + minutes * 60_000L
    }

    fun cancel() { deadlineMillis = null }

    fun remainingMillis(): Long = deadlineMillis?.let { (it - now()).coerceAtLeast(0) } ?: 0L

    // 先消费截止时间再通知播放器，防止暂停事件或旧检查重复关闭新的播放。
    fun expireIfDue(): Boolean {
        val deadline = deadlineMillis ?: return false
        if (now() < deadline) return false
        deadlineMillis = null
        return true
    }

    companion object {
        val PRESET_MINUTES = listOf(10, 20, 30, 45, 60, 90)
        const val MAX_MINUTES = 1440
    }
}
