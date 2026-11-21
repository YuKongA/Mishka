package top.yukonga.mishka.ui.screen.settings

import androidx.compose.animation.CapturedAnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthReject
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.mishka.R
import top.yukonga.mishka.platform.PlatformStorage
import top.yukonga.mishka.platform.StorageKeys
import top.yukonga.mishka.ui.component.AdaptiveTopAppBar
import top.yukonga.mishka.ui.component.BackNavigationIcon
import top.yukonga.mishka.ui.component.CardItem
import top.yukonga.mishka.ui.component.GlassDialogTextButton
import top.yukonga.mishka.ui.component.blur.BlurredBar
import top.yukonga.mishka.ui.component.blur.LocalPageBackdrop
import top.yukonga.mishka.ui.component.blur.PageBackdropBox
import top.yukonga.mishka.ui.component.blur.rememberBlurBackdrop
import top.yukonga.mishka.ui.component.groupedCardItems
import top.yukonga.mishka.ui.component.overScrollVertical
import top.yukonga.mishka.ui.theme.FloatingBottomBarStyle
import top.yukonga.mishka.ui.theme.LocalAppDarkMode
import top.yukonga.mishka.ui.theme.MaxDensityScale
import top.yukonga.mishka.ui.theme.MinDensityScale
import top.yukonga.mishka.ui.theme.ThemeAccentColor
import top.yukonga.mishka.ui.theme.ThemeConfig
import top.yukonga.mishka.ui.theme.ThemePaletteStyles
import top.yukonga.mishka.ui.theme.TopBarBlurStyle
import top.yukonga.mishka.ui.theme.label
import top.yukonga.mishka.ui.theme.normalizeDensityScale
import top.yukonga.mishka.ui.theme.summary
import top.yukonga.mishka.ui.theme.writeThemeConfig
import top.yukonga.mishka.ui.util.horizontalCutoutPadding
import top.yukonga.mishka.ui.util.rememberIsWideScreen
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerInterceptionMode
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.roundToInt

