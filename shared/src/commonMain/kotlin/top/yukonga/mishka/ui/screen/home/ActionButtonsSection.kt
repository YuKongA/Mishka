package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.home_reload
import mishka.shared.generated.resources.home_restart
import mishka.shared.generated.resources.home_start
import mishka.shared.generated.resources.home_starting_btn
import mishka.shared.generated.resources.home_stop
import mishka.shared.generated.resources.home_stopping_btn
import org.jetbrains.compose.resources.stringResource
import top.yukonga.mishka.ui.theme.ActionKind
import top.yukonga.mishka.ui.theme.ActionPalette
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextButtonColors

fun LazyListScope.actionButtonsSection(
    onRestart: () -> Unit = {},
    onStop: () -> Unit = {},
    onReload: () -> Unit = {},
    onStart: () -> Unit = {},
    isRunning: Boolean = false,
    isStarting: Boolean = false,
    isStopping: Boolean = false,
) {
    item(key = "actions") {
        ActionButtonsRow(onRestart, onStop, onReload, onStart, isRunning, isStarting, isStopping)
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
    isStopping: Boolean,
) {
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
                colors = StatusColors.actionButton(ActionKind.Restart).asTextButtonColors(),
            )
            TextButton(
                text = stringResource(Res.string.home_stop),
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = StatusColors.actionButton(ActionKind.Stop).asTextButtonColors(),
            )
            TextButton(
                text = stringResource(Res.string.home_reload),
                onClick = onReload,
                modifier = Modifier.weight(1f),
                colors = StatusColors.actionButton(ActionKind.Reload).asTextButtonColors(),
            )
        } else if (isStopping) {
            TextButton(
                text = stringResource(Res.string.home_stopping_btn),
                onClick = {},
                modifier = Modifier.weight(1f),
                enabled = false,
                colors = ButtonDefaults.textButtonColorsPrimary(),
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

private fun ActionPalette.asTextButtonColors(): TextButtonColors = TextButtonColors(
    color = container,
    disabledColor = container.copy(alpha = 0.5f),
    textColor = content,
    disabledTextColor = content.copy(alpha = 0.5f),
)
