# 全应用审计（0.6.99）

一次把「缺什么、逻辑哪里不对、哪些重复了」摊开过一遍。下面只写结论和处置。

## 一、已修的重复实现

| 位置 | 问题 | 处置 |
| --- | --- | --- |
| `ui/online/OnlineMusicScreen.kt` / `ui/local/LocalMusicScreen.kt` | 两个私有搜索框逐行近似复制，只有回车行为不同 | 合并为 `ui/components/SearchField.kt`，`onSearch = null` 表示边输边筛 |
| `ui/settings/SettingsScreen.kt` | `Chip` 是 internal，别的包想用拿不到 | 迁到 `ui/components/Chip.kt`，顺手补上按下反馈 |
| `ui/settings/SettingsScreen.kt` | 定时关闭弹窗只有设置页能开 | 迁到 `ui/components/SleepTimerDialog.kt`，播放页 ⋯ 菜单同源复用 |
| `ui/player/PlayerScreen.kt` | ⋯ 菜单里「播放队列」与播放条右侧队列图标重复 | 删菜单项，只留图标入口 |

## 二、已补的功能缺口

- **倍速播放**：`PlaybackState.speed` + `PlaybackManager.setSpeed / cycleSpeed`（0.75 / 1.0 / 1.25 / 1.5 / 2.0 五档轮转），写入 `playback_settings` 的 `speed`，重连与切歌都保持。入口在播放页 ⋯ 菜单，显示当前档位。
- **播放页定时关闭入口**：睡前不用退出播放页去翻设置，和设置页是同一个弹窗、同一份 `SleepTimer`。

## 三、查过但确认没问题的地方

- **列表项动画**：在线 / 本地 / 榜单 / 歌单详情 / 队列 / 喜欢 / 最近都用了 `animateItem`，没有漏的。
- **本地页排序**：`添加顺序 / 歌名 / 艺人 / 时长` 四档，选择会被记住。
- **歌单重命名 / 删除**：都在歌单页 ⋯ 里，不是缺口。
- **队列单曲移除、下一首播放插队**：`QueueScreen` 与 `SongActionsDialog` 都有。
- **队列推进**：`next()` 环绕、`previous()` 3 秒内回到本首、`onWindowEnded()` 按播放模式分流，三条路径都有终态，不会卡死。
- **播放页回封面**：手势（下滑 >96dp 或轻点空白），切换按钮已删，不需要再加。

## 四、验证过但不打算做的方向

- **搜索历史**：属于新增数据模型 + 存储 + UI 三层，收益不如先把上面这些对齐，留到后面单独一轮。
- **本地批量全选**：本地页按歌单选 / 全选下载已经能做，批量删除不属于高频路径。
- **通知栏进度条细粒度刷新**：系统媒体通知自身有刷新节流，再压频率反而会让进度跳格。

## 五、仍然待拆的大文件（见 ARCHITECTURE.md）

`DynamicIslandService`(1356 行) / `SettingsScreen`(约 740 行，本轮减 48 行) / `PlaybackManager`(约 828 行，本轮加 24 行)。

## 0.7.3 崩溃隐患复查

延续 0.7.2「列表 key 重复」那类，把同一族（key 冲突 / 索引越界 / 区间反转）全量扫了一遍：

| 位置 | 隐患 | 处理 |
| --- | --- | --- |
| `MusicApi.parseToplist` | 榜单行用 `song.key` 当 LazyColumn 行 key，上游返回同名曲两次即崩 | 解析时就 `distinctBy { it.key }` |
| `MusicApi.parseHotQq / parseHotNetease` | 热门搜索用词本身当 key，重复热词会撞 key | `distinct()` |
| `PlaylistDetailViewModel.moveSong` | `add(to, removeAt(from))` 无越界检查，拖动手势的旧索引可能超过当前长度 | 先校验 `from / to`，`to` 再 `coerceIn(0, size)` |
| `PlaybackManager.moveInQueue` | `to` 只允许 `indices`，`playNext` 在「当前是最后一首」时插到队尾被静默丢弃（不崩但功能哑） | 放开为 `0..size`，插入时再收敛 |
| `DynamicIslandService.clampIntoScreen` | 分屏/小窗下视图比屏幕还宽时 `coerceIn(0, sw - vw)` 区间反转 → IllegalArgumentException | 两端 `coerceAtLeast` 兜底 |
| `FloatingLyricsService` 恢复 y 坐标 | 同上，`screenHeight - 60dp` 区间可能反转 | `coerceAtLeast(0)` |