@Composable
fun ThemeSettingsScreen(
    storage: PlatformStorage,
    themeConfig: ThemeConfig,
    onThemeConfigChange: (ThemeConfig) -> Unit,
    onPredictiveBackChange: ((Boolean) -> Unit)? = null,
    swipeDismissEnabled: Boolean = true,
    onSwipeDismissChange: ((Boolean) -> Unit)? = null,
    onBack: () -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    var isPredictiveBackEnabled by remember {
        mutableStateOf(storage.getString(StorageKeys.PREDICTIVE_BACK, "false") == "true")
    }
    var densityScaleDraft by remember { mutableFloatStateOf((themeConfig.densityScale * 100f).roundToInt().toFloat()) }
    var showDensityScaleDialog by remember { mutableStateOf(false) }
    val densityScaleTextState = rememberTextFieldState()

    LaunchedEffect(themeConfig.densityScale) {
        densityScaleDraft = (themeConfig.densityScale * 100f).roundToInt().toFloat()
    }

    fun updateTheme(next: ThemeConfig) {
        writeThemeConfig(storage, next)
        onThemeConfigChange(next)
    }

    fun updateDensityScale(percent: Float) {
        val nextPercent = percent.roundToInt().coerceIn(
            (MinDensityScale * 100f).roundToInt(),
            (MaxDensityScale * 100f).roundToInt(),
        )
        val nextScale = normalizeDensityScale(nextPercent / 100f)
        densityScaleDraft = nextScale * 100f
        updateTheme(themeConfig.copy(densityScale = nextScale))
    }

    fun openDensityScaleDialog() {
        densityScaleTextState.setTextAndPlaceCursorAtEnd(densityScaleDraft.roundToInt().toString())
        showDensityScaleDialog = true
    }

    val isSystem = themeConfig.colorMode == 0
    val isDark = LocalAppDarkMode.current
    val paletteStyles = ThemePaletteStyles
    val paletteItems = paletteStyles.map { style -> style.label() }
    val selectedPaletteIndex = paletteStyles.indexOf(themeConfig.paletteStyle).coerceAtLeast(0)
    val accentOptions = ThemeAccentColor.entries.toList()
    val accentItems = accentOptions.map { accent -> accent.label() }
    val selectedAccentIndex = accentOptions.indexOf(themeConfig.accentColor).coerceAtLeast(0)
    val floatingBottomBarStyles = FloatingBottomBarStyle.entries.toList()
    val floatingBottomBarStyleItems = floatingBottomBarStyles.map { style -> style.label() }
    val selectedFloatingBottomBarStyleIndex =
        floatingBottomBarStyles.indexOf(themeConfig.floatingBottomBarStyle).coerceAtLeast(0)
    val pagerInterceptionModes = PagerInterceptionMode.entries.toList()
    val pagerInterceptionModeItems = pagerInterceptionModes.map { mode -> mode.label() }
    val selectedPagerInterceptionModeIndex =
        pagerInterceptionModes.indexOf(themeConfig.pagerInterceptionMode).coerceAtLeast(0)
    val topBarBlurStyles = TopBarBlurStyle.entries.toList()
    val topBarBlurStyleItems = topBarBlurStyles.map { style -> style.label() }
    val topBarBlurStyleSummaries = topBarBlurStyles.map { style -> style.summary() }
    val selectedTopBarBlurStyleIndex = topBarBlurStyles.indexOf(themeConfig.topBarBlurStyle).coerceAtLeast(0)
    val isBlurSupported = isRuntimeShaderSupported()
    val isWideScreen = rememberIsWideScreen()

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null

    PageBackdropBox {
        Scaffold(
            topBar = {
                BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                    AdaptiveTopAppBar(
                        title = stringResource(R.string.settings_theme_title),
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
                item(key = "themePreview") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ThemeModePreview(
                            title = stringResource(R.string.settings_theme_light),
                            dark = false,
                            wide = isWideScreen,
                            isSelected = !isDark,
                            onClick = { updateTheme(themeConfig.copy(colorMode = 1)) },
                            modifier = Modifier.weight(1f),
                        )
                        ThemeModePreview(
                            title = stringResource(R.string.settings_theme_dark),
                            dark = true,
                            wide = isWideScreen,
                            isSelected = isDark,
                            onClick = { updateTheme(themeConfig.copy(colorMode = 2)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item(key = "followSystem") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = stringResource(R.string.settings_theme_system),
                            checked = isSystem,
                            onCheckedChange = { followSystem ->
                                val newMode = if (followSystem) 0 else if (isDark) 2 else 1
                                updateTheme(themeConfig.copy(colorMode = newMode))
                            },
                        )
                    }
                }

                item { SmallTitle(text = stringResource(R.string.settings_theme_group_color)) }
                groupedCardItems(
                    keyPrefix = "theme_color",
                    items = listOf(
                        CardItem("monet") {
                            SwitchPreference(
                                title = stringResource(R.string.settings_theme_monet),
                                summary = stringResource(R.string.settings_theme_monet_summary),
                                checked = themeConfig.useMonet,
                                onCheckedChange = { checked ->
                                    updateTheme(themeConfig.copy(useMonet = checked))
                                },
                            )
                            CapturedAnimatedVisibility(
                                visible = themeConfig.useMonet,
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                            ) {
                                Column {
                                    GlassDropdownPreference(
                                        title = stringResource(R.string.settings_theme_palette_style),
                                        backdrop = LocalPageBackdrop.current,
                                        summary = paletteItems.getOrElse(selectedPaletteIndex) { paletteItems.first() },
                                        items = paletteItems,
                                        selectedIndex = selectedPaletteIndex,
                                        onSelectedIndexChange = { index ->
                                            updateTheme(themeConfig.copy(paletteStyle = paletteStyles[index]))
                                        },
                                    )
                                    GlassDropdownPreference(
                                        title = stringResource(R.string.settings_theme_accent_title),
                                        backdrop = LocalPageBackdrop.current,
                                        summary = accentItems.getOrElse(selectedAccentIndex) { accentItems.first() },
                                        items = accentItems,
                                        selectedIndex = selectedAccentIndex,
                                        onSelectedIndexChange = { index ->
                                            updateTheme(themeConfig.copy(accentColor = accentOptions[index]))
                                        },
                                    )
                                    SwitchPreference(
                                        title = stringResource(R.string.settings_theme_pure_black),
                                        summary = stringResource(R.string.settings_theme_pure_black_summary),
                                        checked = themeConfig.pureBlack,
                                        onCheckedChange = { checked ->
                                            updateTheme(themeConfig.copy(pureBlack = checked))
                                        },
                                    )
                                }
                            }
                        },
                    ),
                )

                item { SmallTitle(text = stringResource(R.string.settings_theme_group_interface)) }
                groupedCardItems(
                    keyPrefix = "theme_interface",
                    items = buildList {
                        add(CardItem("blur") {
                            SwitchPreference(
                                title = stringResource(R.string.settings_theme_blur),
                                summary = stringResource(R.string.settings_theme_blur_summary),
                                checked = themeConfig.blurEnabled && isBlurSupported,
                                onCheckedChange = { checked ->
                                    updateTheme(themeConfig.copy(blurEnabled = checked))
                                },
                                enabled = isBlurSupported,
                            )
                            CapturedAnimatedVisibility(
                                visible = false,
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                            ) {
                                GlassDropdownPreference(
                                    title = stringResource(R.string.settings_theme_blur_style),
                                    backdrop = LocalPageBackdrop.current,
                                    summary = topBarBlurStyleSummaries.getOrElse(selectedTopBarBlurStyleIndex) {
                                        topBarBlurStyleSummaries.first()
                                    },
                                    items = topBarBlurStyleItems,
                                    selectedIndex = selectedTopBarBlurStyleIndex,
                                    onSelectedIndexChange = { index ->
                                        updateTheme(themeConfig.copy(topBarBlurStyle = topBarBlurStyles[index]))
                                    },
                                )
                            }
                        })
                        if (onPredictiveBackChange != null) {
                            add(CardItem("predictiveBack") {
                                SwitchPreference(
                                    title = stringResource(R.string.settings_predictive_back),
                                    summary = stringResource(R.string.settings_predictive_back_summary),
                                    checked = isPredictiveBackEnabled,
                                    onCheckedChange = { checked ->
                                        storage.putString(StorageKeys.PREDICTIVE_BACK, checked.toString())
                                        isPredictiveBackEnabled = checked
                                        onPredictiveBackChange(checked)
                                    },
                                )
                            })
                        }
                        add(CardItem("densityScale") {
                            ArrowPreference(
                                title = stringResource(R.string.settings_theme_density_scale),
                                summary = stringResource(R.string.settings_theme_density_scale_summary),
                                endActions = {
                                    Text(
                                        text = formatDensityScalePercent(densityScaleDraft),
                                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                                bottomAction = {
                                    Slider(
                                        value = densityScaleDraft.coerceIn(
                                            MinDensityScale * 100f,
                                            MaxDensityScale * 100f,
                                        ),
                                        onValueChange = { value ->
                                            densityScaleDraft = value
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        valueRange = (MinDensityScale * 100f)..(MaxDensityScale * 100f),
                                        onValueChangeFinished = {
                                            updateDensityScale(densityScaleDraft)
                                        },
                                        showKeyPoints = true,
                                        keyPoints = listOf(80f, 90f, 100f, 110f),
                                        // magnetThreshold 是 range 占比：0.01 = 1%，仅在关键点附近轻微吸附
                                        magnetThreshold = 0.01f,
                                        hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                                    )
                                },
                                onClick = ::openDensityScaleDialog,
                                holdDownState = showDensityScaleDialog,
                            )
                        })
                    },
                )

                item { SmallTitle(text = stringResource(R.string.settings_theme_group_navigation)) }
                groupedCardItems(
                    keyPrefix = "theme_navigation",
                    items = buildList {
                        add(CardItem("pagerGesture") {
                            GlassDropdownPreference(
                                title = stringResource(R.string.settings_theme_pager_gesture),
                                backdrop = LocalPageBackdrop.current,
                                summary = pagerInterceptionModeItems.getOrElse(
                                    selectedPagerInterceptionModeIndex,
                                ) { pagerInterceptionModeItems.first() },
                                items = pagerInterceptionModeItems,
                                selectedIndex = selectedPagerInterceptionModeIndex,
                                onSelectedIndexChange = { index ->
                                    updateTheme(
                                        themeConfig.copy(
                                            pagerInterceptionMode = pagerInterceptionModes[index],
                                        ),
                                    )
                                },
                            )
                        })
                        if (onSwipeDismissChange != null) {
                            add(CardItem("swipeDismiss") {
                                SwitchPreference(
                                    title = stringResource(R.string.settings_swipe_dismiss),
                                    summary = stringResource(R.string.settings_swipe_dismiss_summary),
                                    checked = swipeDismissEnabled,
                                    onCheckedChange = { checked -> onSwipeDismissChange(checked) },
                                )
                            })
                        }
                        if (!isWideScreen) {
                            add(CardItem("floatingStyle") {
                                GlassDropdownPreference(
                                    title = stringResource(R.string.settings_theme_floating_bottom_bar_style),
                                    backdrop = LocalPageBackdrop.current,
                                    summary = floatingBottomBarStyleItems.getOrElse(
                                        selectedFloatingBottomBarStyleIndex,
                                    ) { floatingBottomBarStyleItems.first() },
                                    items = floatingBottomBarStyleItems,
                                    selectedIndex = selectedFloatingBottomBarStyleIndex,
                                    onSelectedIndexChange = { index ->
                                        updateTheme(
                                            themeConfig.copy(
                                                floatingBottomBarStyle = floatingBottomBarStyles[index],
                                            ),
                                        )
                                    },
                                )
                            })
                        }
                    },
                )
                item {
                    Spacer(
                        Modifier
                            .height(24.dp)
                            .navigationBarsPadding()
                    )
                }

            }
        }

        DensityScaleDialog(
            show = showDensityScaleDialog,
            textState = densityScaleTextState,
            currentPercent = { densityScaleDraft },
            onDismiss = { showDensityScaleDialog = false },
            onConfirm = { percent ->
                updateDensityScale(percent)
                showDensityScaleDialog = false
            },
        )
    }
}

@Composable
private fun DensityScaleDialog(
    show: Boolean,
    textState: TextFieldState,
    currentPercent: () -> Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    GlassDialog(
        show = show,
        backdrop = LocalPageBackdrop.current,
        title = stringResource(R.string.settings_theme_density_scale),
        summary = stringResource(R.string.settings_theme_density_scale_summary),
        onDismissRequest = onDismiss,
    ) {
        GlassTextField(
            state = textState,
            modifier = Modifier.fillMaxWidth(),
            inputTransformation = DigitsOnlyTransformation.maxLengthReject(3),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                Text(
                    text = "%",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassDialogTextButton(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            GlassDialogTextButton(
                text = stringResource(R.string.common_confirm),
                modifier = Modifier.weight(1f),
                role = GlassDialogButtonRole.Primary,
                onClick = {
                    // 空输入/非法输入回退当前值，确认后总是关闭，不做静默 no-op
                    val percent = textState.text.toString().toIntOrNull()?.toFloat() ?: currentPercent()
                    onConfirm(percent)
                },
            )
        }
    }
}

@Composable
private fun ThemeModePreview(
    title: String,
    dark: Boolean,
    wide: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.semantics(mergeDescendants = true) {
            role = Role.RadioButton
            selected = isSelected
        },
        cornerRadius = 26.dp,
        insideMargin = PaddingValues(vertical = 6.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = onClick,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .squircleBorder(
                    width = 3.dp,
                    color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                    cornerRadius = 26.dp,
                )
                .padding(6.dp),
        ) {
            ThemeModeIllustration(dark = dark, wide = wide)
        }
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceSecondary,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ThemeModeIllustration(
    dark: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (wide) 2f else 1f)
            .squircleClip(20.dp),
    ) {
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF406EFF), Color(0xFF55B9FA))),
        )
        val illustrationWidth = size.width * if (wide) 0.7f else 1f
        val start = (size.width - illustrationWidth) / 2f
        val unitX = illustrationWidth / 680f
        val unitY = size.height / 680f
        val cornerUnit = minOf(unitX, unitY)
        val panelColor = if (dark) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.2f)
        val lineColor = if (dark) Color.White.copy(alpha = 0.35f) else Color(0xFF285ABC).copy(alpha = 0.6f)
        for (top in listOf(148f, 356f)) {
            drawRoundRect(
                color = panelColor,
                topLeft = Offset(start + 48f * unitX, top * unitY),
                size = Size(584f * unitX, 176f * unitY),
                cornerRadius = CornerRadius(44f * cornerUnit),
            )
            for ((offset, width) in listOf(52f to 460f, 100f to 312f)) {
                drawRoundRect(
                    color = lineColor,
                    topLeft = Offset(start + 112f * unitX, (top + offset) * unitY),
                    size = Size(width * unitX, 24f * unitY),
                    cornerRadius = CornerRadius(12f * cornerUnit),
                )
            }
        }
    }
}

// 仅允许数字输入，非数字编辑整体回退
private val DigitsOnlyTransformation = InputTransformation {
    if (!asCharSequence().all { it.isDigit() }) revertAllChanges()
}

private fun formatDensityScalePercent(value: Float): String = "${value.roundToInt()}%"
