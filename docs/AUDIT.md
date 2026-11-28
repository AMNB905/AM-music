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
