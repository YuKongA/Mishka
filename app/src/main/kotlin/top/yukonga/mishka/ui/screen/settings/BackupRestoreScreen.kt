package top.yukonga.mishka.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.FilePicker
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.HintCard
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.viewmodel.BackupViewModel
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Backup
import top.yukonga.miuix.kmp.icon.glass.Import
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupRestoreScreen(
    viewModel: BackupViewModel,
    storage: PlatformStorage,
    filePicker: FilePicker? = null,
    onRestartApp: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    var showLocalDialog by remember { mutableStateOf(false) }
    var showWebDavDialog by remember { mutableStateOf(false) }
    // 恢复来源（本地文件 / WebDAV）共用同一个覆盖确认对话框，确认后执行挂起的动作
    var pendingRestoreAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.backup_title),
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackNavigationIcon(onBack) },
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
                item(key = "top_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "hint") {
                    HintCard(text = stringResource(R.string.backup_hint))
                }
                groupedCardItems(
                    keyPrefix = "backup_entries",
                    outerBottomPadding = 12.dp,
                    items = listOf(
                        CardItem("local") {
                            ArrowPreference(
                                title = stringResource(R.string.backup_section_local),
                                summary = stringResource(R.string.backup_local_row_summary),
                                enabled = filePicker != null && !uiState.isBusy,
                                holdDownState = showLocalDialog,
                                onClick = { showLocalDialog = true },
                            )
                        },
                        CardItem("webdav") {
                            ArrowPreference(
                                title = stringResource(R.string.backup_section_webdav),
                                summary = stringResource(R.string.backup_webdav_row_summary),
                                enabled = !uiState.isBusy,
                                holdDownState = showWebDavDialog,
                                onClick = { showWebDavDialog = true },
                            )
                        },
                    ),
                )
                item(key = "bottom_spacer") {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }
            }
        }

        if (filePicker != null) {
            LocalBackupDialog(
                show = showLocalDialog,
                isBusy = uiState.isBusy,
                onDismiss = { showLocalDialog = false },
                onExport = {
                    showLocalDialog = false
                    viewModel.exportBackup { onResult ->
                        filePicker.createZipDocument(defaultBackupFileName(), onResult)
                    }
                },
                onRestore = {
                    showLocalDialog = false
                    pendingRestoreAction = {
                        filePicker.pickZipDocument { uri ->
                            if (uri != null) viewModel.restoreFromDocument(uri)
                        }
                    }
                },
            )
        }

        WebDavDialog(
            show = showWebDavDialog,
            isBusy = uiState.isBusy,
            storage = storage,
            onDismiss = { showWebDavDialog = false },
            onTest = { viewModel.testConnection() },
            onBackup = { viewModel.backup() },
            onRestore = {
                showWebDavDialog = false
                pendingRestoreAction = { viewModel.restore() }
            },
        )

        GlassDialog(
            show = pendingRestoreAction != null,
            backdrop = LocalPageBackdrop.current,
            title = stringResource(R.string.backup_restore_confirm_title),
            summary = stringResource(R.string.backup_restore_confirm_summary),
            onDismissRequest = { pendingRestoreAction = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassDialogTextButton(
                    text = stringResource(R.string.common_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { pendingRestoreAction = null },
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    modifier = Modifier.weight(1f),
                    role = GlassDialogButtonRole.Primary,
                    onClick = {
                        val action = pendingRestoreAction
                        pendingRestoreAction = null
                        action?.invoke()
                    },
                )
            }
        }

        // 恢复成功后强制重启：OverrideJsonStore / Repository Flow 等内存态不随磁盘恢复刷新，不给取消项
        GlassDialog(
            show = uiState.restoreCompleted,
            backdrop = LocalPageBackdrop.current,
            title = stringResource(R.string.backup_restore_done_title),
            summary = stringResource(R.string.backup_restore_done_summary),
            onDismissRequest = {},
        ) {
            GlassDialogTextButton(
                text = stringResource(R.string.backup_restart_now),
                modifier = Modifier.fillMaxWidth(),
                role = GlassDialogButtonRole.Primary,
                onClick = onRestartApp,
            )
        }
    }
}

@Composable
private fun LocalBackupDialog(
    show: Boolean,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
) {
    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = stringResource(R.string.backup_section_local),
        insideMargin = DpSize(0.dp, 24.dp),
        onDismissRequest = onDismiss,
    ) {
        ArrowPreference(
            title = stringResource(R.string.backup_export_local),
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Glass.Backup,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 16.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
            enabled = !isBusy,
            onClick = onExport,
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        )
        ArrowPreference(
            title = stringResource(R.string.backup_restore_local),
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Glass.Import,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 16.dp),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            },
            enabled = !isBusy,
            onClick = onRestore,
            insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        )
        GlassDialogTextButton(
            text = stringResource(R.string.common_cancel),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun WebDavDialog(
    show: Boolean,
    isBusy: Boolean,
    storage: PlatformStorage,
    onDismiss: () -> Unit,
    onTest: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    val urlState = rememberTextFieldState(initialText = storage.getString(StorageKeys.WEBDAV_URL, ""))
    val usernameState = rememberTextFieldState(initialText = storage.getString(StorageKeys.WEBDAV_USERNAME, ""))
    val passwordState = rememberTextFieldState(initialText = storage.getString(StorageKeys.WEBDAV_PASSWORD, ""))

    LaunchedEffect(urlState, usernameState, passwordState) {
        snapshotFlow {
            Triple(
                urlState.text.toString(),
                usernameState.text.toString(),
                passwordState.text.toString(),
            )
        }.drop(1).collect { (url, username, password) ->
            storage.putString(StorageKeys.WEBDAV_URL, url)
            storage.putString(StorageKeys.WEBDAV_USERNAME, username)
            storage.putString(StorageKeys.WEBDAV_PASSWORD, password)
        }
    }

    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = stringResource(R.string.backup_section_webdav),
        insideMargin = DpSize(0.dp, 24.dp),
        onDismissRequest = onDismiss,
    ) {
        // 长内容：滚动区收缩 + 底部按钮行固定，防小屏上按钮被顶出可视区
        Column(Modifier.heightIn(max = 500.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    state = urlState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    label = stringResource(R.string.backup_server_url),
                )
                TextField(
                    state = usernameState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 8.dp),
                    label = stringResource(R.string.backup_username),
                )
                TextField(
                    state = passwordState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 12.dp),
                    label = stringResource(R.string.backup_password),
                )
                ArrowPreference(
                    title = stringResource(R.string.backup_now),
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.Glass.Backup,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 16.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    },
                    enabled = !isBusy,
                    onClick = onBackup,
                    insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                )
                ArrowPreference(
                    title = stringResource(R.string.backup_restore_now),
                    startAction = {
                        Icon(
                            imageVector = MiuixIcons.Glass.Import,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 16.dp),
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    },
                    enabled = !isBusy,
                    onClick = onRestore,
                    insideMargin = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassDialogTextButton(
                    text = stringResource(R.string.backup_test_connection),
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy,
                    onClick = onTest,
                )
                GlassDialogTextButton(
                    text = stringResource(R.string.common_close),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
            }
        }
    }
}

private fun defaultBackupFileName(): String {
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        .format(Date())
    return "mishka-backup-$ts.zip"
}
