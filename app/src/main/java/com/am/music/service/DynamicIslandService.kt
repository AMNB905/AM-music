package com.am.music.service

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.compose.ui.unit.dp
import com.am.music.AMMusicApp
import com.am.music.data.island.IslandAppearance
import com.am.music.data.island.IslandPrefs
import com.am.music.data.model.Song
import com.am.music.ui.island.IslandDebug
import com.am.music.ui.island.IslandNavigator
import com.am.music.ui.island.IslandProgressLineView
import com.am.music.ui.island.SpectrumBarView
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 灵动岛（系统级悬浮窗，仿 iOS Dynamic Island）。
 *
 * 形态：
 * - 收起：黑色胶囊，[封面 20dp | 锚点 | 12 根模拟频谱]，宽 = max(长度, 56+锚点)，
 *   圆角 = 高的一半，视觉上是一颗药丸。
 * - 展开：顶边钉在锚点，向下弹出播放卡（64dp 封面 + 歌名/歌手 + 可拖进度条 +
 *   当前/总时长 + 上一首/播放/下一首），3 秒无操作自动收回。
 *
 * 外观两档（设置页可切）：
 * - 纯黑：0.95 黑底，最省电，任何壁纸下都稳。
 * - 自定义：用户选的图片或视频铺底（视频静音循环），上面压一层暗色 scrim 保证白字可读。
 *
 * 展开/收起只改一次窗口几何（fillGeometry + 一次 updateViewLayout），动画只作用在
 * 内容层的 alpha/scale：逐帧改 WindowManager 尺寸在不少 ROM 上会被直接忽略，
 * 那就是「点了展不开」的根因。
 *
 * 坐标系：窗口用 FLAG_LAYOUT_IN_SCREEN，y=0 对齐物理屏幕顶，锚点百分比才能精确
 * 对齐摄像头挖孔（否则永远差一个状态栏高度）。
 *
 * 与系统媒体岛的关系：设置页把系统媒体通知降级为普通通知后，这里才有意义——
 * 否则两套岛会同时出现在屏幕上。
 */
@OptIn(UnstableApi::class)
class DynamicIslandService : Service() {

    private lateinit var wm: WindowManager

    // ==================== 窗口视图 ====================

    private var rootBox: ViewGroup? = null
    private var contentBox: LinearLayout? = null
    private var bgStack: FrameLayout? = null
    private var bgImage: ImageView? = null
    private var bgVideo: TextureView? = null
    private var bgScrim: View? = null
    private var videoPlayer: ExoPlayer? = null
    private var anchorDot: View? = null
    private var coverImg: ImageView? = null
    private var anchorView: View? = null
    private var leftPad: View? = null
    private var rightPad: View? = null
    private var collapsedBar: View? = null
    private var spectrumView: SpectrumBarView? = null

    /** 收起态胶囊底部那根进度线：收起也能看出播到哪。 */
    private var collapsedProgress: IslandProgressLineView? = null
    private var expandedContent: LinearLayout? = null
    private var titleTv: TextView? = null
    private var artistTv: TextView? = null
    private var progressBar: SeekBar? = null
    private var curTimeTv: TextView? = null
    private var totalTimeTv: TextView? = null
    private var prevBtn: ImageButton? = null
    private var playBtn: ImageButton? = null
    private var nextBtn: ImageButton? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    /** 当前圆角（px）：背景 + 内容一起被这个圆角裁切，动画时同步更新。 */
    private var cornerRadiusPx = 0f

    /** 主题色（ARGB）：进度条、频谱、播放键用它上色。 */
    private var accentColor = Color.WHITE

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var coverJob: Job? = null

    private var currentExpanded = false
    private var currentSongKey: String? = null
    private var animating = false
    private var geometryRecoveryUsed = false
    private var locateMode = false
    private var locateAutoStart = false
    private var entryAnimated = false
    private var seekDragging = false

    private val simData = FloatArray(12)
    private var simPhase = 0f
    private var spectrumAnimator: ValueAnimator? = null
    private var lastSpectrumFrame = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        IslandDebug.log("service onCreate canDraw=${Settings.canDrawOverlays(this)} sw=${screenWidth()} sh=${screenHeight()} density=${resources.displayMetrics.density}")
        showOverlay()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        IslandDebug.log("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_REFRESH -> rebuild()
            ACTION_LOCATE -> {
                if (isLandscape()) {
                    Toast.makeText(this, "横屏不显示岛，转竖屏再定位", Toast.LENGTH_SHORT).show()
                    return START_NOT_STICKY
                }
                locateAutoStart = intent.getBooleanExtra(EXTRA_AUTO_START, false)
                locateMode = true
                rebuild()
            }
            ACTION_SET_WIDTH -> applyWidth()
            ACTION_SET_ANCHOR -> applyAnchor()
            ACTION_SET_ACCENT -> applyAccent()
            ACTION_SET_ANCHOR_POS -> applyAnchorPos(
                intent.getFloatExtra(EXTRA_ANCHOR_X, -1f),
                intent.getFloatExtra(EXTRA_ANCHOR_Y, -1f),
            )
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        coverJob?.cancel()
        scope.cancel()
        stopSpectrum()
        releaseVideo()
        removeOverlay()
        // 定位模式关闭服务不关总开关（只有正常模式才算「关掉岛」）
        if (!locateMode) IslandPrefs.setEnabled(this, false)
        super.onDestroy()
    }

    private fun rebuild() {
        removeOverlay()
        showOverlay()
    }

