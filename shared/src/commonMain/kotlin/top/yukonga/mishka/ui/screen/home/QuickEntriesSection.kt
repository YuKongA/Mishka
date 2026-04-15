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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import mishka.shared.generated.resources.Res
import mishka.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource

fun LazyListScope.quickEntriesSection(
    onNavigateLog: () -> Unit = {},
    onNavigateProvider: () -> Unit = {},
    onNavigateConnection: () -> Unit = {},
    onNavigateDnsQuery: () -> Unit = {},
) {
    item(key = "quick_entries") {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(Res.string.home_providers),
                    subtitle = stringResource(Res.string.home_providers_subtitle),
                    onClick = onNavigateProvider,
                )
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(Res.string.home_connections),
                    subtitle = stringResource(Res.string.home_connections_subtitle),
                    onClick = onNavigateConnection,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(Res.string.home_logs),
                    subtitle = stringResource(Res.string.home_logs_subtitle),
                    onClick = onNavigateLog,
                )
                QuickEntryCard(
                    modifier = Modifier.weight(1f),
                    title = stringResource(Res.string.home_dns),
                    subtitle = stringResource(Res.string.home_dns_subtitle),
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
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
        pressFeedbackType = PressFeedbackType.Sink,
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
