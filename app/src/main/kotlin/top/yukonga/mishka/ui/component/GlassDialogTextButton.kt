package top.yukonga.mishka.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonDefaults
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GlassDialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: GlassDialogButtonRole = GlassDialogButtonRole.Normal,
    insideMargin: PaddingValues = GlassDialogButtonDefaults.InsideMargin,
) {
    GlassDialogButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        role = role,
        insideMargin = insideMargin,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.button,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
