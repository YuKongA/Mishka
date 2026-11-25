# Miuix 0.9.5 Glass 迁移指南

面向 Compose + miuix 应用的通用迁移参考，涵盖组件替换、采样架构与关键踩坑约束。

---

## 1. 依赖引入

在 `libs.versions.toml` 中引入：

- `miuix-glass`：顶栏、底栏、侧边栏、按钮、标签栏、弹窗与抽屉等 Glass 组件。
- `miuix-glass-icons`：`MiuixIcons.Glass.*` 图标集（与 `GlassIconButton` 搭配，默认 `28.dp`）。
- `miuix-blur`：`LayerBackdrop`、`rememberLayerBackdrop`、`layerBackdrop`、`isRuntimeShaderSupported`。

---

## 2. 背景采样与降级

### 2.1 不透明底色与隔离原则
- 录制 `backdrop` 时必须先绘制不透明底色再 `drawContent()`，避免列表透明区域采样出黑底或残影：
  ```kotlin
  val surfaceColor = MiuixTheme.colorScheme.surface
  val backdrop = rememberLayerBackdrop {
      drawRect(surfaceColor)
      drawContent()
  }
  ```
- **禁止自采样**：被采样的 `layerBackdrop` 内绝不能包含消费它的玻璃组件本身。

### 2.2 两级采样宿主
- **页面局部 `backdrop`**：仅录制正文列表，供 `GlassTopAppBar` / `GlassNavigationBar` 采样；`GlassTopAppBar` 开启 `progressiveBlur` 时外层不要再叠高斯模糊。
- **整页 `PageBackdropBox`（`LocalPageBackdrop`）**：包在 Miuix 根 `Scaffold` 最外层（含正文与顶底栏），专供 `GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference`、`GlassIconDropdownMenu` 等浮层采样；**子页面 `Scaffold` 必须传 `popupHost = {}`**，否则弹窗会落入 `pageBackdrop` 内触发自采样闪退。
- **宿主依赖**：`GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference` 依赖 Miuix `Scaffold`，不可脱离其使用。
### 2.3 无模糊降级
- 必须以 `isRuntimeShaderSupported()` 门控模糊；不支持或用户关闭时，将 `backdrop` 置 `null`（组件自动回退纯色底），并将 `GlassSegmentedTabRow` 等的 `surfaceAlpha` 设为 `0f`。注意 `Modifier.glass(..., shading = false)` 会忽略 `tint`（需通过 `GlassMaterial` 的 `GlassColorLayer` 上色），且只要传入非空 `backdrop`，即使 `material = null`、`fill = Color.Transparent` 仍会模糊绘制 `backdrop` 背景。

---

## 3. 组件替换清单

