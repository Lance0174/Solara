package io.github.akudamatata.solara.ui

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.akudamatata.solara.BuildConfig
import io.github.akudamatata.solara.MusicViewModel
import io.github.akudamatata.solara.data.GENRES
import io.github.akudamatata.solara.data.MAX_AUDIO_CACHE_GB
import io.github.akudamatata.solara.data.THEME_STYLES
import io.github.akudamatata.solara.playback.PlaybackDiagnostics

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MusicViewModel) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val cacheBytes by vm.cacheBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClearCache by remember { mutableStateOf(false) }
    var site by rememberSaveable { mutableStateOf(saved.site) }
    var api by rememberSaveable { mutableStateOf(saved.api) }
    // 口令不进入 SavedState 或本地偏好，登录提交后立即清空。
    var password by remember { mutableStateOf("") }
    val exportDiagnostics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.let(vm::exportPlaybackDiagnostics)
    }
    LaunchedEffect(vm) { vm.refreshAudioCache() }
    // 站点与接口在输入完成（焦点离开输入框）时自动校验保存，口令填写后失焦即登录；与地址/口令无关的改动不触发。
    fun commitConnection() {
        if (site.trim().trimEnd('/') == saved.site && api == saved.api && password.isBlank()) return
        vm.saveSettings(saved.copy(site = site, api = api), password) { password = "" }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("外观", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ChoiceMenu(mapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")[saved.theme].orEmpty(),
            linkedMapOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色")) { vm.saveAppearance(theme = it) }
        ChoiceMenu("主题风格：${THEME_STYLES[saved.themeStyle]}", THEME_STYLES) { vm.saveAppearance(themeStyle = it) }
        Text("明暗模式可独立选择；外观改动即时生效并自动保存，跟随系统随系统深浅色切换。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SolaraTheme(saved) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("SOLARA // ${if (saved.themeStyle == "endfield") "ENDFIELD" else "MUSIC"}", style = MaterialTheme.typography.labelMedium)
                    Text("主题预览 · 音乐随行", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        HorizontalDivider()
        Text("探索雷达", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("选择想听的音乐风格", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GENRES.forEach { genre -> FilterChip(selected = genre in saved.genres,
                onClick = { vm.saveAppearance(genres = if (genre in saved.genres) saved.genres - genre else saved.genres + genre) }, label = { Text(genre) }) }
        }
        HorizontalDivider()
        Text("播放缓存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(cacheBytes?.let { "已使用 ${Formatter.formatShortFileSize(context, it)} / ${saved.cacheLimitGb} GB" }
            ?: "正在读取缓存用量…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceMenu("缓存上限：${saved.cacheLimitGb} GB", (1..MAX_AUDIO_CACHE_GB).associate { it.toString() to "$it GB" }) {
            vm.setCacheLimit(it.toInt())
        }
        Text("默认及最高 5 GB。超限优先清理较久未使用的内容；缓存写入满 7 天自动清理。容量调整立即生效。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("边听边缓存，完整缓存的歌曲可在断网或服务异常时播放；未缓存部分仍需联网。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { confirmClearCache = true }, enabled = !busy && (cacheBytes ?: 0L) > 0,
            modifier = Modifier.fillMaxWidth()) { Text("清理播放缓存") }
        HorizontalDivider()
        Text("Solara 站点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("可选。填写后音乐接口经由自己的站点代理，并可登录站点账号。改动在输入完成后自动保存；留空时使用公共音乐接口。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(value = site, onValueChange = { site = it }, label = { Text("站点地址（可选）") },
            placeholder = { Text("https://music.example.com") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitConnection() },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        if (site.isNotBlank()) {
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("站点登录口令") },
                supportingText = { Text("仅登录时需要，已有会话可留空；输入完成后自动登录") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused && password.isNotBlank()) commitConnection() })
        } else {
            OutlinedTextField(value = api, onValueChange = { api = it }, label = { Text("音乐接口地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commitConnection() },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        }
        if (saved.site.isNotBlank()) {
            TextButton(onClick = { vm.logout() }, enabled = !busy) { Text("退出站点登录") }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text("播放诊断", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("声音异常后可导出本次运行最近 ${PlaybackDiagnostics.MAX_EVENTS} 条播放事件。记录仅在本机内存中保留，关闭进程后清空，不含音频、歌曲地址或口令。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { exportDiagnostics.launch("Solara-播放诊断-${System.currentTimeMillis()}.txt") },
            enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("导出播放诊断") }
        HorizontalDivider()
        Text("关于 Solara", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Android ${BuildConfig.VERSION_NAME} · 原生音乐客户端")
        Text("源项目：akudamatata/Solara\nhttps://github.com/akudamatata/Solara\n感谢 GD 音乐台提供音乐 API。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("沿用上游 CC BY-NC-SA 协议：禁止商业化，衍生项目保留项目地址并以相同协议开源。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
    }
    if (confirmClearCache) AlertDialog(onDismissRequest = { confirmClearCache = false }, title = { Text("清理播放缓存？") },
        text = { Text("将清理自动缓存的音频，正在使用的部分会在释放后清理。已下载的音乐、歌单和收藏会保留。") },
        confirmButton = { TextButton(onClick = { confirmClearCache = false; vm.clearAudioCache() }, enabled = !busy) { Text("清理") } },
        dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("取消") } })
}
