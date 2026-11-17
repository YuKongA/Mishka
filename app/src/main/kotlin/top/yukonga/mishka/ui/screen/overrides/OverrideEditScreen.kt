package top.yukonga.mishka.ui.screen.overrides

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.platform.FilePickResult
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.theme.StatusColors
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private enum class AddMethod { Remote, Blank, Local }

@Composable
fun OverrideEditScreen(
    overrideId: String?,
    viewModel: OverrideProfileViewModel,
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    onPickFile: ((FilePickResult?) -> Unit) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profile = overrideId?.let { id -> state.profiles.find { it.id == id } }
    if (overrideId != null && profile == null) {
        LaunchedEffect(overrideId) { onBack() }
        return
    }

    ClearOverrideErrorOnExit(viewModel)
    var method by rememberSaveable { mutableStateOf(AddMethod.Remote) }
    var name by rememberSaveable(overrideId) { mutableStateOf(profile?.name.orEmpty()) }
    var url by rememberSaveable(overrideId) {
        mutableStateOf(profile?.takeIf { it.isRemote }?.sourceLocation.orEmpty())
    }
    var format by rememberSaveable(overrideId) { mutableStateOf(profile?.format ?: OverrideFormat.Yaml) }
    var picked by remember { mutableStateOf<FilePickResult?>(null) }
    val isRemote = profile?.isRemote ?: (method == AddMethod.Remote)
    val hasChanges = profile == null || name != profile.name || format != profile.format ||
            (profile.isRemote && url != profile.sourceLocation)
    val canSave = hasChanges && !state.isLoading && name.isNotBlank() &&
            (profile != null || method != AddMethod.Local || picked != null)

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(if (profile == null) R.string.override_add else R.string.override_edit),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { OverrideBackButton(onBack) },
                    )
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .horizontalCutoutPadding()
                    .scrollEndHaptic()
                    .overScrollVertical(scrollBehavior)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                ),
            ) {
                if (profile == null) {
                    item(key = "top_spacer") { Spacer(Modifier.height(12.dp)) }
                    groupedCardItems(
                        keyPrefix = "override_add_method",
                        outerBottomPadding = 6.dp,
                        items = listOf(
                            CardItem("method") {
                                GlassDropdownPreference(
                                    title = stringResource(R.string.override_add_method),
                                    backdrop = LocalPageBackdrop.current,
                                    items = AddMethod.entries.map { stringResource(it.label) },
                                    selectedIndex = method.ordinal,
                                    onSelectedIndexChange = { method = AddMethod.entries[it] },
                                )
                            },
                        ),
                    )
                }

                item(key = "name") {
                    SmallTitle(text = stringResource(R.string.override_name))
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.override_name_placeholder),
                        useLabelAsPlaceholder = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 6.dp),
                    )
                }

                if (isRemote) {
                    item(key = "url") {
                        SmallTitle(text = stringResource(R.string.override_url))
                        TextField(
                            value = url,
                            onValueChange = { url = it },
                            label = stringResource(R.string.override_url_placeholder),
                            useLabelAsPlaceholder = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 6.dp),
                        )
                    }
                }

                if (profile == null && method == AddMethod.Local) {
                    groupedCardItems(
                        keyPrefix = "override_local_file",
                        outerTopPadding = 6.dp,
                        outerBottomPadding = 6.dp,
                        items = listOf(
                            CardItem("file") {
                                ArrowPreference(
                                    title = stringResource(R.string.override_local_file),
                                    summary = picked?.fileName ?: stringResource(R.string.override_error_file_required),
                                    onClick = {
                                        onPickFile { result ->
                                            if (result != null) {
                                                picked = result
                                                if (name.isBlank()) name = result.fileName.substringBeforeLast('.')
                                                format = if (result.fileName.endsWith(".js", ignoreCase = true)) {
                                                    OverrideFormat.JavaScript
                                                } else {
                                                    OverrideFormat.Yaml
                                                }
                                            }
                                        }
                                    },
                                )
                            },
                        ),
                    )
                }

                groupedCardItems(
                    keyPrefix = "override_format",
                    outerTopPadding = 6.dp,
                    outerBottomPadding = 12.dp,
                    items = listOf(
                        CardItem("format") {
                            GlassDropdownPreference(
                                title = stringResource(R.string.override_format),
                                backdrop = LocalPageBackdrop.current,
                                items = OverrideFormat.entries.map { it.label },
                                selectedIndex = format.ordinal,
                                onSelectedIndexChange = { format = OverrideFormat.entries[it] },
                            )
                        },
                    ),
                )

                if (state.error.isNotEmpty()) {
                    item(key = "error") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            Text(text = state.error, color = StatusColors.danger)
                        }
                    }
                }

                item(key = "save") {
                    TextButton(
                        text = stringResource(R.string.common_save),
                        enabled = canSave,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            when {
                                profile != null -> viewModel.edit(profile, name, url, format, onSaved)
                                method == AddMethod.Remote -> viewModel.addRemote(name, url, format, onSaved)
                                method == AddMethod.Local -> picked?.let {
                                    viewModel.addLocal(name, it.fileName, format, it.content, onSaved)
                                }

                                else -> viewModel.addBlank(name, format, onSaved)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    )
                }

                item(key = "bottom_spacer") {
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

private val AddMethod.label: Int
    get() = when (this) {
        AddMethod.Remote -> R.string.override_method_remote
        AddMethod.Blank -> R.string.override_method_blank
        AddMethod.Local -> R.string.override_method_local
    }

private val OverrideFormat.label: String
    @Composable get() = when (this) {
        OverrideFormat.Yaml -> stringResource(R.string.override_format_yaml)
        OverrideFormat.JavaScript -> stringResource(R.string.override_format_javascript)
    }
