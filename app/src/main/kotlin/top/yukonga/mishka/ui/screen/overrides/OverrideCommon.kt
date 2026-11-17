package top.yukonga.mishka.ui.screen.overrides

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.res.stringResource
import top.yukonga.mishka.R
import top.yukonga.mishka.domain.model.OverrideFormat
import top.yukonga.mishka.domain.model.OverrideProfile
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.viewmodel.OverrideProfileViewModel

@Composable
internal fun ClearOverrideErrorOnExit(viewModel: OverrideProfileViewModel) {
    DisposableEffect(viewModel) { onDispose { viewModel.clearError() } }
}

@Composable
internal fun OverrideBackButton(onBack: () -> Unit) {
    BackNavigationIcon(onBack)
}

@Composable
internal fun overrideSummary(profile: OverrideProfile): String = stringResource(
    if (profile.isRemote) R.string.override_source_remote else R.string.override_source_local,
) + " · " + stringResource(
    if (profile.format == OverrideFormat.Yaml) {
        R.string.override_format_yaml
    } else {
        R.string.override_format_javascript
    },
)
