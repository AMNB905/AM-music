package com.am.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.am.music.data.update.UpdateInfo
import com.am.music.data.update.UpdateInstaller
import com.am.music.ui.theme.Motion
import com.am.music.util.formatBytes
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 云更新弹窗。
 *
 * 两种形态共用一套视觉（纸白、2dp 圆角、24dp 边距），只有按钮不同：
 * - **强制**：没有「稍后」，返回键也关不掉，只能更新；
 * - **普通**：可以「稍后」，24 小时内不再打扰（逻辑在 UpdateChecker 里）。
 *
 * 更新动作**全程在 App 内**：下载（带百分比进度）→ 交给系统安装器
 * （FileProvider 的 content:// + apk MIME），默认路径不再跳浏览器；
 * 只有系统安装器彻底打不开时才给一条「用浏览器下载」的兜底。
 */
@Composable
fun UpdateDialog(
    info: UpdateInfo,
    forced: Boolean,
    currentName: String,
    onFinish: () -> Unit,
    onLater: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun startDownload() {
        job?.cancel()
        phase = Phase.Downloading(percent = 0, downloaded = 0, total = 0)
        job = scope.launch {
            when (val result = UpdateInstaller.download(context, info) { percent, downloaded, total ->
                phase = Phase.Downloading(percent, downloaded, total)
            }) {
                is UpdateInstaller.Download.Done -> phase = Phase.Ready(result.file)
                is UpdateInstaller.Download.Failed -> phase = Phase.Failed(result.message)
            }
        }
    }

    fun handOff(file: File) {
        when (val result = UpdateInstaller.install(context, file)) {
            UpdateInstaller.Install.Launched -> phase = Phase.HandedOff
            UpdateInstaller.Install.NeedPermission -> phase = Phase.Ready(file, needPermission = true)
            is UpdateInstaller.Install.Failed -> phase = Phase.Failed(result.message)
        }
    }

    // 下载完、且有安装权限时直接拉起系统安装器：用户只需在系统弹窗里按一次「安装」
    LaunchedEffect(phase) {
        val ready = phase as? Phase.Ready ?: return@LaunchedEffect
        if (!ready.needPermission) handOff(ready.file)
    }

    // 弹窗销毁（取消/关闭）时把还在跑的下载取消掉
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    AmAlertDialog(
        onDismissRequest = {
            if (!forced) {
                job?.cancel()
                onLater?.invoke()
            }
        },
        title = {
            Text(
                text = if (forced) "需要更新后才能继续使用" else "有新版本 ${info.latestName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = if (forced) {
                        info.forceMessage.ifEmpty {
                            "旧版停用了，更新到 ${info.latestName}。"
                        }
                    } else {
                        "当前 $currentName · 最新 ${info.latestName}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (info.notes.isNotEmpty()) {
                    Text(
                        text = info.notes,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 待下载 / 下载中 / 下载完 / 已交给系统 / 失败：每换一个阶段淡换一次，
                // 进度线出现时也不把上面的说明顶一下
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 30)) +
                            slideInVertically(tween(260, easing = Motion.EmphasizedDecel)) { it / 20 }) togetherWith
                            fadeOut(tween(150))
                    },
                    label = "updatePhase",
                ) { current ->
                when (current) {
                    Phase.Idle -> Text(
                        text = "直接在这下、这装，不跳浏览器；数据不丢。",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is Phase.Downloading -> {
                        Text(
                            text = if (current.total > 0) {
                                "正在下载 ${current.percent}% · " +
                                    "${formatBytes(current.downloaded)}/${formatBytes(current.total)}"
                            } else {
                                "正在下载 ${formatBytes(current.downloaded)}…"
                            },
                            modifier = Modifier.padding(top = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (current.percent in 0..100) {
                            LinearProgressIndicator(
                                progress = { current.percent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }

                    is Phase.Ready -> Text(
                        text = "先去系统里允许装应用，回来点「已授权」。",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Phase.HandedOff -> Text(
                        text = "按系统提示装完，重开就是新版。",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is Phase.Failed -> Text(
                        text = "没更新成：${current.message}",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                }
            }
        },
        confirmButton = {
            when (val current = phase) {
                Phase.Idle -> DialogAction("下载并安装") { startDownload() }

                is Phase.Downloading -> DialogAction("下载中…", enabled = false) {}

                is Phase.Ready -> if (current.needPermission) {
                    DialogAction("去授权") { UpdateInstaller.openInstallPermission(context) }
                } else {
                    DialogAction("继续安装") { handOff(current.file) }
                }

                Phase.HandedOff -> DialogAction("关闭") { onFinish() }

                is Phase.Failed -> DialogAction("重试") { startDownload() }
            }
        },
        dismissButton = when {
            phase is Phase.Downloading -> {
                {
                    TextButton(onClick = {
                        job?.cancel()
                        phase = Phase.Idle
                    }) {
                        Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            (phase as? Phase.Ready)?.needPermission == true -> {
                {
                    val ready = phase as Phase.Ready
                    TextButton(onClick = { handOff(ready.file) }) {
                        Text("已授权，继续安装", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            phase is Phase.Failed -> {
                {
                    TextButton(onClick = { UpdateInstaller.openInBrowser(context, info) }) {
                        Text("用浏览器下载", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            phase is Phase.HandedOff -> {
                {
                    TextButton(onClick = onFinish) {
                        Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            onLater != null && !forced -> {
                {
                    TextButton(onClick = onLater) {
                        Text("稍后", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            else -> null
        },
    )
}

/** 弹窗内主按钮的统一排版（纯文字、墨黑，跟页面那套一致）。 */
@Composable
private fun DialogAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = label,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 弹窗内部的下载/安装阶段。 */
private sealed interface Phase {
    data object Idle : Phase

    data class Downloading(val percent: Int, val downloaded: Long, val total: Long) : Phase

    data class Ready(val file: File, val needPermission: Boolean = false) : Phase

    data class Failed(val message: String) : Phase

    data object HandedOff : Phase
}