查过、确认没问题的：`playContext` 空列表早返回；`previous/next` 前有 `isEmpty` 判断；`removeFromQueue / dropVirtual` 都先 `index !in virtual.indices` 返回；`PlaybackSnapshotter.trim` 的 `subList` 起止都夹过；`RecentScreen` 的 key 依赖 `RecentSongEntity`（`@PrimaryKey songId`）天然去重；`PendingIntent` 带 `FLAG_IMMUTABLE`；前台服务是 media3 `MediaSessionService` + `mediaPlayback` 类型（清单已声明）。

## 0.7.4 崩溃现场可定位 + 空列表防护

崩溃栈被混淆后只剩「崩了」，不知道「在哪儿崩的」。这一版补两件事：

1. **崩溃面包屑**：`last_crash.txt` 头部新增 `页面: <路由> · <最后动作>`；路由由 NavHost 的 back stack 实时写入，关键动作（点播放条队列胶囊、队列自动定位、切榜单）记一条备注。下次截图就能直接对上页面。
2. **Index 0 out of bounds for length 0 这类的空列表防护**：
   - 每日推荐 `HorizontalPager`：`pageCount = 0` 的 PagerState 在布局期会去取第 0 页 → 空列表直接不组 pager，页数至少 1，自动翻页 `runCatching` 兜住
   - 频谱条 `onDraw`：`w / bars.size`，空数组时除零 → 先判空返回
   - 歌词：空句（LRC 空行）不预排版、不计算扫光区（`TextLayout` 对空串没有行）

## 0.7.5 榜单页 Index 0 out of bounds 的修法

崩溃面包屑定位到 `ranking · 榜单 QQ音乐·飙升榜`，栈是 `ArrayList.get(0)`（长度 0）在重组帧里。
榜单页里唯一「按下标去取另一个数组」的写法，是 0.7.3 加的行 key 兜底：

```kotlin
val rankKeys = remember(songs) { songs.stableKeys { it.key } }
itemsIndexed(songs, key = { i, _ -> rankKeys[i] })
```

key 数组和曲目列表是两份数据，只靠下标对齐；LazyList 的 prefetch / 复用一旦拿着
上一份快照的下标来问 key，`rankKeys[0]` 就撞进一个空数组。修法是把二者绑成一条记录
（`List.keyedRows()`，返回 `List<Pair<String, T>>`），取行 key 时永远取行自己的，
结构上不存在跨列表取值。队列页、歌单详情页同样换掉。

## 0.7.6 歌词过渡 / 歌单骨架 / 封面懒加载

- **歌词换歌过渡**：`PlayerLyricsStage` 的 Loading / NotFound / Ready 三态外包一层 `AnimatedContent`，
  `contentKey` 用「状态 + 当前曲目 key」——换歌才会重放（淡出 170ms，新内容从下方 1/8 处淡入，
  撞上 `Motion.SWAP`），同一首歌里歌词行变化不重放，不会整块闪。
- **歌单页骨架**：`PlaylistsUiState.isLoading` 默认 true，第一次读到库就置 false；这段时间铺 6 块
  `PosterSkeleton`（方版封面 + 两行字，入场节奏与真格子一致），不再先闪一屏「还没歌单」。
- **封面懒加载**：`AlbumArt` 的小缩略图（<120dp）不再为每一行预下载原图（`CoverCache` 只认已缓存），
  没缓存交给 Coil 现场按显示尺寸拉；同时 `ImageRequest.size(px, px)` + `memoryCacheKey`，
  解码只到显示尺寸（×2 高分屏），滚动时省内存也不卡。

## 0.7.7 歌单内搜索

