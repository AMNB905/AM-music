package com.am.music.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.am.music.AMMusicApp
import com.am.music.MainActivity
import com.am.music.data.island.FloatingLyricsPrefs
import com.am.music.data.model.LyricLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮歌词：一层系统级悬浮窗，浮在任何 App 上面，跟着播放逐行滚。
 *
 * 为什么是系统悬浮窗而不是应用内：歌词的价值就在「退出播放页之后还看得见」——
 * 刷微博、看消息时仍然知道现在唱到哪一句。代价是必须开「显示在其他应用上层」。
 *
 * 交互只有三条，不做面板，不做按钮：
 * - 单指拖动：整块跟着走，松手记住位置（下次原样出现）
 * - 点一下：回 App 的播放页
 * - 长按：关掉（等同于在设置里关开关）
 *
 * 视觉上刻意不用卡片、不用阴影，只有一行半透明底色 + 两行字：
 * 当前行亮白，下一行 60% 白。压在别的内容上时，越轻越好，不能像弹窗那样抢注意力。
 */
class FloatingLyricsService : Service() {

    private lateinit var windowManager: WindowManager
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var currentLineView: TextView? = null
    private var nextLineView: TextView? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    /** 当前歌 + 已解析好的歌词；换歌才重新读。 */
    private var loadedKey: String? = null
    private var lines: List<LyricLine> = emptyList()
    private var calibrationMs = 0L
    private var lastIndex = Int.MIN_VALUE

    /** 用户主动关（长按 / 开关）才落偏好；被系统回收时不能落，否则下次回 App 不会自启。 */
    private var userStop = false

    // 动效状态：内容有没有（shown）、暂停降不降透明（pausedDim）、当前已生效的目标透明度
    private var shown = false
    private var pausedDim = false
    private var targetAlpha = -1f
    private var density = 1f

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isRunning = true
        if (!canDrawOverlays(this)) {
            stopSelf()
            return
        }
        showOverlay()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userStop = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                rebuildOverlay()
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        root?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        root = null
        // 只有用户主动关才落偏好：被系统回收时保留开关，回 App 后由 MainActivity 补起
        if (userStop) FloatingLyricsPrefs.setEnabled(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 窗口

    private fun rebuildOverlay() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        showOverlay()
    }

    private fun showOverlay() {
        if (root != null) return
        density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        // 没有底色：只有字。压在别人 App 上时最轻的一层存在，不遮内容。
        val textColor = FloatingLyricsPrefs.color(this)
        val nextColor = (textColor and 0x00FFFFFF) or (0x99 shl 24)

        val current = TextView(this).apply {
            setTextColor(textColor)
            textSize = 17f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            // 没有底板就只能靠阴影托住：白色字压在浅色壁纸上也能读，溢出一点点的阴影比色块干净
            setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            text = "正在准备歌词…"
        }
        val next = TextView(this).apply {
            setTextColor(nextColor)
            textSize = 12.5f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, (2 * density).toInt(), 0, 0)
            setShadowLayer(7f, 0f, 2f, 0xCC000000.toInt())
        }

        // 锁定：整块窗不参与触摸（FLAG_NOT_TOUCHABLE），点按/滑动直接落到下面的 App，位置也定死
        val locked = FloatingLyricsPrefs.isLocked(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addView(current)
            addView(next)
            // 一开始是收起来的：等 update() 确认有歌，再淡入
            alpha = 0f
            visibility = View.INVISIBLE
        }

