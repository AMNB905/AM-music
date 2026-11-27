# AMMusic 工程结构约定

面向「接手的人能在十分钟内找到东西在哪」而写。不是理论文档，是这个仓库的实际规矩。

## 一句话分层

```
data/       →  数据的唯一来源（网络、磁盘、数据库、缓存）     不认识 UI
playback/   →  播放状态机（队列、补料、恢复）                  不认识 UI，只吐 StateFlow
service/    →  常驻的 Android 组件（前台服务、悬浮窗、通知）
ui/<feature>/ → 一个页面一个包：Screen（画）+ ViewModel（算）
ui/components/ → 跨页面复用的纯展示组件（不知道业务）
ui/theme/、ui/navigation/、util/ → 横切关注点
```

依赖方向只能是 `ui → playback → data`，反向不允许。`data` 层不许 import `androidx.compose.*`。

## 页面包内的固定命名

| 文件 | 职责 |
| --- | --- |
| `<Feature>Screen.kt` | 编排：收集状态、决定布局顺序，不放具体绘制细节 |
| `<Feature>Stage.kt` / `<Feature>Panel.kt` | 页面里的一块「舞台」，自己管手势与动画 |
| `<Feature>ViewModel.kt` | 状态与动作；不写任何绘制代码 |
| `<Feature>Parts.kt` | 同页面内的小组件，多个时才拆 |

同一页面的拆法以「这块能不能独立存在」为准：能单独描述的一块（封面舞台、歌词舞台、传输条）就单独一个文件。

## 文件体量预算

- **单文件 400 行为上限**，超过就说明混了两件以上的事。
- 600 行以上必须在本文档的「待拆清单」里登记，并在拆完后移除。
- 一个文件只允许一个「主角」（一个 Screen / 一个类 / 一个舞台）。

## 当前拆分情况（0.6.94）

已完成：

- `ui/player/` 原先 774 行的单文件拆成 5 个：
  - `PlayerScreen.kt` — 编排 + 顶栏
  - `PlayerCoverStage.kt` — 封面、换歌手势、双击收藏
  - `PlayerLyricsStage.kt` — 歌词三态、回封面手势
  - `PlayerTransportBar.kt` — 进度条、时间、控制键、标题区
  - `ClearScreenPlayer.kt` — 清屏模式
- `ui/navigation/AppNavGraph.kt` — 全部路由与转场从根布局里独立出来；
  `ui/AMMusicRoot.kt` 只保留壳层（权限、系统栏、浮岛、启动弹窗、新手引导）。
- `data/onboarding/` + `ui/onboarding/` — 新手引导按「数据（看过没）+ 展示（遮罩层）」两层分开。

待拆清单（按收益排序）：

1. `service/DynamicIslandService.kt`（约 1360 行）
   目标拆法：`IslandOverlayViewFactory`（视图树构建）、`IslandBackdrop`（图片/视频背景，含 ExoPlayer 生命周期）、
   `IslandGeometry`（定位、宽高、圆角换算）、`DynamicIslandService` 只留生命周期与状态同步。
2. `playback/PlaybackManager.kt`（约 890 行）
   目标拆法：`PlaybackQueue`（virtual + window 两个坐标系）、`QueueFiller`（补料与直链缓存）、
   `PlaybackPersistence`（落盘与恢复）。状态机本身保留在 manager。
3. `ui/settings/SettingsScreen.kt`（约 790 行）
   目标拆法：按已存在的四组（播放 / 外观 / 存储 / 关于）各拆一个 `SettingsGroup` 文件，
   Screen 只负责拼装与滚动。

## 约定细则

- **状态只有一份**：播放相关的都在 `PlaybackManager.state`，页面不许自己再维护一份「当前歌」。
- **手势写在使用它的那一块里**（Stage），不要提到 Screen；Screen 只传回调。
- **动画常量集中在 `ui/theme/Motion`**，单页自定义的曲线放在该 Stage 文件顶部。
- **没有「按钮」能替代手势时就不要加按钮**：能用手势表达的（回封面、切歌、排序）用手势，
  但要给一次性的提示胶囊（3 秒淡出），不常驻。
- **每个 page 的数据入口只有一个**：`AppContainer` 注入，不接受静态单例。

## 交互反馈（0.6.97 起）

所有「能点的地方」都走同一套 `ui/theme/Motion.kt` 里的规格，不再各页各写：

| 档位 | 用法 | 手感 |
| --- | --- | --- |
| 行 / 卡片 | `pressFeedback(scale = 1f, dim = 0.05f)` | 整行泛一层淡色，文字不抖 |
| 图标 / 小按钮 | `pressFeedback(scale = 0.9f)` | 缩一下再弹回 |
| 主操作 | `pressFeedback(scale = 0.94f, haptic = true)` | 缩放 + 一下轻震 |

约束：只观察按下 / 抬起，一帧都不消费事件，`clickable` / 长按 / 拖动照常工作；
震动只在「主操作」上用（切 tab、播放、识曲、点小播放器、调色点），列表行不震。

## 视觉对齐（0.6.98 起）

页面的「骨骼」统一收在 `ui/theme/Layout.kt`，不再各页写 dp：

| 项 | 值 | 说明 |
| --- | --- | --- |
| `Layout.Gutter` | 24dp | 页面左右留白：标题、歌曲行、骨架、空态、网格都从这条线开始 |
| `Layout.HeaderTop` / `HeaderBottom` | 20dp / 6dp | 标题块上下留白 |
| `Layout.HeaderEnd` | 16dp | 标题右侧给按钮留的位置 |
| `Layout.RowVertical` | 9dp | 歌曲行上下留白，封面一律 48dp |
| `Layout.ArtRadius` | 2dp | 封面小图 / 网格缩略图 / 输入框圆角 |
| `Layout.SurfaceRadius` | 20dp | 浮起来的表面（浮岛、卡片） |

标题字号：所有页面（含二级页）一律 `headlineMedium` + `primary`；
返回型页面标题用 `titleMedium` 只允许出现在对话框与面板里。
空态：统一用 `components/EmptyState`（标题 + 说明 + 12dp 浮入），不再写裸 Text。