| 旧组件 | 新组件 | 关键注意事项 |
| --- | --- | --- |
| `TopAppBar` / `SmallTopAppBar` | `GlassTopAppBar` | 父容器禁 `clipToBounds()`；列表加 `overScrollVertical` + `overscrollEffect = null`；`bottomContent` 含 `GlassSegmentedTabRow` 时顶部不加重复 `Spacer`，细节见 3.1 |
| `NavigationBar` | `GlassNavigationBar` | 图标用 `MiuixIcons.Glass.*`；无模糊时 `backdrop = null` |
| `IosLiquidGlassNavigationBar` | 补水平安全区 inset | 外层 `BoxWithConstraints` 加 `displayCutout ∪ navigationBars` 水平 inset |
| `NavigationRail` | `GlassNavigationRail` | 见第 4 节 |
| `IconButton` / `MiuixIcons.Back` | `GlassIconButton` + `MiuixIcons.Glass.*` | 图标默认 `28.dp`（超大水印自建矢量路径） |
| `WindowListPopup` | `GlassIconDropdownMenu` / `GlassTransformPopup` | 锚点加 `.glassPopupAnchor(anchor, cornerRadius)`，内部 `Icon` **必须**加 `.glassPopupAnchorContent(anchor)` |
| `TabRow` | `GlassSegmentedTabRow` | 传 `backdrop` 与 `surfaceAlpha`；置顶栏 `bottomContent` 或列表内时静止态为 `0f`（`if (blurActive && contentUnderTopBar) 1f else 0f`） |
| `WindowDialog` | `GlassDialog` | 传 `backdrop = LocalPageBackdrop.current`；按钮换 `GlassDialogButton`（`Normal` / `Primary`） |
| `WindowBottomSheet` | `GlassBottomSheet` | 传 `backdrop = LocalPageBackdrop.current`；支持 `progressiveBlur`（默认开）与 `scrollOffset`，内容需消费 `innerPadding: PaddingValues`（如 `.padding(innerPadding)` 或列表 `contentPadding`）以避让顶栏 |
| `TextField`（`GlassDialog` 内） | `GlassTextField` | 内置玻璃材质与动态取色适配的半透明底色与标签配色 |
| `Card`（玻璃浮层/动效背景内） | `GlassCard` | 统一半透明填充与动态取色对比度 |
| `SearchBar` / `SearchPager` | `GlassSearchOverlay` | 配合 `rememberGlassSearchBarAlpha` 与 `glassSearchBarTransition`，见第 7 节 |

> **圆角与材质**：所有 `miuix-glass` 组件默认已内置 `GlassShape`，无需额外传 `RoundedCornerShape` 或覆盖 `shape`。

### 3.1 顶栏与折叠栏细节
- **水平边距与阴影**：`GlassTopAppBar` 左右按钮内边距基准为 `GlassTopAppBarDefaults.HorizontalPadding`（`12.dp`），自定义顶栏勿沿用 `16.dp` 或叠加 `TitlePadding`（`26.dp`）。
- **自定义可折叠内容顶栏**：渐进模糊带须固定测为 `topInset + CollapsedHeight + BandOverhang`（禁 `.matchParentSize()`）；头图在顶栏背后时，按钮玻璃态以 `collapsedFraction` 线性门控（如 `((collapsedFraction - 0.5f) / 0.5f).coerceIn(0f, 1f) * buttonAlpha`），防快速回弹时残留白底。
- **折叠态主操作按钮**：可用 `GlassFloatingActionButtonDefaults.material()` 保持主题色，刚收起时维持紧凑高度，正文滚入后再随 `GlassTopAppBarDefaults.ButtonSize`（`44.dp`）插值增高并启用描边阴影。

---

## 4. 宽屏侧边栏（`GlassNavigationRail`）

### 4.1 持久化层级与跨层弹窗
将 `GlassNavigationRail` 放在 `NavDisplay` **外层 `Box`** 中，避免进入二级页面时被遮挡。自下而上层级：
1. `Box(Modifier.layerBackdrop(sidebarBackdrop)) { NavDisplay(...) }`
2. `GlassNavigationRail(...)`
3. `TopLayerHost(topLayer)`（页面内的 `GlassIconDropdownMenu` 等内联弹窗无论宽窄屏均需通过 `TopLayer { ... }` 提升至 `pageBackdrop` 之外的此层，否则会被外置侧边栏盖住或触发自采样闪退）

导航行为：
- 二级页将 `selectedIndex` 设为 `-1`；从二级页切 Tab 时先开 0ms `InstantNavTransition`（短暂延迟后复位）并 `popUntil { it is Route.Main }`。
- 仅全屏全宽显示保留 `rememberNavSystemCornerRadius()`；`isInMultiWindowMode()` 或存在分栏时将 `NavDisplayEffects(cornerClipRadius = 0.dp)` 置零。