- 入口：歌单页顶栏新增搜索按钮（有歌才出现），展开后变成「关闭」；搜索条从标题栏下 `expandVertically + fadeIn` 展开，收起反向。
- 交互：展开 80ms 后自动要焦点弹键盘；边打边筛，120ms 防抖 + `Dispatchers.Default` 后台算，700 首不卡输入。
- 动画：筛选结果用原有的 `animateItem`（位移动画 + 280ms 淡入 / 170ms 淡出）；计数行 `AnimatedContent` 淡换；无结果换成 `EmptyState`。
- 加载动画：后台筛选期间一条 1dp 细线扫过（占位固定，只做 alpha 进出，不会把列表顶一下）；歌单首次读库仍是骨架 + 提示。
- 视觉对齐：复用全 App 唯一的 `SearchField`（24dp 页边距、2dp 圆角、surfaceVariant 55% 底），与在线页 / 本地页完全同款。
- 搜索态行为：拖动手柄隐藏（筛选下标 ≠ 歌单真实位置，避免拖错）；点结果按「筛选结果」起播，前后首即结果相邻曲。
- 测试：`ui/playlists/PlaylistSearch.kt` 只放纯函数（`searchTokens` / `matchesTokens` / `filterSongs`），
  `app/src/test/.../PlaylistSearchTest.kt` 8 个用例覆盖空查询、歌名/歌手/专辑、大小写、多关键词 AND、
  多余空格、全角空格、无结果；`gradle :app:testReleaseUnitTest` 全绿（20 tests, 0 fail）。

## 0.7.8 动效补齐（弹窗 / 识曲 / 状态置换 / 图片落位）

先做体检再做补：全仓扫了一遍 `AnimatedContent / Crossfade / animateItem / animate*AsState`，
页面转场、队列拖动、播放页封面与歌词、底部浮岛这些早就成型的没有动，
只补真正缺的，并且抽两个共用件避免各写一套。

**新增共用件**
- `ui/components/PhaseTransition.kt`
  - `PhaseTransition`：加载中 / 空 / 有内容 / 失败 四态之间统一置换——新内容淡入 + 从下方推 1/14 屏，旧内容更快收走，高度变化走 `Motion.settle()`，列表是「长」出来的。
  - `FadingImage`：图片解码完再淡入（300ms）+ 0.97 → 1 的轻缩放。全 App 的封面 / 头像 / 相册缩略图共用一份落位手感。

**逐模块**
- 歌单墙：骨架块接 `animateItem(fadeOutSpec)`，读库完成时淡出而不是被真格子顶掉；`PosterSkeleton` 加 `modifier` 参数。
- 本地：权限 / 扫库 / 空 / 列表 四态走 `PhaseTransition`（原来 `when` 硬切，扫完整屏闪一下）。
- 歌单详情、添加歌曲：同上；添加行首次出现带 `animateItem` 淡入。
- 队列：底部「回到正在播」改为真的从底部顶出 / 退回（`pillAlpha` + `pillLift` 两条 `animateFloatAsState`，隐藏时禁用点击）；空队列 ↔ 列表 `PhaseTransition`；顶部「空 / N 首」`AnimatedContent` 淡换。
- 小播放器：`12/700` 序号改「翻牌」——旧数字上滑走、新数字下滑来，总数不动。
- 识曲：录音外圈按真实秒数平滑生长（`animateFloatAsState` 900ms）；录音中真实呼吸光圈（停止即熄）；阶段文字与秒数 `AnimatedContent`；录音块 / 结果块 / 多结果块换场。
- 弹窗：下载（每档状态文字淡换、进度线撑开/收回、进度插值 420ms）；应用更新（各阶段淡换）；相似歌曲 / 歌曲资料（骨架 → 内容置换，结果行 `animateItem`）。
- `Chip`：底色 / 字色 200ms 渐变 + 选中轻弹。
- 封面选择器：修掉「查相册时先闪一句『相册里没有图片』」——初始值改 `null` 表加载态，补骨架；缩略图走 `FadingImage`。
- 在线页：连接状态圆点在「检测中」缓慢呼吸（真实探测状态，非装饰），「检测中 → 已连接」淡换；每日推荐 / 歌单卡封面 `FadingImage`。
- 歌词页衬底、设置页作者头像：`FadingImage`。
- `SongItem`：正在播那首的标题颜色 260ms 渐变（原来一帧换色）。
- 收藏页回执条：`AnimatedVisibility` 压开 / 收拢。

**验证**
- `:app:compileReleaseKotlin` 通过；`gradle :app:testReleaseUnitTest` 20 tests / 0 failures。
- 签名指纹 `33a8bef9…`，`aapt2` 核对 `versionCode=121 / versionName=0.7.8`。

**审查结论（与 0.7.8 并行）**
- 另起一份全 App 体验审查（8 模块 135 条），一页结论里 P0 是：列表 key 去 `@$i` 下标、时间/进度/计数改插值、
  四态置换、弹窗浮层动画、灵动岛频谱接真实振幅。0.7.8 已覆盖「四态置换」与部分弹窗 / 图片落位；
  其余按补丁分批合入，不在这一版。
