# Miuix 0.9.5-beta01 Glass 通用迁移指南

本文档面向基于 Compose + miuix 的应用，提供从传统 miuix 组件迁移到 **miuix 0.9.5-beta01 Glass** 设计体系的通用方案、架构要点与常见踩坑清单。

---

## 1. 依赖配置

在 `libs.versions.toml` 中引入 Glass 相关模块：

- `miuix-glass`：`GlassTopAppBar`、`GlassNavigationBar`、`GlassNavigationRail`、`GlassIconButton`、`GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference`、`GlassSegmentedTabRow`、`GlassIconDropdownMenu` / `GlassTransformPopup` 等。
- `miuix-glass-icons`：`MiuixIcons.Glass.*`（如 `HomeOutline`、`SettingsOutline`、`ChevronBackward`、`More`、`Search` 等），建议搭配 `GlassIconButton` 使用（图标尺寸通常为 `28.dp`）。
- `miuix-blur`：`LayerBackdrop`、`rememberLayerBackdrop`、`layerBackdrop`、`isRuntimeShaderSupported` 等背景采样与实时模糊原语。

---

## 2. 背景采样与模糊基础

### 2.1 不透明底色录制
- 创建 `backdrop` 时必须先绘制不透明背景再 `drawContent()`，否则列表透明区域会导致玻璃表面采样到透明/黑底或产生叠加伪影：
  ```kotlin
  val surfaceColor = MiuixTheme.colorScheme.surface
  val backdrop = rememberLayerBackdrop {
      drawRect(surfaceColor)
      drawContent()
  }
  ```
- **严格隔离玻璃表面自身**：被采样的 `layerBackdrop` 中绝不能包含消费它的玻璃组件本身，否则会产生递归采样反馈循环。

### 2.2 分级采样宿主（`PageBackdropBox`）
- 页面内部通常需要两层采样源：
  1. **页面局部 `backdrop`**：仅录制正文列表，供页面自己的 `GlassTopAppBar` / `GlassNavigationBar` 采样；当 `GlassTopAppBar` 开启 `progressiveBlur` 时外层不要再叠一层高斯模糊，否则双重模糊发灰。
  2. **整页 `PageBackdropBox`（`LocalPageBackdrop`）**：包在页面 `Scaffold` 最外层（包含正文 + 顶底栏），专供 `GlassDialog`、`GlassBottomSheet`、`GlassDropdownPreference`、`GlassIconDropdownMenu` 等全屏浮层采样。
- **Miuix `Scaffold` 依赖**：`GlassDialog`、`GlassBottomSheet` 与 `GlassDropdownPreference` 依赖 Miuix `Scaffold` 提供的弹窗宿主，不能放在完全脱离 Miuix `Scaffold` 的根容器中。

### 2.3 设备兼容与无模糊降级
- `RuntimeShader` 仅在受支持设备（如 Android 13+）可用，启用模糊前必须检查 `isRuntimeShaderSupported()`。
- 当硬件不支持或用户关闭模糊时，让 `rememberBlurBackdrop()` 返回 `null`：
  - `GlassTopAppBar` / `GlassNavigationBar` / `GlassNavigationRail` 收到 `backdrop = null` 时自动回退到纯色填充。
  - `GlassSegmentedTabRow` 等组件应同步将 `surfaceAlpha` 降为 `0f` 或使用纯色底，避免半透明底色无模糊时文字透底发花。

---

## 3. 顶栏与底栏迁移

### 3.1 顶栏（`GlassTopAppBar`）
- 使用 `GlassTopAppBar` 替代原版 `TopAppBar` / `SmallTopAppBar`：
  - 传入 `backdrop`、`progressiveBlur`、`bandBrush = GlassTopAppBarDefaults.bandBrush(surface)`、`bandOverhang = GlassTopAppBarDefaults.BandOverhang`。
  - 返回按钮与操作按钮统一使用 `GlassIconButton` + `MiuixIcons.Glass.*`。
- **禁止裁剪溢出**：`bandOverhang` 背景带、按钮阴影（`GlassShadows.Regular`）与折叠标题模糊均向布局边界外绘制，**不要**在顶栏或其直接父容器上加 `clipToBounds()`。
- **弹性越界联动**：配合 `overScrollVertical` 时，需将 `overScrollVertical(onOffsetChanged = { scrollBehavior.state.overscrollOffset = it })` 放在 `.nestedScroll(...)` 之前（或同时传 `isContentScrolled = listState.canScrollBackward`），确保越界回弹时按钮与背景带材质同步响应。
- **分段标签间距**：若 `bottomContent` 中包含 `GlassSegmentedTabRow`（通常自带底部外边距），正文列表首项不要重复叠加顶部 `Spacer`，否则会出现双倍空白。

### 3.2 窄屏底栏（`GlassNavigationBar`）
- 使用 `GlassNavigationBar` 替代固定底栏 `NavigationBar`，图标统一切换为 `MiuixIcons.Glass.*`。
- 若同时支持 iOS 液态玻璃底栏等自定义方案，建议统一图标集与显示语义，避免风格割裂。

