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
- **整页 `PageBackdropBox`（`LocalPageBackdrop`）**：包在 Miuix `Scaffold` 最外层（含正文与顶底栏），专供 `GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference`、`GlassIconDropdownMenu` 等浮层采样。
- **宿主依赖**：`GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference` 依赖 Miuix `Scaffold`，不可脱离其使用。

### 2.3 无模糊降级
- 必须以 `isRuntimeShaderSupported()` 门控模糊；不支持或用户关闭时，将 `backdrop` 置 `null`（组件自动回退纯色底），并将 `GlassSegmentedTabRow` 等的 `surfaceAlpha` 设为 `0f`。

---

## 3. 组件替换清单

| 旧组件 | 新组件 | 关键注意事项 |
| --- | --- | --- |
| `TopAppBar` / `SmallTopAppBar` | `GlassTopAppBar` | 父容器禁止 `clipToBounds()`（会切掉 `bandOverhang` 渐隐带与阴影）；配合 `overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })` 置于 `.nestedScroll(...)` 前，列表需同时设 `overscrollEffect = null` 防重复越界；`bottomContent` 含 `GlassSegmentedTabRow` 时列表顶部不加重复 `Spacer` |
| `NavigationBar` | `GlassNavigationBar` | 图标切换为 `MiuixIcons.Glass.*`；未开模糊时 `backdrop = null` |
| `IosLiquidGlassNavigationBar` | 补水平安全区 inset | 外层 `BoxWithConstraints` 增加 `WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)` |
| `NavigationRail` | `GlassNavigationRail` | 见第 4 节 |
| `IconButton` / `MiuixIcons.Back` | `GlassIconButton` + `MiuixIcons.Glass.*` | 图标尺寸 `28.dp` |
| `WindowListPopup` | `GlassIconDropdownMenu` / `GlassTransformPopup` | 锚点按钮加 `.glassPopupAnchor(anchor)`，内部 `Icon` **必须**加 `.glassPopupAnchorContent(anchor)`，否则变形动画时原图标不隐藏且错位 |
| `TabRow` | `GlassSegmentedTabRow` | 传入 `backdrop` 与 `surfaceAlpha`；置于顶栏 `bottomContent` 时静止态设为 `0f`（`if (blurActive && contentUnderTopBar) 1f else 0f`） |
| `WindowDialog` | `GlassDialog` | 传入 `backdrop = LocalPageBackdrop.current`；内部按钮同步换为 `GlassDialogButton`（`Normal` / `Primary`） |
| `WindowBottomSheet` | `GlassBottomSheet` | 传入 `backdrop = LocalPageBackdrop.current` |
| `TextField`（`GlassDialog` 内） | `GlassTextField` | 与 `GlassDialogButton` 共用 `onBackground` 半透明底色体系 |
| `Card`（`GlassDialog` / `GlassBottomSheet` / 动效背景内） | `GlassCard` | 统一半透明填充与动态取色对比度 |
| `SearchBar` / `SearchPager` | `GlassSearchOverlay` | 配合 `rememberGlassSearchBarAlpha` 与 `glassSearchBarTransition` 使用，见第 8 节 |

> **圆角与材质**：所有 `miuix-glass` 组件默认已内置 `GlassShape`，无需额外传 `RoundedCornerShape` 或覆盖 `shape`。

---

## 4. 宽屏侧边栏（`GlassNavigationRail`）

### 4.1 持久化层级与跨层弹窗
将 `GlassNavigationRail` 放在 `NavDisplay` **外层 `Box`** 中，避免进入二级页面时被遮挡。自下而上层级：
1. `Box(Modifier.layerBackdrop(sidebarBackdrop)) { NavDisplay(...) }`
2. `GlassNavigationRail(...)`
3. `TopLayerHost(topLayer)`（页面内的 `GlassIconDropdownMenu` 等窗口坐标弹窗需通过 `TopLayer { ... }` 提升至此层，否则会被外置侧边栏盖住）

导航行为：
- 二级页面期间将 `selectedIndex` 设为 `-1`。
- 从二级页点击侧边栏切换 Tab 时，先启用 0ms 无动画过渡 `InstantNavTransition`（短暂延迟后复位）并 `popUntil { it is Route.Main }`。
- 仅在全屏全宽显示时保留 `rememberNavSystemCornerRadius()`；多窗口模式（`isInMultiWindowMode()`）或存在分栏（如双栏内嵌子栈）时将 `NavDisplayEffects(cornerClipRadius = 0.dp)` 置零。

