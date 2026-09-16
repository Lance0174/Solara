package io.github.akudamatata.solara.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.akudamatata.solara.playback.SleepTimer

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(
    remainingLabel: String?,
    enabled: Boolean,
    onSet: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var custom by rememberSaveable { mutableStateOf(false) }
    var customMinutes by rememberSaveable { mutableStateOf("") }
    val minutes = customMinutes.trim().toIntOrNull()
    val valid = minutes != null && minutes in 1..SleepTimer.MAX_MINUTES
    AlertDialog(onDismissRequest = onDismiss, title = { Text("定时关闭音乐") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(remainingLabel?.let { "还剩 $it，届时暂停音乐" } ?: "选择多久后暂停音乐")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepTimer.PRESET_MINUTES.forEach { preset ->
                        OutlinedButton(onClick = { onSet(preset) }, enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 16.dp)) { Text("$preset 分钟") }
                    }
                }
                TextButton(onClick = { custom = !custom }, enabled = enabled) { Text("自定义") }
                if (custom) OutlinedTextField(value = customMinutes, onValueChange = { customMinutes = it },
                    label = { Text("自定义分钟数") }, supportingText = { Text("1–${SleepTimer.MAX_MINUTES} 分钟") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
                    isError = customMinutes.isNotBlank() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) minutes?.let(onSet) }))
                Text("切歌或手动暂停不重置计时；重新选择时长会重新计时。", style = MaterialTheme.typography.bodySmall)
                if (remainingLabel != null) TextButton(onClick = onCancel, enabled = enabled) { Text("取消定时关闭") }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = { minutes?.let(onSet) }, enabled = enabled && valid) { Text("开始计时") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}