    // ==================== 悬浮窗 ====================

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        removeOverlay()
        if (locateMode) {
            showLocateOverlay()
        } else {
            showIslandOverlay()
        }
    }

    /** 定位模式：只有一个黑点，拖它对齐摄像头/挖孔，轻点完成。 */
    private fun showLocateOverlay() {
        val density = resources.displayMetrics.density
        val a = (IslandPrefs.anchorSize(this) * density).toInt()
        val hot = locateHotPx(a)

        val box = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(a, a)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
        }
        box.addView(dot)

        val lp = WindowManager.LayoutParams(
            hot, hot, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                // 定位窗也按住状态栏，黑点才能拖进摄像头那条带
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        rootBox = box
        anchorView = dot
        layoutParams = lp
        // 定位窗口只有 hot×hot：这里不能走岛那套几何（否则会被改成胶囊尺寸），
        // 直接把黑点中心摆到已存的百分比坐标上（Y 允许越界，方便对齐摄像头）。
        run {
            val (ax, cy) = anchorCenterPx()
            lp.x = (ax - hot / 2f).roundToInt().coerceIn(0, (screenWidth() - hot).coerceAtLeast(0))
            lp.y = (cy - hot / 2f).roundToInt().coerceIn(-hot, screenHeight())
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        // 窗口本身只有几十 dp，slop 给 10dp：跟手，又不至于把轻点误判成拖动
        val slop = 10f * density
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clampIntoScreen(lp, hot, hot)
                        runCatching { wm.updateViewLayout(box, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val sw = screenWidth().coerceAtLeast(1)
                        val sh = screenHeight().coerceAtLeast(1)
                        val cx = lp.x + hot / 2f
                        val cy = lp.y + hot / 2f
                        IslandPrefs.setAnchorPos(this, cx / sw * 100f, cy / sh * 100f)
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        if (locateAutoStart) {
                            locateMode = false
                            Toast.makeText(this, "已定位，岛已开启", Toast.LENGTH_SHORT).show()
                            rebuild()
                        } else {
                            Toast.makeText(this, "锚点已定位", Toast.LENGTH_SHORT).show()
                            stopSelf()
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
        wm.addView(box, lp)
    }

    private fun locateHotPx(dotPx: Int): Int =
        dotPx.coerceAtLeast((48 * resources.displayMetrics.density).toInt())

    /** 正常模式：胶囊 + 三种可选外观。 */
    private fun showIslandOverlay() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val appearance = IslandPrefs.appearance(this)
        // 主题色：只染进度条 / 频谱 / 播放键，文字保持白色
        accentColor = IslandPrefs.accentColor(this)

        // ---- 封面：收起 20dp / 展开 64dp，圆角 = 边长的 20% ----
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor(Color.BLACK) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = (view.width * 0.2f).coerceAtLeast(1f)
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }

        val anchorSize = (IslandPrefs.anchorSize(this) * density).toInt()
        val anchor = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(anchorSize, anchorSize)
        }

        val barView = SpectrumBarView(this)
        val collapsed = barView

        val tTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            includeFontPadding = false
        }
        val tArtist = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val tProgress = SeekBar(this, null, android.R.attr.seekBarStyle).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
            // 展开卡高度是死的（锚点 + 156dp），SeekBar 默认最小高度能到 48dp，
            // 不压住它就会把三键顶出卡片外
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(28),
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val player = playbackState()?.durationMs ?: 0L
                        curTimeTv?.text = timeText((progress / 1000f * player).toLong())
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seekDragging = true
                    scheduleAutoCollapse()
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seekDragging = false
                    scheduleAutoCollapse()
                    val duration = playbackState()?.durationMs ?: 0L
                    if (duration > 0) {
                        app().container.playbackManager.seekTo(((sb?.progress ?: 0) / 1000f * duration).toLong())
                    }
                }
            })
        }
        val tCur = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10.5f
            includeFontPadding = false
        }
        val tTotal = TextView(this).apply {
            setTextColor(0x99FFFFFF.toInt())
            textSize = 10.5f
            includeFontPadding = false
        }
        val timeSpacer = View(this)
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tCur, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(timeSpacer, LinearLayout.LayoutParams(0, 1, 1f))
            addView(tTotal, LinearLayout.LayoutParams(WRAP, WRAP))
        }

        fun controlBtn(resId: Int): ImageButton = ImageButton(this).apply {
            setImageResource(resId)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setPadding(0, 0, 0, 0)
            scaleType = ImageView.ScaleType.CENTER
            minimumWidth = 0
            minimumHeight = 0
            // 三键各占 1/3 宽铺满整行，图标按原始尺寸居中（不拉伸）
            layoutParams = LinearLayout.LayoutParams(0, dp(32), 1f)
        }
        val bPrev = controlBtn(android.R.drawable.ic_media_previous)
        val bPlay = controlBtn(android.R.drawable.ic_media_play).apply {
            setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
        }
        val bNext = controlBtn(android.R.drawable.ic_media_next)
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(bPrev); addView(bPlay); addView(bNext)
        }

        val titleArtistCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tTitle, LinearLayout.LayoutParams(MATCH_PARENT, WRAP))
            addView(tArtist, LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { topMargin = dp(3) })
        }
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleArtistCol, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
        }
        // 展开卡排版：封面（左）+ 右侧一列（歌名/歌手、进度条、时间、三键）
        val expanded = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(10), 0, 0, 0)
            addView(infoRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
            addView(tProgress, LinearLayout.LayoutParams(MATCH_PARENT, dp(18)).apply { topMargin = dp(4) })
            addView(timeRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { topMargin = dp(1) })
            addView(controlsRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(32)).apply { topMargin = dp(2) })
        }
        expanded.visibility = View.GONE

        val leftPadView = View(this)
        val rightPadView = View(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cover, LinearLayout.LayoutParams(dp(COLLAPSED_COVER), dp(COLLAPSED_COVER)))
            addView(leftPadView, LinearLayout.LayoutParams(0, MATCH_PARENT))
            addView(anchor, LinearLayout.LayoutParams(anchorSize, anchorSize))
            addView(rightPadView, LinearLayout.LayoutParams(0, MATCH_PARENT))
            addView(collapsed, LinearLayout.LayoutParams(dp(COLLAPSED_COVER), MATCH_PARENT))
            addView(expanded, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }

        // ---- 背景层：图片/视频 + scrim，全部在内容之下；纯黑就是一层纯色 ----
        val bgLayer = FrameLayout(this)
        if (appearance == IslandAppearance.CUSTOM) {
            val scrim = View(this).apply {
                setBackgroundColor(0x8C000000.toInt())
                visibility = View.GONE
            }
            bgLayer.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            bgScrim = scrim
        }

        val root = FrameLayout(this)
        val solid = when (appearance) {
            IslandAppearance.BLACK -> 0xF20A0A0A.toInt()
            IslandAppearance.CUSTOM -> if (IslandPrefs.customUri(this) == null) 0xF20A0A0A.toInt() else Color.TRANSPARENT
        }
        // 底色和圆角都做在「背景层」上，不做在 root 上：
        // 收起态窗口为了放大触摸区比胶囊大一圈（左右 10dp、下方 44dp），
        // 底色铺在 root 上就会把整个窗口涂满 → 看起来成了长方形。
        // 背景层尺寸 = 可见胶囊，圆角 = 胶囊高度一半，形状才是真胶囊。
        if (solid != Color.TRANSPARENT) {
            bgLayer.background = GradientDrawable().apply { setColor(solid) }
        }
        bgLayer.clipToOutline = true
        bgLayer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.coerceAtLeast(0.1f))
            }
        }
        root.addView(bgLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val anchorDotView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(anchorSize, anchorSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
        }
        // 锚点黑点只在「拖动定位」时出现；日常收起/展开都不显示，
        // 否则每次起播都会在胶囊上多一颗黑点（用户看到的就是「岛把锚点露出来了」）。
        anchorDotView.visibility = View.GONE
        root.addView(anchorDotView)

        // 收起态底部的进度线：叠在内容之上，但只在收起时显示
        val progressLine = IslandProgressLineView(this).apply {
            visibility = View.GONE
            color = accentColor
        }
        root.addView(progressLine, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                // 关键：让这个悬浮窗自己要求全屏。状态栏窗口在，摄像头那条带的触摸就归 SystemUI；
                // 窗口活着期间把状态栏按住，退出 App 后贴摄像头的位置也还能点。
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 岛要落在挖孔/摄像头那一带：不加这一条，窗口与屏幕切口相交时可能被挪开或被切，
            // 表现为「看得见但位置/尺寸跟 lp 对不上」
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        rootBox = root
        contentBox = content
        bgStack = bgLayer
        anchorDot = anchorDotView
        coverImg = cover
        anchorView = anchor
        leftPad = leftPadView
        rightPad = rightPadView
        collapsedBar = collapsed
        spectrumView = barView
        collapsedProgress = progressLine
        barView.barColor = accentColor
        expandedContent = expanded
        titleTv = tTitle
        artistTv = tArtist
        progressBar = tProgress
        curTimeTv = tCur
        totalTimeTv = tTotal
        prevBtn = bPrev
        playBtn = bPlay
        nextBtn = bNext
        layoutParams = lp

        currentExpanded = IslandPrefs.isExpanded(this)
        applyStateToViews()
        fillGeometry()
        if (currentExpanded) scheduleAutoCollapse()

        attachGestureHandler(root, density)
        wm.addView(root, lp)
        root.visibility = View.GONE
        IslandDebug.log("overlay added expanded=$currentExpanded lp=${lp.width}x${lp.height}@${lp.x},${lp.y} anchor=${IslandPrefs.anchorSize(this)}dp")
        root.post {
            val p = IntArray(2)
            runCatching { root.getLocationOnScreen(p) }
            IslandDebug.log(
                "onScreen=${p[0]},${p[1]} real=${root.width}x${root.height} " +
                    "vis=${if (root.visibility == View.VISIBLE) "V" else "G"} att=${root.isAttachedToWindow}"
            )
            // 让悬浮窗自己试着藏状态栏：状态栏窗口在，摄像头那条带的触摸就归 SystemUI。
            // 非焦点窗口的系统栏请求，部分 ROM 会忽略——成功与否都记一条。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val c = root.windowInsetsController
                if (c == null) {
                    IslandDebug.log("insetsController=null（ROM 不给悬浮窗控系统栏）")
                } else {
                    val ok = runCatching { c.hide(WindowInsets.Type.statusBars()) }.isSuccess
                    IslandDebug.log("insets hide statusBars requested=$ok")
                }
            }
        }

        if (appearance == IslandAppearance.CUSTOM) applyCustomBackground()

        if (!currentExpanded && !entryAnimated) {
            if (IslandPrefs.isAnchorSet(this) && !isLandscape()) {
                entryAnimated = true
                root.post { entryScaleIn(root) }
            }
        }
    }

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 横屏不显示岛：旋转时立刻收起并隐藏，不用等 200ms 轮询。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val box = rootBox ?: return
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) return
        cancelAutoCollapse()
        if (currentExpanded) {
            currentExpanded = false
            IslandPrefs.setExpanded(this, false)
            applyStateToViews()
            applyGeometry()
        }
        setShown(box, false, "landscape(rotate)")
    }

    // ==================== 自定义背景（图片 / 视频） ====================

    private fun applyCustomBackground() {
        val layer = bgStack ?: return
        val uriString = IslandPrefs.customUri(this)
        if (uriString == null) {
            bgScrim?.visibility = View.GONE
            return
        }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return
        val type = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
        bgScrim?.visibility = View.VISIBLE
        if (type.startsWith("video/")) {
            startVideoBackground(uri)
        } else {
            startImageBackground(uri)
        }
    }

    private fun startImageBackground(uri: Uri) {
        val layer = bgStack ?: return
        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        layer.addView(imageView, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bgImage = imageView
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeFromUri(uri) }
            if (bitmap != null) imageView.setImageBitmap(bitmap)
        }
    }

    private fun startVideoBackground(uri: Uri) {
        val layer = bgStack ?: return
        val texture = TextureView(this)
        layer.addView(texture, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        bgVideo = texture
        runCatching {
            val player = ExoPlayer.Builder(this).build()
            player.setMediaItem(MediaItem.fromUri(uri))
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.volume = 0f
            player.setVideoTextureView(texture)
            player.prepare()
            player.playWhenReady = true
            videoPlayer = player
        }
    }

    /** 只解一张「胶囊大小」的图：展开时最大 64dp，按 3 倍屏密度也算不出几百万像素。 */
    private fun decodeFromUri(uri: Uri): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        val sample = generateSample(options.outWidth, options.outHeight, 512)
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }.let { opts ->
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }.getOrNull()

    private fun generateSample(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun releaseVideo() {
        runCatching { videoPlayer?.release() }
        videoPlayer = null
        bgVideo = null
    }

    // ==================== 定位与几何 ====================

    private fun updateDot() {
        val dot = anchorDot ?: return
        val lp = layoutParams ?: return
        val a = (IslandPrefs.anchorSize(this) * resources.displayMetrics.density).toInt()
        if (currentExpanded) {
            val ax = anchorCenterPx().first
            dot.translationX = (ax - a / 2f) - lp.x
            dot.translationY = 0f
        } else {
            // 窗口带了一圈透明留白，锚点仍要对准「可见胶囊」的中心
            val padX = (TOUCH_PAD_X * resources.displayMetrics.density).toInt()
            dot.translationX = padX + (collapsedWidthPx() - a) / 2f
            dot.translationY = (collapsedHeightPx() - a) / 2f
        }
    }

    private fun anchorCenterPx(): Pair<Int, Int> {
        val sw = screenWidth()
        val sh = screenHeight()
        val ax = IslandPrefs.anchorX(this).let { if (it == -1f) 50f else it }
        val ay = IslandPrefs.anchorY(this).let { if (it == -1f) 12f else it }
        return (sw * ax / 100f).toInt() to (sh * ay / 100f).toInt()
    }

    private fun entryScaleIn(box: View) {
        val density = resources.displayMetrics.density
        val a = (IslandPrefs.anchorSize(this) * density).toInt()
        val leftW = leftPad?.layoutParams?.width ?: 0
        val padX = TOUCH_PAD_X * density
        box.pivotX = padX + (8 * density) + (COLLAPSED_COVER * density) + leftW + a / 2f
        box.pivotY = collapsedHeightPx() / 2f
        box.scaleX = 0.3f
        box.scaleY = 0.3f
        box.alpha = 0f
        box.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(360)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()
    }

    private fun applyStateToViews() {
        expandedContent?.visibility = if (currentExpanded) View.VISIBLE else View.GONE
        collapsedBar?.visibility = if (currentExpanded) View.GONE else View.VISIBLE
        anchorView?.visibility = if (currentExpanded) View.GONE else View.VISIBLE
        leftPad?.visibility = if (currentExpanded) View.GONE else View.VISIBLE
        rightPad?.visibility = if (currentExpanded) View.GONE else View.VISIBLE
        // 收起看细线，展开看 SeekBar，两者不同时出现
        collapsedProgress?.visibility = if (currentExpanded) View.GONE else View.VISIBLE
        contentBox?.gravity = if (currentExpanded) Gravity.TOP else Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        // 内边距只加在内容层：加在 root 上会把背景层（自定义图片/视频）挤成不满铺，
        // 展开态还会把底部三键裁掉。
        rootBox?.setPadding(0, 0, 0, 0)
        contentBox?.setPadding(
            if (currentExpanded) (12 * density).toInt() else (8 * density).toInt(),
            if (currentExpanded) (6 * density).toInt() else 0,
            if (currentExpanded) (12 * density).toInt() else (8 * density).toInt(),
            if (currentExpanded) (8 * density).toInt() else 0,
        )
    }

    private fun updateSidePads(windowW: Int) {
        val density = resources.displayMetrics.density
        val a = (IslandPrefs.anchorSize(this) * density).toInt()
        val contentW = (16 * density).toInt() + (COLLAPSED_COVER * density).toInt() + a + (20 * density).toInt()
        val side = ((windowW - contentW) / 2).coerceAtLeast(0)
        leftPad?.layoutParams = LinearLayout.LayoutParams(side, MATCH_PARENT)
        rightPad?.layoutParams = LinearLayout.LayoutParams(side, MATCH_PARENT)
        leftPad?.requestLayout()
        rightPad?.requestLayout()
    }

    private fun removeOverlay() {
        releaseVideo()
        rootBox?.let { runCatching { wm.removeView(it) } }
        rootBox = null; contentBox = null; bgStack = null; bgImage = null; bgScrim = null
        anchorDot = null; coverImg = null; anchorView = null
        leftPad = null; rightPad = null; collapsedBar = null; spectrumView = null
        collapsedProgress = null
        expandedContent = null; titleTv = null; artistTv = null; progressBar = null
        curTimeTv = null; totalTimeTv = null; seekDragging = false
        prevBtn = null; playBtn = null; nextBtn = null
        layoutParams = null
        currentSongKey = null
    }

    // ==================== 手势 ====================

    private fun attachGestureHandler(box: View, density: Float) {
        var downRawX = 0f
        var downRawY = 0f
        var moved = false
        val touchSlop = 25f * density
        prevBtn?.setOnClickListener { scheduleAutoCollapse(); app().container.playbackManager.previous() }
        playBtn?.setOnClickListener { scheduleAutoCollapse(); app().container.playbackManager.togglePlayPause() }
        nextBtn?.setOnClickListener { scheduleAutoCollapse(); app().container.playbackManager.next() }
        box.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    IslandDebug.log("touch DOWN (${e.rawX},${e.rawY}) expanded=$currentExpanded animating=$animating")
                    downRawX = e.rawX
                    downRawY = e.rawY
                    moved = false
                    scheduleAutoCollapse()
                    // 按下时胶囊轻微「按下去」：悬浮窗没有水波纹，这一下压缩是唯一的触觉反馈
                    if (!currentExpanded) {
                        contentBox?.animate()?.cancel()
                        contentBox?.animate()
                            ?.scaleX(0.984f)
                            ?.scaleY(0.972f)
                            ?.setDuration(80)
                            ?.setInterpolator(DecelerateInterpolator())
                            ?.start()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && dx * dx + dy * dy > touchSlop * touchSlop) moved = true
                    true
                }
                MotionEvent.ACTION_UP -> {
                    IslandDebug.log("touch UP moved=$moved animating=$animating")
                    if (!currentExpanded) {
                        contentBox?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(220)
                            ?.setInterpolator(OvershootInterpolator(1.3f))
                            ?.start()
                    }
                    if (!moved && !animating) onTap(e.rawX, e.rawY)
                    true
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    if (currentExpanded && !animating) setExpanded(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!currentExpanded) {
                        contentBox?.animate()
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(200)
                            ?.setInterpolator(OvershootInterpolator(1.2f))
                            ?.start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap(rawX: Float, rawY: Float) {
        IslandDebug.log("tap ($rawX,$rawY) expanded=$currentExpanded animating=$animating")
        if (currentExpanded) {
            val inInfo = isPointInView(coverImg, rawX, rawY) ||
                isPointInView(titleTv, rawX, rawY) ||
                isPointInView(artistTv, rawX, rawY)
            if (inInfo) {
                IslandNavigator.requestOpenPlayer(this)
                setExpanded(false)
            } else {
                setExpanded(false)
            }
        } else {
            setExpanded(true)
        }
    }

    private fun isPointInView(v: View?, rawX: Float, rawY: Float): Boolean {
        if (v == null || v.visibility != View.VISIBLE || v.width <= 0) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + v.width &&
            rawY >= loc[1] && rawY <= loc[1] + v.height
    }

    private fun clampIntoScreen(lp: WindowManager.LayoutParams, w: Int, h: Int) {
        val sw = screenWidth()
        val sh = screenHeight()
        if (sw <= 0 || sh <= 0) return
        val vw = w.coerceAtLeast(1)
        val vh = h.coerceAtLeast(1)
        // 分屏 / 小窗里视图可能比可用宽高还大，区间反转会让 coerceIn 直接抛异常
        lp.x = lp.x.coerceIn(0, (sw - vw).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(-vh, (sh - vh).coerceAtLeast(-vh))
    }

    // ==================== 展开 / 收起 ====================

    private val autoCollapseTask = Runnable {
        if (currentExpanded && !animating) setExpanded(false)
    }

    private fun scheduleAutoCollapse() {
        handler.removeCallbacks(autoCollapseTask)
        handler.postDelayed(autoCollapseTask, 3000)
    }

    private fun cancelAutoCollapse() {
        handler.removeCallbacks(autoCollapseTask)
    }

    private fun setExpanded(expanded: Boolean) {
        val root = rootBox ?: return
        if (expanded == currentExpanded || animating) return
        currentExpanded = expanded
        IslandPrefs.setExpanded(this, expanded)
        applyStateToViews()
        if (expanded) scheduleAutoCollapse() else cancelAutoCollapse()

        val content = contentBox
        val density = resources.displayMetrics.density
        val anchorPx = IslandPrefs.anchorSize(this) * density
        IslandDebug.log("setExpanded($expanded) winBefore=${root.width}x${root.height}")

        if (expanded) {
            // 之前收起/重建如果留下了淡出残影（alpha=0），这里先掰回不透明再展开，
            // 否则会「展开了但看不见」。
            root.animate().cancel()
            root.alpha = 1f
            val bg = bgStack
            // 先把整块卡片（背景+内容）压到锚点附近并透明，改成展开几何后再长出来，
            // 这样「窗口瞬间变宽」那一帧是看不见的，看起来就是胶囊原地长成卡片。
            if (bg != null) {
                bg.animate().cancel()
                bg.alpha = 0f
                bg.scaleX = 0.86f
                bg.scaleY = 0.86f
            }
            if (content != null) {
                content.animate().cancel()
                content.alpha = 0f
                content.scaleX = 0.94f
                content.scaleY = 0.96f
            }
            // 走 updateViewLayout 改尺寸，**不摘窗**：摘了重挂会销毁 TextureView 的
            // SurfaceTexture，视频背景要重新建 surface 才能继续出帧——那就是「展开后卡一下」。
            applyGeometry()
            val anchorDx = (anchorCenterPx().first - (layoutParams?.x ?: 0)).toFloat()
            if (bg != null) {
                bg.pivotX = anchorDx
                bg.pivotY = 0f
                bg.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
            if (content != null) {
                content.translationY = anchorPx * 0.5f
                content.pivotX = anchorDx
                content.pivotY = 0f
                content.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(anchorPx)
                    .setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
            // 卡片里的四行不一起「啪」地出现：歌名 → 进度 → 时间 → 三键依次上浮，
            // 中间隔 38ms，眼睛能看出这一块是「展开出来的」
            expandedContent?.let { card ->
                for (i in 0 until card.childCount) {
                    val v = card.getChildAt(i)
                    v.animate().cancel()
                    v.alpha = 0f
                    v.translationY = 12f * density
                    v.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(300)
                        .setStartDelay(60L + i * 38L)
                        .setInterpolator(DecelerateInterpolator(1.4f))
                        .start()
                }
            }
            IslandDebug.log("expanded winAfter=${root.width}x${root.height} lp=${layoutParams?.width}x${layoutParams?.height}@${layoutParams?.x},${layoutParams?.y}")
        } else {
            // 收起：整块卡片缩回锚点（背景+内容一起缩+淡出），动画跑完再改回胶囊几何。
            // 不再动窗口/根视图的 alpha —— 那是「收起后整颗消失」的根因。
            animating = true
            // 兜底：动画被打断（视图被摘/窗口重建）时别把 animating 永远卡在 true
            handler.postDelayed({ animating = false }, 500)
            val dur = 180L
            val bg = bgStack
            var finished = false
            val finish = Runnable {
                if (finished) return@Runnable
                finished = true
                animating = false
                root.alpha = 1f
                applyGeometry()
                if (bg != null) {
                    bg.animate().cancel()
                    bg.alpha = 1f
                    bg.scaleX = 1f
                    bg.scaleY = 1f
                }
                content?.animate()?.cancel()
                content?.alpha = 1f
                content?.scaleX = 1f
                content?.scaleY = 1f
                content?.translationY = 0f
                // 展开时给每行设过 alpha/translationY，收起后要复原，否则下次展开前会先闪一下
                expandedContent?.let { card ->
                    for (i in 0 until card.childCount) {
                        val v = card.getChildAt(i)
                        v.animate().cancel()
                        v.alpha = 1f
                        v.translationY = 0f
                    }
                }
                IslandDebug.log(
                    "collapsed winAfter=${root.width}x${root.height} " +
                        "alpha=${root.alpha} lp=${layoutParams?.width}x${layoutParams?.height}"
                )
            }
            if (bg != null) {
                bg.pivotX = (anchorCenterPx().first - (layoutParams?.x ?: 0)).toFloat()
                bg.pivotY = 0f
                bg.animate()
                    .alpha(0f)
                    .scaleX(0.88f)
                    .scaleY(0.88f)
                    .setDuration(dur)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction(finish)
                    .start()
            } else {
                handler.postDelayed(finish, dur)
            }
            if (content != null) {
                content.animate()
                    .alpha(0f)
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(dur)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * 把窗口几何一次性摆到当前状态（展开 / 收起）。
     *
     * 只调用一次 updateViewLayout，**不摘窗**：
     * ① 摘窗重挂会销毁 TextureView 的 SurfaceTexture，视频背景要重建 surface 才继续出帧，
     *    表现就是「收纳后再展开卡一下」——所以正常路径不走重挂；
     * ② 真遇到 ROM 忽略 resize（下面 post 里量出宽高不对），才退到 recoverWindow 兜底。
     */
    private fun applyGeometry() {
        val root = rootBox ?: return
        fillGeometry()
        root.invalidateOutline()
        bgStack?.invalidateOutline()
        updateDot()
        val lp = layoutParams ?: return
        geometryRecoveryUsed = false
        runCatching { wm.updateViewLayout(root, lp) }.onFailure {
            IslandDebug.log("updateViewLayout fail: ${it.message}")
            recoverWindow(root, lp)
        }
        // 有些 ROM 的 WindowManager 会「接受」这次 updateViewLayout 但根本不改尺寸，
        // 表现就是点了没反应。下一帧量一下真实宽高，对不上就摘了重挂一次（每个状态只救一次）。
        root.post {
            if (!root.isAttachedToWindow) return@post
            if (root.width != lp.width || root.height != lp.height) {
                IslandDebug.log("size mismatch want=${lp.width}x${lp.height} got=${root.width}x${root.height}, 重挂")
                recoverWindow(root, lp)
            }
        }
    }

    private fun recoverWindow(root: View, lp: WindowManager.LayoutParams) {
        if (geometryRecoveryUsed) return
        geometryRecoveryUsed = true
        runCatching {
            wm.removeView(root)
            wm.addView(root, lp)
        }
        // 兜底重挂会重建 SurfaceTexture：把视频重新绑一次，避免等它自己发现 surface 变了
        val texture = bgVideo
        val player = videoPlayer
        if (texture != null && player != null) {
            root.post { runCatching { player.setVideoTextureView(texture) } }
        }
    }

    /** 只改 lp / 圆角 / 封面尺寸 / 留白，不碰 WindowManager——首次挂窗和后续切换共用。 */
    private fun fillGeometry() {
        val lp = layoutParams ?: return
        val density = resources.displayMetrics.density
        val a = (IslandPrefs.anchorSize(this) * density).toInt()
        val gap = (EXPAND_GAP * density).toInt()
        // 不做任何 Y 方向钳制：锚点要能精确贴到摄像头/挖孔（Y 可越出屏幕上下边缘）
        if (currentExpanded) {
            lp.width = (screenWidth() - 2 * gap).coerceAtLeast(1)
            lp.height = a + (EXPANDED_H * density).toInt()
            lp.x = gap
            lp.y = gap
            cornerRadiusPx = EXPANDED_RADIUS * density
            setCoverSize((EXPANDED_COVER * density).toInt())
            contentBox?.translationY = a.toFloat()
            layoutVisuals(MATCH_PARENT, MATCH_PARENT, 0, 0)
        } else {
            val cw = collapsedWidthPx()
            val ch = collapsedHeightPx()
            // 触摸区比可见胶囊大一圈：状态栏那条带的触摸归 SystemUI，所以窗口向下多留
            // 一块「看不见但点得到」的地方，胶囊本身仍然精确贴在锚点（摄像头）上。
            val padX = (TOUCH_PAD_X * density).toInt()
            val padBottom = (TOUCH_PAD_BOTTOM * density).toInt()
            lp.width = cw + 2 * padX
            lp.height = ch + padBottom
            updateSidePads(cw)
            val (ax, cy) = anchorCenterPx()
            lp.x = (ax - cw / 2f - padX).roundToInt()
                .coerceIn(0, (screenWidth() - lp.width).coerceAtLeast(0))
            lp.y = (cy - ch / 2f).roundToInt().coerceIn(-ch, screenHeight())
            cornerRadiusPx = collapsedRadiusPx()
            setCoverSize((COLLAPSED_COVER * density).toInt())
            contentBox?.translationY = 0f
            layoutVisuals(cw, ch, padX, 0)
        }
    }

    /**
     * 窗口比可见胶囊大一圈时，把视觉层（背景 + 内容）摆到窗口内的偏移位置：
     * 窗口负责「能点到」，视觉层负责「看起来还是那颗胶囊」。
     */
    private fun layoutVisuals(w: Int, h: Int, left: Int, top: Int) {
        for (v in listOfNotNull(bgStack, contentBox, collapsedProgress)) {
            val cur = v.layoutParams as? FrameLayout.LayoutParams
            if (cur != null && cur.width == w && cur.height == h &&
                cur.leftMargin == left && cur.topMargin == top
            ) continue
            v.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = left
                topMargin = top
            }
        }
    }

    private fun setCoverSize(size: Int) {
        val cover = coverImg ?: return
        if (cover.layoutParams?.width == size && cover.layoutParams?.height == size) return
        cover.layoutParams = LinearLayout.LayoutParams(size, size)
        cover.requestLayout()
        cover.invalidateOutline()
    }

    // ==================== 轮询 ====================

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 200)
        }
    }

    private fun playbackState() = app().container.playbackManager.state.value

    /** 岛的显示/隐藏只在变化时记一条：点不动的时候，先确认「你点的是不是我们这颗」。 */
    private fun setShown(box: View, shown: Boolean, why: String) {
        val want = if (shown) View.VISIBLE else View.GONE
        if (box.visibility == want) return
        box.visibility = want
        if (!shown) stopSpectrum()
        IslandDebug.log("island ${if (shown) "SHOW" else "HIDE"} ($why)")
    }

    private fun app(): AMMusicApp = application as AMMusicApp

    /**
     * 换歌时的过渡：封面从 35% 淡进来，歌名/歌手从下方 5dp 弹回原位。
     *
     * 岛是常驻悬浮窗，200ms 轮询一次；不做这一步的话，换歌在屏幕上就是
     * 「一行字突然变成另一行字」，完全没有「换了一首」的感觉。
     */
    private fun animateSongSwap() {
        val d = resources.displayMetrics.density
        for (v in listOfNotNull(titleTv, artistTv)) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = 5f * d
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(280)
                .setStartDelay(40)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
        coverImg?.let { c ->
            c.animate().cancel()
            c.alpha = 0.35f
            c.animate()
                .alpha(1f)
                .setDuration(340)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun update() {
        val box = rootBox ?: return
        if (locateMode) return
        // 横屏隐藏：竖屏专属悬浮，200ms 轮询自动跟随旋转
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            setShown(box, false, "landscape")
            return
        }
        val state = playbackState()
        val song = state?.currentSong
        if (song == null) {
            setShown(box, false, "no-song")
            return
        }
        setShown(box, true, "song=${song.title}")

        val songKey = song.key
        if (songKey != currentSongKey) {
            currentSongKey = songKey
            loadCover(song)
            titleTv?.text = song.title
            artistTv?.text = song.artist
            animateSongSwap()
        }

        val duration = state.durationMs
        val position = state.positionMs
        collapsedProgress?.fraction =
            if (duration > 0) position.toFloat() / duration.toFloat() else 0f
        val p = progressBar
        if (p != null && !seekDragging) {
            p.progress = if (duration > 0) ((position.toFloat() / duration) * 1000).toInt().coerceIn(0, 1000) else 0
            curTimeTv?.text = timeText(position)
            totalTimeTv?.text = timeText(duration)
        }

        val pb = playBtn
        if (pb != null) {
            val res = if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            if (pb.tag != res) {
                pb.setImageResource(res)
                pb.setColorFilter(accentColor, PorterDuff.Mode.SRC_IN)
                pb.tag = res
            }
        }

        if (state.isPlaying && !currentExpanded) startSpectrum() else stopSpectrum()
    }

    // ==================== 滑块热更新 ====================

    private fun applyWidth() = refreshGeometry()

    /** 主题色热更新：不重建视图，只换三处着色（改色时岛不会闪一下）。 */
    private fun applyAccent() {
        val color = IslandPrefs.accentColor(this)
        if (color == accentColor) return
        accentColor = color
        progressBar?.apply {
            progressTintList = android.content.res.ColorStateList.valueOf(color)
            thumbTintList = android.content.res.ColorStateList.valueOf(color)
        }
        spectrumView?.barColor = color
        collapsedProgress?.color = color
        playBtn?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        val b = playBtn
        if (b != null) b.invalidate()
    }

    private fun applyAnchor() {
        val density = resources.displayMetrics.density
        val s = (IslandPrefs.anchorSize(this) * density).toInt()
        anchorView?.let {
            it.layoutParams = LinearLayout.LayoutParams(s, s)
            it.requestLayout()
        }
        anchorDot?.let { it.layoutParams = FrameLayout.LayoutParams(s, s) }
        refreshGeometry()
    }

    private fun applyAnchorPos(xPercent: Float, yPercent: Float) {
        if (locateMode) return
        refreshGeometry()
    }

    /** 设置页拖滑块 / 重新定位后的统一入口：按当前 prefs 重摆一次窗口。 */
    private fun refreshGeometry() {
        // 定位模式下窗口是那颗黑点，几何完全不同，不能按岛来算
        if (locateMode || animating) return
        applyGeometry()
    }

    private fun collapsedHeightDp(): Int = IslandPrefs.anchorSize(this) + 22

    private fun collapsedHeightPx(): Int = (collapsedHeightDp() * resources.displayMetrics.density).toInt()

    private fun collapsedRadiusPx(): Float = collapsedHeightDp() / 2f * resources.displayMetrics.density

    private fun collapsedWidthPx(): Int {
        val density = resources.displayMetrics.density
        val a = IslandPrefs.anchorSize(this)
        return (maxOf(IslandPrefs.width(this), 56 + a) * density).toInt()
    }

    // ==================== 模拟频谱 ====================

    private fun startSpectrum() {
        if (spectrumAnimator?.isRunning == true) return
        val sv = spectrumView ?: return
        lastSpectrumFrame = 0L
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val now = SystemClock.elapsedRealtime()
                val dt = if (lastSpectrumFrame == 0L) 0.016f
                else ((now - lastSpectrumFrame) / 1000f).coerceIn(0.001f, 0.1f)
                lastSpectrumFrame = now
                simPhase += dt
                val k = 1f - 0.75f.pow(dt * 60f)
                for (i in 0 until 12) {
                    val wave = (sin(simPhase * (1.2f + i * 0.22f)) + 1f) / 2f
                    val target = (wave * 0.55f + Random.nextFloat() * 0.45f).coerceIn(0.12f, 1f)
                    simData[i] += (target - simData[i]) * k
                }
                sv.bars = simData.copyOf()
                sv.invalidate()
            }
        }
        anim.start()
        spectrumAnimator = anim
    }

    private fun stopSpectrum() {
        spectrumAnimator?.cancel()
        spectrumAnimator = null
        lastSpectrumFrame = 0L
        val sv = spectrumView
        if (sv != null) {
            sv.bars.fill(0f)
            sv.invalidate()
        }
    }

    // ==================== 封面 ====================

    private fun loadCover(song: Song) {
        coverJob?.cancel()
        val url = song.coverUrl ?: song.albumArtUri?.toString() ?: return
        coverJob = scope.launch {
            val file = runCatching { app().container.coverCache.ensure(url) }.getOrNull()
            val key = song.key
            val bitmap = withContext(Dispatchers.IO) { file?.let { decodeSampled(it.absolutePath) } }
            if (bitmap != null && currentSongKey == key) {
                coverImg?.setImageBitmap(bitmap)
            }
        }
    }

    // ==================== 歌词 ====================

    private fun decodeSampled(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 256 && bounds.outHeight / (sample * 2) >= 256) sample *= 2
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }.let { BitmapFactory.decodeFile(path, it) }
    }.getOrNull()

    // ==================== 工具 ====================

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun screenWidth(): Int = runCatching { resources.displayMetrics.widthPixels }.getOrDefault(1080)

    /** m:ss，长于 1 小时就是 h:mm:ss；展开卡里两行时间要对齐，所以不定长补零。 */
    private fun timeText(ms: Long): String {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    }

    private fun screenHeight(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (getSystemService(WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds.height()
        } else {
            resources.displayMetrics.heightPixels
        }
    }.getOrDefault(resources.displayMetrics.heightPixels)

    companion object {
        const val ACTION_STOP = "com.am.music.island.STOP"
        const val ACTION_LOCATE = "com.am.music.island.LOCATE"
        const val ACTION_REFRESH = "com.am.music.island.REFRESH"
        const val ACTION_SET_WIDTH = "com.am.music.island.SET_WIDTH"
        const val ACTION_SET_ANCHOR = "com.am.music.island.SET_ANCHOR"
        const val ACTION_SET_ACCENT = "com.am.music.island.SET_ACCENT"
        const val ACTION_SET_ANCHOR_POS = "com.am.music.island.SET_ANCHOR_POS"
        const val EXTRA_ANCHOR_X = "anchor_x"
        const val EXTRA_ANCHOR_Y = "anchor_y"
        const val EXTRA_AUTO_START = "auto_start"

        private const val COLLAPSED_COVER = 20
        private const val EXPAND_GAP = 8
        private const val EXPANDED_H = 144
        private const val EXPANDED_COVER = 56
        private const val EXPANDED_RADIUS = 20

        /** 收起态触摸区左右各多出来的透明留白（dp）。视觉胶囊尺寸不变。 */
        private const val TOUCH_PAD_X = 10

        /** 收起态触摸区在胶囊下方多出来的透明高度（dp）：状态栏吃掉的那条带靠它兜住。 */
        private const val TOUCH_PAD_BOTTOM = 44

        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** 服务是否活着：MainActivity 回前台时据此决定要不要补启（同进程，直接读内存即可）。 */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

        /**
         * 开 / 关岛。开启时若没定位过锚点，先以定位模式起来（对齐摄像头后自动开岛）；
         * 关闭直接把服务停掉（服务 onDestroy 会回写开关）。
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, DynamicIslandService::class.java)
            if (enabled) {
                IslandPrefs.setEnabled(context, true)
                if (!IslandPrefs.isAnchorSet(context)) {
                    context.startService(
                        intent.setAction(ACTION_LOCATE).putExtra(EXTRA_AUTO_START, true)
                    )
                } else {
                    context.startService(intent.setAction(ACTION_REFRESH))
                }
            } else {
                context.stopService(intent)
                IslandPrefs.setEnabled(context, false)
            }
        }

        /** 外观变了（黑/玻璃/自定义、换图换视频）：让服务整窗重建一次。 */
        fun refresh(context: Context) {
            if (!IslandPrefs.isEnabled(context)) return
            runCatching {
                context.startService(Intent(context, DynamicIslandService::class.java).setAction(ACTION_REFRESH))
            }
        }
    }
}