        // 宽度定死（屏宽 - 左右各 20dp），让长句稳定在一行半以内，
        // 而不是随内容忽宽忽窄——压在别人 App 上时，形状稳比内容紧更重要。
        val widthPx = screenWidth - (40 * density).toInt()
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        val lp = WindowManager.LayoutParams(
            widthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = FloatingLyricsPrefs.x(this@FloatingLyricsService)
            val savedY = FloatingLyricsPrefs.y(this@FloatingLyricsService)
            x = if (savedX >= 0) savedX.coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0)) else (20 * density).toInt()
            y = if (savedY >= 0) savedY.coerceIn(0, (screenHeight - (60 * density).toInt()).coerceAtLeast(0)) else (screenHeight * 3 / 5)
        }

        // 锁定就不挂手势：窗口本身收不到触摸，挂上也是死代码
        if (!locked) attachGestures(container, lp)

        runCatching { windowManager.addView(container, lp) }
            .onFailure { stopSelf() }

        root = container
        params = lp
        currentLineView = current
        nextLineView = next
        // 重建（改色、切锁定）后如果本来在显示，就直接接上，不要再从头淡一遍
        targetAlpha = if (shown) 1f else 0f
        if (shown) {
            container.alpha = if (pausedDim) 0.62f else 1f
            container.visibility = View.VISIBLE
        }
    }

    /**
     * 拖动 + 单击 + 长按，全部挂在同一个 View 上：
     * 位移超过一点点就认定是拖，取消单击判定；按住不动 600ms 就是长按。
     */
    private fun attachGestures(view: View, lp: WindowManager.LayoutParams) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                openApp()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                Toast.makeText(this@FloatingLyricsService, "已关闭悬浮歌词", Toast.LENGTH_SHORT).show()
                userStop = true
                stopSelf()
            }
        })

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        FloatingLyricsPrefs.setPosition(this, lp.x, lp.y)
                    }
                }
            }
            true
        }
    }

    private fun openApp() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    // ------------------------------------------------------------------ 内容

    private fun update() {
        val container = root ?: return
        val state = app().container.playbackManager.state.value
        val song = state.currentSong
        if (song == null) {
            // 没歌就淡出，不留一块透明但仍在吃触摸的窗
            shown = false
            pausedDim = false
            syncAlpha()
            return
        }
        shown = true
        pausedDim = !state.isPlaying
        syncAlpha()

        // 只认「上次加载的是这首歌」：id 相同即视为同一首，重播也复用
        if (song.key != loadedKey) {
            loadLyrics(song.key, song.id)
        }

        if (lines.isEmpty()) {
            setLineAnimated(currentLineView, song.title, 4f)
            setLineAnimated(nextLineView, "没有歌词", 3f)
            return
        }

        val position = state.positionMs + calibrationMs
        val index = lines.indexOfLast { it.timeMs <= position }.coerceAtLeast(0)
        if (index != lastIndex) {
            lastIndex = index
            // 换行不是瞬切：旧字上移淡出，新字从下方回落淡入，眼睛跟得住
            setLineAnimated(currentLineView, lines[index].text, 4f)
            setLineAnimated(nextLineView, lines.getOrNull(index + 1)?.text ?: "", 3f)
        }
    }

    /**
     * 单行换字：120ms 上移淡出 → 换文本 → 220ms 回落淡入。
     * 用 View 动画而不是重排两个 TextView，是因为这块窗只有两行、内容固定，
     * 让它像一块滚动字幕而不是一个会闪的标签。
     */
    private fun setLineAnimated(view: TextView?, text: String, riseDp: Float) {
        if (view == null) return
        if (view.text?.toString() == text) return
        val rise = riseDp * density
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(-rise)
            .setDuration(120)
            .withEndAction {
                view.text = text
                view.translationY = rise
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * 整块淡入 / 淡出 / 暂停降档。
     * 目标透明度 0 时动画结束才设 INVISIBLE——透明但可见的 View 仍然会吃掉触摸，
     * 而这块窗默认是可交互的（未锁定时），必须真的收起来。
     */
    private fun syncAlpha() {
        val container = root ?: return
        val target = when {
            !shown -> 0f
            pausedDim -> 0.62f
            else -> 1f
        }
        if (target == targetAlpha) return
        targetAlpha = target
        container.animate().cancel()
        if (target > 0f) {
            container.visibility = View.VISIBLE
            container.animate()
                .alpha(target)
                .setDuration(260)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            container.animate()
                .alpha(0f)
                .setDuration(240)
                .withEndAction {
                    if (targetAlpha <= 0f) container.visibility = View.INVISIBLE
                }
                .start()
        }
    }

    private fun loadLyrics(key: String, songId: String) {
        loadedKey = key
        lines = emptyList()
        lastIndex = Int.MIN_VALUE
        setLineAnimated(currentLineView, "正在加载歌词…", 4f)
        setLineAnimated(nextLineView, "", 3f)
        scope.launch {
            val repo = app().container.lyricsRepository
            val loaded = repo.readCache(key)
            val offset = repo.calibration(songId)
            withContext(Dispatchers.Main) {
                // 加载期间用户可能已经切歌，过期的结果直接丢
                if (loadedKey != key) return@withContext
                lines = loaded.orEmpty()
                calibrationMs = offset
                lastIndex = Int.MIN_VALUE
            }
        }
    }

    private fun app(): AMMusicApp = application as AMMusicApp

    companion object {
        const val ACTION_STOP = "com.am.music.floating_lyrics.STOP"
        const val ACTION_REFRESH = "com.am.music.floating_lyrics.REFRESH"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /** 开关：开→记偏好 + 起服务；关→停服务（服务的 onDestroy 会自己记关闭）。 */
        fun setEnabled(context: Context, enabled: Boolean) {
            FloatingLyricsPrefs.setEnabled(context, enabled)
            val intent = Intent(context, FloatingLyricsService::class.java)
            if (enabled) {
                runCatching { context.startService(intent) }
            } else {
                runCatching { context.stopService(intent) }
            }
        }

        /** 锁定 / 解锁：改窗的触摸属性要整窗重建，所以走 ACTION_REFRESH。 */
        fun setLocked(context: Context, locked: Boolean) {
            FloatingLyricsPrefs.setLocked(context, locked)
            refresh(context)
        }

        fun refresh(context: Context) {
            if (!isRunning) return
            runCatching {
                context.startService(
                    Intent(context, FloatingLyricsService::class.java).setAction(ACTION_REFRESH)
                )
            }
        }
    }
}