### 4.2 全屏背景延伸与内容避让
- **不要**在 `NavDisplay` 外层用 `.layout` 裁剪或右移容器，否则背景与顶栏模糊会被硬截断。
- `NavDisplay` 保持 `Modifier.fillMaxSize()` 全屏铺满，通过 `CompositionLocal` 下发动态值：
  - `LocalTopBarStartInset`：`{ (GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth) * railState.progress }`
  - `LocalTopAppBarTitleAlpha`：`{ railState.progress }`（折叠态胶囊居中于顶栏上方时淡出标题；刚过宽屏阈值时顶栏右侧按钮不宜过多，以免与胶囊重叠）
  - `LocalTopAppBarCenterObstructionStart`：使用 `compositionLocalOf`（而非 `staticCompositionLocalOf`）以免折叠态胶囊变化触发全树重组；折叠态侧边栏（`!railState.expanded && collapsedWidth > 0.dp`）提供 `railState.collapsedStart` 给 `GlassTopAppBar` / `TopAppBar` 的 `centerObstructionStart`，主 Tab（`Route.Main`）内覆盖为 `Dp.Unspecified`
- 顶栏通过 `contentModifier`、正文与全屏覆盖层（如 `GlassSearchOverlay`）各自应用起始缩进，实现「背景全屏、内容避让」。

---

## 5. 工程红线 Checklist

1. **`layerBackdrop` 必须先于 `inset` / `padding`**：
   `Modifier.layerBackdrop(backdrop)` 必须放在 `windowInsetsPadding`、`horizontalCutoutPadding()`、`sidebarStartInset()` **之前**（紧跟 `.fillMaxSize()`）。否则录制层尺寸被缩窄并右偏，导致横竖屏切换或侧边栏展开/收起时顶栏模糊错位与矩形截断。
2. **禁止组合期读取帧率级状态**：
   `railState.progress`、`scrollBehavior.state.contentOffset` 等每帧变化的值严禁在组合期用 `by` 解包或直接传给 `Modifier.padding(dp)`，必须以 lambda 透传至 `layout` / `graphicsLayer` / `draw` / `titleAlpha` 阶段读取。
3. **避免起始缩进重复叠加**：
   顶栏内部已消费 `LocalTopBarStartInset` 时，外部传入的 `contentModifier`（如同时处理刘海安全区）需关闭侧栏缩进，防止双倍偏移。
4. **滚动阈值防抖**：
   判断 `isContentScrolled` 时使用 `scrollBehavior.state.contentOffset < -1f || scrollBehavior.state.overscrollOffset < -1f`（而非 `< 0f`），避免亚像素或回弹边缘抖动误触发，同时保证页面不足一页向上越界时顶栏模糊与按钮材质能正常浮起；即使显式传入 `listState.canScrollBackward` 也应叠加 `overscrollOffset < -1f`。

---

## 6. Compose 1.13.0 与渲染约束

- **状态与动画新 API**：`derivedStateOf` 迁移至 `computedStateOf`；`AnimatedVisibility` 迁移至 `CapturedAnimatedVisibility`。
- **输入框状态化**：`TextField` / `BasicTextField` 统一使用 `TextFieldState`（`rememberTextFieldState`、`TextFieldLineLimits.SingleLine`、`onKeyboardAction`、`decorator`）；字符长度限制用 `maxLengthReject` / `maxLengthTrim` 替代已废弃的 `maxLength`。
- **容器尺寸基准**：根部用 `BoxWithConstraints(Modifier.fillMaxSize())` 注入 `LocalContainerSize`，宽屏与视口计算优先读取它再回退 `LocalWindowInfo.current.containerSize`。
- **分段卡片防漏缝**：自绘分段卡片（`CardSegment`）在 `!isLast && bottomCornerRadius == 0.dp && color.alpha == 1f` 时向下 `drawBehind` 延伸 2px，避免 0.5x 降采样 RenderNode 光栅化产生抗锯齿黑缝。
- **顶栏透明带与越界驱动**：`GlassTopAppBar` 在非折叠态保留 100ms 线性过渡，折叠态随 `collapsedFraction` 推进渐进模糊带；`overscrollOffset < -1f` 直接驱动按钮悬浮材质及非折叠态渐进模糊带。
- **`GlassSegmentedTabRow` 静止不透明度**：列表内或未滚动的顶栏 `bottomContent` 中应保持 `surfaceAlpha = 0f`，滚动/越界后再切至 `1f`，避免静止态误显遮罩。
- **侧栏与路由重组优化**：`LocalTopAppBarCenterObstructionStart` 使用 `compositionLocalOf`，传入 `LocalTopAppBarTitleAlpha` 与 `LocalTopBarStartInset` 的 lambda 需以 `remember(railState)` / 顶层常量稳定引用；`LazyColumn` 中的多行静态偏好卡统一拆分为 `groupedCardItems`（按行 item）。
- **图标、弹窗与卡片**：全量使用 `MiuixIcons.Glass.*`（原生 `28.dp`，超大水印图标建议自建矢量路径）；`GlassDialog` 内输入框使用 `GlassTextField`；玻璃表面内的分组卡片统一使用 `GlassCard`；`NavDisplayEffects(cornerClipRadius)` 仅在全屏全宽显示时保留 `rememberNavSystemCornerRadius()`，在 `isInMultiWindowMode()` 或存在分栏时置 `0.dp`。

