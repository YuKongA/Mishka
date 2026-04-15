package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextButtonColors
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

fun LazyListScope.actionButtonsSection(
    onRestart: () -> Unit = {},
    onStop: () -> Unit = {},
    onReload: () -> Unit = {},
    onStart: () -> Unit = {},
    isRunning: Boolean = false,
    isStarting: Boolean = false,
) {
    item(key = "actions") {
        ActionButtonsRow(onRestart, onStop, onReload, onStart, isRunning, isStarting)
    }
}

@Composable
private fun ActionButtonsRow(
    onRestart: () -> Unit,
    onStop: () -> Unit,
    onReload: () -> Unit,
    onStart: () -> Unit,
    isRunning: Boolean,
    isStarting: Boolean,
) {
    val isDark = isSystemInDarkTheme()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isRunning) {
            TextButton(
                text = stringResource(Res.string.home_restart),
                onClick = onRestart,
                modifier = Modifier.weight(1f),
                colors = TextButtonColors(
                    color = if (isDark) Color(0xFF1B3A26) else Color(0xFFDFF5E3),
                    disabledColor = if (isDark) Color(0xFF1B3A26).copy(alpha = 0.5f) else Color(0xFFDFF5E3).copy(alpha = 0.5f),
                    textColor = if (isDark) Color(0xFF66BB6A) else Color(0xFF43A047),
                    disabledTextColor = if (isDark) Color(0xFF66BB6A).copy(alpha = 0.5f) else Color(0xFF43A047).copy(alpha = 0.5f),
                ),
            )
            TextButton(
                text = stringResource(Res.string.home_stop),
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = TextButtonColors(
                    color = if (isDark) Color(0xFF3A1B1B) else Color(0xFFFDE8E8),
                    disabledColor = if (isDark) Color(0xFF3A1B1B).copy(alpha = 0.5f) else Color(0xFFFDE8E8).copy(alpha = 0.5f),
                    textColor = if (isDark) Color(0xFFEF5350) else Color(0xFFE53935),
                    disabledTextColor = if (isDark) Color(0xFFEF5350).copy(alpha = 0.5f) else Color(0xFFE53935).copy(alpha = 0.5f),
                ),
            )
            TextButton(
                text = stringResource(Res.string.home_reload),
                onClick = onReload,
                modifier = Modifier.weight(1f),
                colors = TextButtonColors(
                    color = if (isDark) Color(0xFF1B2D3A) else Color(0xFFE3F2FD),
                    disabledColor = if (isDark) Color(0xFF1B2D3A).copy(alpha = 0.5f) else Color(0xFFE3F2FD).copy(alpha = 0.5f),
                    textColor = if (isDark) Color(0xFF42A5F5) else Color(0xFF1E88E5),
                    disabledTextColor = if (isDark) Color(0xFF42A5F5).copy(alpha = 0.5f) else Color(0xFF1E88E5).copy(alpha = 0.5f),
                ),
            )
        } else {
            TextButton(
                text = if (isStarting) stringResource(Res.string.home_starting_btn) else stringResource(Res.string.home_start),
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = !isStarting,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