---

## 4. 宽屏侧边栏（`GlassNavigationRail`）架构

### 4.1 持久化层级与跨层弹窗（`TopLayer`）
- **外置防遮挡**：**不要**把 `GlassNavigationRail` 放在 `NavDisplay` 的主页面（`Route.Main`）内部，否则进入二级页面时侧边栏会被新页面盖住。
- **推荐层级结构**（自下而上）：
  1. `Box(Modifier.layerBackdrop(sidebarBackdrop))` 包裹 `NavDisplay`（录制全应用界面供侧边栏毛玻璃采样）。
  2. `GlassNavigationRail`（同级置于 `NavDisplay` 上方）。
  3. `TopLayerHost(topLayer)`（同级置于最顶层）。
- **`TopLayer` 提升弹窗**：`GlassIconDropdownMenu` / `GlassTransformPopup` 默认在页面树内按窗口坐标绘制，会位于 `NavDisplay` 内部从而被外置 `GlassNavigationRail` 遮挡。因此页面内的弹出菜单需通过 `TopLayer { ... }` 提升到 `TopLayerHost` 渲染。
- **二级页导航交互**：
  - 处于非主页面且不在侧栏直达项中时，将 `selectedIndex` 设为 `-1`（避免误高亮且允许点击当前所属 Tab 项回到主页）。
  - 从二级页点击侧栏切换回主 Tab 时，配合 0ms 无动画过渡 `InstantNavTransition`（在短暂延迟后复位）执行 `popUntil { it is Route.Main }`，再切页，避免出现退场滑移动画与切页动画打架。
  - 宽屏下可将 `NavDisplayEffects(cornerClipRadius = 0.dp)` 置零，避免多窗格过渡裁剪出设备圆角黑边。

### 4.2 全屏背景延伸与内容动态缩进
- **不要**在 `NavDisplay` 外层容器上使用 `.layout { ... }` 裁剪或右移整个容器——这会把所有页面的背景、渐变与顶栏模糊在侧边栏处硬截断。
- **正确做法**：
  - `NavDisplay` 保持 `Modifier.fillMaxSize()` 全屏铺满。
  - 通过 `CompositionLocal` 下发侧边栏动态缩进与折叠标题透明度：
    - `LocalTopBarStartInset`：`{ (GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth) * railState.progress }`
    - `LocalTopAppBarTitleAlpha`：`{ railState.progress }`
  - 顶栏通过 `contentModifier` 应用起始缩进（`GlassTopAppBar` 背景仍全宽绘制）；列表正文与全屏覆盖层（如 `GlassSearchOverlay`）各自在内部应用起始缩进。
- **折叠态顶部避让**：`GlassNavigationRail` 折叠后居中悬浮于顶栏正上方。除设置 `titleAlpha = { railState.progress }` 隐藏被盖住的标题外，在刚达宽屏阈值（如 600dp 左右）的窄窗格下，顶栏右侧操作按钮不宜过多，以免与居中的折叠胶囊重叠。

---

## 5. 弹出菜单变形动画

- 使用 `GlassIconDropdownMenu` / `GlassTransformPopup` 时，锚点按钮必须成对设置修饰符：
  1. 按钮容器：`GlassIconButton(modifier = Modifier.glassPopupAnchor(anchor, cornerRadius = GlassTopAppBarDefaults.ButtonSize / 2))`
  2. 按钮图标：`Icon(modifier = Modifier.size(28.dp).glassPopupAnchorContent(anchor))`
- **常见错误**：漏掉 `glassPopupAnchorContent(anchor)` 会导致展开/收起变形动画期间原图标未隐藏，并在按钮左上角产生残影与位置偏移。

---

## 6. 关键工程红线 Checklist

1. **`layerBackdrop` 必须先于 `inset` / `padding`（模糊错位根因）**：
   - `Modifier.layerBackdrop(backdrop)` **必须**位于 `windowInsetsPadding`、`horizontalCutoutPadding()`、`sidebarStartInset()` **之前**（紧跟 `.fillMaxSize()`）。
   - 若置于 `inset` 之后，录制的 `GraphicsLayer` 尺寸会被缩窄且起点向右偏移；而 `GlassTopAppBar` 从 `(0, 0)` 全宽采样，横竖屏切换或侧边栏展开/收起时必然出现矩形截断与模糊错位。
2. **禁止在组合期读取每帧变化的状态**：
   - `railState.progress`、`scrollBehavior.state.contentOffset` 等帧率级数值**严禁**在组合期用 `by` 解包或直接传给 `Modifier.padding(dp)`，否则动画/滚动期间整页每帧重组。
   - 必须以 lambda（`() -> Float` / `() -> Dp`）透传至 `layout { ... }`、`graphicsLayer { ... }`、`titleAlpha` 或绘制阶段读取。
3. **防止起始缩进重复叠加**：
   - 若 `AdaptiveTopAppBar` 内部已读取 `LocalTopBarStartInset`，外部传入的 `contentModifier`（如同时需要处理刘海安全区时）必须关闭侧栏缩进，避免顶栏内容偏移两倍。