---

## 7. 顶栏搜索栏与程序化滚动

### 7.1 顶栏触发的 `GlassSearchOverlay`
从 `miuix-glass` 直接使用 `GlassSearchOverlay`、`GlassSearchOverlayDefaults`、`rememberGlassSearchBarAlpha` 与 `glassSearchBarTransition`：
- **宿主与顶栏分离动画**：
  - `Scaffold.modifier` 仅挂 `graphicsLayer { alpha = barAlpha.value }`，并在 `searchExpanded` 时追加 `clearAndSetSemantics { }` 屏蔽无障碍穿透。
  - `glassSearchBarTransition(expanded = searchExpanded, blurEnabled = blurEnabled) { 1f }` **只挂到 `AdaptiveTopAppBar.contentModifier`**，避免整页列表被离屏录制模糊。
- **结果列表与渐进模糊顶带**：
  - 将 `searchListState = rememberLazyListState()` 提至 `GlassSearchOverlay` 外层，通过 `scrollOffset`（无条目 `0f`、`firstVisibleItemIndex > 0` 取 `Float.MAX_VALUE`、否则 `firstVisibleItemScrollOffset.toFloat()`）与 `scrollMaterialThreshold = GlassSearchOverlayDefaults.VerticalPadding + 6.dp` 驱动搜索胶囊与取消按钮在结果滚至栏下时从实底过渡为玻璃态，并开启渐进模糊顶带。
  - 传入 `topInsets`（`systemBars ∪ captionBar ∪ displayCutout` 顶部）、`searchTopPadding = LocalTopBarExtraTopInset.current`、`horizontalInsetsModifier = Modifier.horizontalCutoutPadding()`、`blurEnabled = LocalBlurEnabled.current` 与 `MiuixIcons.Glass.*` 图标。
  - 结果 `LazyColumn` 用匿名 `PaddingValues` 延迟计算顶部让位 `topInsets + searchTopPadding() + BarHeight + VerticalPadding * 2`（避开组合期读取帧率级 inset），同步加 `.horizontalCutoutPadding()` 与 `overscrollEffect = null`，并在 `LaunchedEffect(query)` 变动时将列表滚回顶部。

### 7.2 程序化滚动与顶栏模糊激活
- `LazyListState.animateScrollToItem` 不经过 `NestedScrollConnection`，不会更新 `scrollBehavior.state.contentOffset` 与 `heightOffset`。
- 若页面存在自动跟随/跳转滚动（如日志流贴底），需显式传 `isContentScrolled = listState.canScrollBackward` 给 `AdaptiveTopAppBar`，并在 `canScrollBackward` 为真且 `heightOffset > heightOffsetLimit` 时通过 `animate(...)` 将 `scrollBehavior.state.heightOffset` 折叠至 `heightOffsetLimit`（清空列表时复位 `0f`），否则手机端 `GlassTopAppBar` 的渐进模糊带不会激活。

---

## 8. 关于页（`AboutPage` / `AboutScreen`）

- **卡片去 `textureBlur` 改用 `GlassCard`**：不再对关于页分组卡片套用卡片级 `textureBlur` 与手写 `ColorBlendToken`，直接放置 `GlassCard` 透出底层 `BgEffectBackground` 动效，降低离屏模糊开销。
- **标题分段显隐**：以 `showTitle by remember { computedStateOf { scrollProgress >= 0.45f } }` 触发 `animateFloatAsState(if (showTitle) 1f else 0f, tween(200))` 控制顶栏标题淡入淡出，替代按滚动比例逐帧线性插值；`titleAlpha` 仍以 lambda `{ titleAlpha.value }` 透传至绘制阶段读取。
- **背景动效门控与列表越界**：`BgEffectBackground` 通过 `alpha = { 1f - scrollProgress }` 在绘制阶段判断可见性，首屏完全滚出后自动停止帧循环与色阶推进；正文 `LazyColumn` 设 `overscrollEffect = null` 避免与 `overScrollVertical` 重复越界。
