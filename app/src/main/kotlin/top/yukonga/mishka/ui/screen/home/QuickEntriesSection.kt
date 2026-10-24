package top.yukonga.mishka.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.mishka.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

fun LazyListScope.quickEntriesSection(
    isRunning: Boolean,
    onNavigateLog: () -> Unit = {},
    onNavigateProvider: () -> Unit = {},
    onNavigateConnection: () -> Unit = {},
    onNavigateDnsQuery: () -> Unit = {},
) {
    item(key = "quick_entries_title") {
        SmallTitle(text = stringResource(R.string.home_tools))
    }
    item(key = "quick_entries") {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_providers),
                    subtitle = stringResource(R.string.home_providers_subtitle),
                    isRunning = isRunning,
                    onClick = onNavigateProvider,
                )
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_connections),
                    subtitle = stringResource(R.string.home_connections_subtitle),
                    isRunning = isRunning,
                    onClick = onNavigateConnection,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_logs),
                    subtitle = stringResource(R.string.home_logs_subtitle),
                    isRunning = isRunning,
                    onClick = onNavigateLog,
                )
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(R.string.home_dns),
                    subtitle = stringResource(R.string.home_dns_subtitle),
                    isRunning = isRunning,
                    onClick = onNavigateDnsQuery,
                )
            }
        }
    }
}

@Composable
private fun QuickEntryCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    isRunning: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        showIndication = isRunning,
        pressFeedbackType = if (isRunning) PressFeedbackType.Sink else PressFeedbackType.None,
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
