package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.SubscriptionViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun FileManagerScreen(
    subscriptionViewModel: SubscriptionViewModel?,
    onBack: () -> Unit = {},
    onOpenFile: (uuid: String, relativePath: String) -> Unit = { _, _ -> },
) {
    val scrollBehavior = MiuixScrollBehavior()
    val uiState = subscriptionViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val importedSubs = uiState?.subscriptions.orEmpty().filter { it.imported }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.file_manager_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { BackNavigationIcon(onBack) },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalCutoutPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(scrollBehavior)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                if (importedSubs.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.file_manager_empty),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    item(key = "top_spacer", contentType = "spacer") {
                        Spacer(Modifier.height(12.dp))
                    }
                    items(importedSubs, key = { it.id }) { sub ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            ArrowPreference(
                                title = sub.name.ifBlank { sub.id.take(8) },
                                summary = sub.id,
                                onClick = {
                                    val files = subscriptionViewModel?.fileManager?.listImportedFiles(sub.id).orEmpty()
                                    val firstFile = files.firstOrNull { it == "config.yaml" } ?: files.firstOrNull()
                                    if (firstFile != null) {
                                        onOpenFile(sub.id, firstFile)
                                    }
                                },
                            )
                        }
                    }
                    item {
                        Spacer(
                            Modifier
                                .height(24.dp)
                                .navigationBarsPadding()
                        )
                    }
                }
            }
        }
    }
}