### 4.2 全屏背景延伸与内容避让
- **勿**在 `NavDisplay` 外层用 `.layout` 裁剪或右移，否则背景与顶栏模糊被硬截断；`NavDisplay` 保持 `fillMaxSize()`，通过 `CompositionLocal` 下发动态值：
  - `LocalTopBarStartInset`：`{ (GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth) * railState.progress }`
  - `LocalTopAppBarTitleAlpha`：`{ railState.progress }`（折叠胶囊居中于顶栏上方时淡出标题；刚过宽屏阈值时右侧按钮勿过多）
  - `LocalTopAppBarCenterObstructionStart`：用 `compositionLocalOf`（而非 `staticCompositionLocalOf`）免全树重组；折叠态侧栏提供 `railState.collapsedStart` 给顶栏 `centerObstructionStart`，主 Tab 内覆为 `Dp.Unspecified`
- 顶栏（`contentModifier`）、正文与全屏覆盖层各自应用起始缩进，实现「背景全屏、内容避让」。

---

## 5. 工程红线 Checklist

1. **`layerBackdrop` 先于 `inset` / `padding`**：紧跟 `.fillMaxSize()`，位于 `windowInsetsPadding`、`horizontalCutoutPadding()`、`sidebarStartInset()` **之前**，否则横竖屏或侧栏动效时顶栏模糊矩形截断与错位。
2. **禁止组合期读帧率级状态**：`railState.progress`、`scrollBehavior.state.contentOffset` 等严禁 `by` 解包或直接传 `Modifier.padding(dp)`，一律以 lambda 透传至 `layout` / `graphicsLayer` / `draw` / `titleAlpha` 读取。
3. **避免起始缩进叠加**：顶栏内部已消费 `LocalTopBarStartInset` 时，外部 `contentModifier` 需关闭侧栏缩进。
4. **滚动阈值防抖**：`isContentScrolled` 用 `contentOffset < -1f || overscrollOffset < -1f`（而非 `< 0f`），防回弹边缘抖动并保证短页上拉越界时顶栏材质正常浮起；显式传 `listState.canScrollBackward` 时同样叠加 `overscrollOffset < -1f`。

---

## 6. Compose 1.13.0 与渲染约束

- **API 迁移**：`derivedStateOf` → `computedStateOf`；`AnimatedVisibility` → `CapturedAnimatedVisibility`；输入框改用 `TextFieldState`（`rememberTextFieldState`、`TextFieldLineLimits.SingleLine`、`onKeyboardAction`、`decorator`）及 `maxLengthReject` / `maxLengthTrim`。
- **容器尺寸基准**：根部 `BoxWithConstraints(Modifier.fillMaxSize())` 注入 `LocalContainerSize`，宽屏与视口计算优先读它，再回退 `LocalWindowInfo.current.containerSize`。
- **列表重组与防漏缝**：`LazyColumn` 静态多行卡拆为 `groupedCardItems`；自定义分段卡片封装（如 `CardSegment`）在 `!isLast && bottomCornerRadius == 0.dp && color.alpha == 1f` 时向下 `drawBehind` 延伸 2px，防 0.5x 降采样产生抗锯齿黑缝；`LocalTopAppBarTitleAlpha` / `LocalTopBarStartInset` lambda 以 `remember(railState)` 或顶层常量稳定引用。
- **顶栏渐进模糊驱动**：非折叠态保留 100ms 线性过渡，折叠态随 `collapsedFraction` 推进；`overscrollOffset < -1f` 直接驱动按钮悬浮材质及非折叠态渐进模糊带。

---

## 7. 顶栏搜索栏与程序化滚动

### 7.1 顶栏触发的 `GlassSearchOverlay`
使用 `GlassSearchOverlay`、`GlassSearchOverlayDefaults`、`rememberGlassSearchBarAlpha` 与 `glassSearchBarTransition`：
- **宿主与顶栏分离动画**：`Scaffold.modifier` 仅挂 `graphicsLayer { alpha = barAlpha.value }`（`searchExpanded` 时追加 `clearAndSetSemantics { }`）；`glassSearchBarTransition(searchExpanded, blurEnabled) { 1f }` **只挂到 `AdaptiveTopAppBar.contentModifier`**，免整页离屏模糊。
- **列表与渐进模糊顶带**：外提 `searchListState = rememberLazyListState()`，按首项索引与偏移计算 `scrollOffset` 并设 `scrollMaterialThreshold = GlassSearchOverlayDefaults.VerticalPadding + 6.dp` 驱动胶囊材质与渐进模糊顶带；传 `topInsets`（`systemBars ∪ captionBar ∪ displayCutout` 顶部）、`searchTopPadding`、`horizontalInsetsModifier` 与 `MiuixIcons.Glass.*`。结果 `LazyColumn` 用匿名 `PaddingValues` 延迟计算顶部让位、加水平安全区与 `overscrollEffect = null`，`LaunchedEffect(query)` 变动时回顶。
- **常驻玻璃搜索栏（非全屏 Overlay）**：若需在顶栏 `bottomContent` 保留常驻搜索框，采用与 `ScrollSearchPillBackground` 相同的双层叠加——底层常驻纯色圆角底与 `drawGlassShadow`，上层按 `GlassMotion.topBarButtonFloat()` 驱动 `graphicsLayer { alpha = glassAlpha }.glass(...)` 平滑淡入淡出，避免直接切换 Modifier 造成硬切。

### 7.2 程序化滚动与顶栏模糊激活
- `animateScrollToItem` 不经 `NestedScrollConnection`，不更新 `contentOffset` 与 `heightOffset`。
- 存在自动跟底/跳转滚动时，需显式传 `isContentScrolled = listState.canScrollBackward`，并在 `canScrollBackward && heightOffset > heightOffsetLimit` 时通过 `animate(...)` 折叠 `heightOffset` 至 `heightOffsetLimit`（清空列表时复位 `0f`），否则移动端 `GlassTopAppBar` 渐进模糊带不激活。

---

## 8. 关于页（`AboutPage` 范式）

- **卡片用 `GlassCard`**：分组卡片移除 `textureBlur` 与手写混色，改用 `GlassCard` 透出底层动效（Hero 图标/标题可保留 `textureBlur(..., BlendMode.DstIn)`）。
- **透明顶栏与阈值显隐**：固定 `expandable = false`，始终向 `GlassTopAppBar` 传入 `backdrop`（仅将 `backgroundAlpha` 门控在 `collapsed`：`{ if (collapsed) 1f else 0f }`，切勿将 `backdrop` 本身门控在 `collapsed`，否则滚动到位前左上角按钮会退化为纯白实心圆），并传 `isContentScrolled = listState.canScrollBackward`；以 `computedStateOf { scrollProgress >= 0.45f }` 驱动 `animateFloatAsState(..., tween(200))` 淡入淡出标题（`AdaptiveTopAppBar` 兼顾非玻璃分支时传 `titleColor`，纯 `GlassTopAppBar` 时传 `titleAlpha = { titleAlpha.value }`）。
- **浮层采样与 `logoSpacer`**：页内含 `GlassBottomSheet` / `GlassDropdownPreference` 时用 `rememberCombinedBackdrop(backdrop, barBackdrop)` 供浮层采样（无浮层则单 `backdrop`）；`logoSpacer` 高度需扣除列表顶部 `contentPadding`，可用单遍 `Layout` 先测 Hero 再测列表消首帧跳变。
- **背景动效门控与越界**：自定义动态背景以 `alpha = { 1f - scrollProgress() }` 在绘制阶段门控帧循环（`<= 0f` 时停 `withFrameNanos`），协程侧色阶动画配合 `snapshotFlow { alpha() > 0f }.first { it }` 挂起；正文 `LazyColumn` 设 `overscrollEffect = null`。

---

## 9. 构建变更

- 升级 Compose Multiplatform `1.13.0-rc01` 后，`compose.desktop.currentOs` 已废弃，需显式替换为 `org.jetbrains.compose.ui:ui` 与 `org.jetbrains.compose.foundation:foundation`。
