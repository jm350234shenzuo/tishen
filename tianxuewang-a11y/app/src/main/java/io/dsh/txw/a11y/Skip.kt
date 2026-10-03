package io.dsh.txw.a11y

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.Collections
import java.util.LinkedHashSet
import java.util.WeakHashMap

/**
 * 配置键。与设置页 SettingsActivity 写入的键一一对应，读写同一个 SharedPreferences。
 */
object SkipKeys {
    const val ENABLED = "skip_enabled"
    const val TEXTS = "skip_texts"
    const val AUTO = "skip_auto"
    const val DELAY = "skip_delay"
    const val WEB = "skip_web"
    const val DISMISS = "dismiss_dialogs"
    const val DISMISS_KW = "dismiss_keywords"
    const val KEEP_SCREEN = "keep_screen_on"
    const val UNLOCK_ROT = "unlock_rotation"

    /** 侦查模式：把采集到的天学网内部符号写到文件，用于编写精准 hook。 */
    const val PROBE = "probe_mode"

    /** 默认要点击的按钮文字（逗号分隔）。 */
    const val DEF_TEXTS = "跳过,跳过本题,跳过这题,跳过该题,跳过本词,跳过该词,跳过这个,下一题,下一个,换一个,不认识,不认得,不会,不知道,不确定,不记得,没印象,想不起来,没记住,不会写,想不出,略过,略过本题,交卷,提交,完成"

    /** 默认要自动关掉的弹窗关键词。 */
    const val DEF_KW = "广告,推荐,升级,更新,评分,通知,会员,活动,福利,邀请,问卷,签到"

    const val DEF_DELAY = 4f
    const val MIN_DELAY = 1.5f
    const val MAX_DELAY = 15f

}

/**
 * Hook 级跳过引擎。全部逻辑运行在被 hook 的天学网进程内，不使用无障碍服务、不合成触摸事件。
 *
 * 三条 hook 线：
 *  1. android.view.View#setOnClickListener —— 拦截天学网自己注册的点击处理器对象（拿到应用内部对象引用）。
 *  2. android.view.View#performClick —— 被动学习屏幕上的真实按钮文字与它背后的处理器类。
 *  3. android.webkit.WebView#evaluateJavascript —— 每个 H5 页面第一次执行 JS 前先注入 hook 脚本，
 *     脚本接管 EventTarget.prototype.addEventListener，从而能直接调用页面自己注册的回调函数。
 *
 * 跳过时优先调用拦截到的应用自身处理器 onClick()，其次 performClick()，再次走 H5 的 JS 回调，
 * 全程只触发应用已有的逻辑，不替用户答题、不改学习记录。
 */
object Skip {

    const val OWN_TAG = "txw-a11y-own"
    private const val TAG = "[TXW-A11Y] "

    @Volatile
    var lastResult: String = "尚未执行"
        private set

    @Volatile private var lastMissDump: String = ""

    // ------------------------------------------------------------------ 配置

    private var prefsRef: XSharedPreferences? = null

    fun prefs(): XSharedPreferences {
        var p = prefsRef
        if (p == null) {
            p = XSharedPreferences(Keys.PKG, Keys.PREFS)
            prefsRef = p
        }
        try {
            p.reload()
        } catch (t: Throwable) {
        }
        return p
    }

    private fun bool(key: String, def: Boolean): Boolean = try {
        prefs().getBoolean(key, def)
    } catch (t: Throwable) {
        def
    }

    private fun split(key: String, def: String): List<String> {
        val raw = try {
            prefs().getString(key, "") ?: ""
        } catch (t: Throwable) {
            ""
        }
        val src = if (raw.isBlank()) def else raw
        return src.split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun isEnabled(): Boolean = bool(SkipKeys.ENABLED, true)

    fun skipTexts(): List<String> = split(SkipKeys.TEXTS, SkipKeys.DEF_TEXTS)

    fun dismissKeywords(): List<String> = split(SkipKeys.DISMISS_KW, SkipKeys.DEF_KW)

    fun webEnabled(): Boolean = bool(SkipKeys.WEB, true)

    fun autoDefault(): Boolean = bool(SkipKeys.AUTO, false)

    fun dismissEnabled(): Boolean = bool(SkipKeys.DISMISS, false)

    fun keepScreen(): Boolean = bool(SkipKeys.KEEP_SCREEN, false)

    fun unlockRotation(): Boolean = bool(SkipKeys.UNLOCK_ROT, false)

    fun delayMs(): Long {
        val v = try {
            prefs().getFloat(SkipKeys.DELAY, SkipKeys.DEF_DELAY)
        } catch (t: Throwable) {
            SkipKeys.DEF_DELAY
        }
        return (v * 1000f).toLong().coerceIn((SkipKeys.MIN_DELAY * 1000f).toLong(), (SkipKeys.MAX_DELAY * 1000f).toLong())
    }

    // 侦查开关读盘有成本，2 秒缓存一次
    @Volatile private var probeCache = false
    @Volatile private var probeAt = 0L

    fun probeEnabled(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - probeAt > 2000L) {
            probeCache = bool(SkipKeys.PROBE, false)
            probeAt = now
        }
        return probeCache
    }

    // ------------------------------------------------------------ 侦查采集

    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 原 hook 侦查采集（写 txw-a11y-probe.txt）已按用户要求移除，保留空实现以兼容调用点。 */
    private fun probe(line: String) {
    }

    // ------------------------------------------------------------ 安装 hook

    @Volatile private var installed = false

    /** 由 Hooks.install() 在目标进程启动时调用一次。 */
    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        guarded("view") { hookView(cl) }
        guarded("webview") { hookWebView(cl) }
        guarded("dialog") { hookDialog(cl) }
        guarded("classloader") { hookClassLoader(cl) }
        XposedBridge.log(TAG + "hook engine installed")
    }

    private inline fun guarded(what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "install " + what + " failed: " + t)
        }
    }

    /** 拦截天学网注册的点击处理器：跳过时直接调用它，而不是模拟点击。 */
    private val listeners: MutableMap<View, View.OnClickListener> =
        Collections.synchronizedMap(WeakHashMap<View, View.OnClickListener>())
    private val listenerOwner: MutableMap<View, String> =
        Collections.synchronizedMap(WeakHashMap<View, String>())

    /** 供自动听音复用：取 hook 截获的控件自身点击处理器。 */
    fun listenerFor(v: View): View.OnClickListener? = listeners[v]

    /** 供自动听音复用：取该控件监听器的类名（日志/侦查用）。 */
    fun ownerFor(v: View): String? = listenerOwner[v]

    private fun hookView(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.view.View", cl, "setOnClickListener", "android.view.View\$OnClickListener",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (isOwn(v)) return
                        val l = param.args[0] as? View.OnClickListener ?: return
                        listeners[v] = l
                        listenerOwner[v] = l.javaClass.name
                        probe("listener   \"" + labelOf(v) + "\"  <-  " + l.javaClass.name)
                    } catch (t: Throwable) {
                    }
                }
            })

        XposedHelpers.findAndHookMethod(
            "android.view.View", cl, "performClick",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val v = param.thisObject as? View ?: return
                        if (isOwn(v)) return
                        if (!probeEnabled()) return
                        val label = labelOf(v)
                        if (label.isEmpty()) return
                        probe("clicked    \"" + label + "\"  |  " + v.javaClass.name + "  |  " + (listenerOwner[v] ?: "-"))
                        if (matches(label, skipTexts())) lastResult = "屏幕上的「" + label + "」已被点击"
                    } catch (t: Throwable) {
                    }
                }
            })
    }

    /** 拦截 WebView：拿到 JS 桥名字，并在每个页面第一次执行 JS 前注入 hook 脚本。 */
    private val jsHooked: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    private val bridgeNames: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    private fun hookWebView(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.webkit.WebView", cl, "addJavascriptInterface", Any::class.java, String::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val name = param.args.getOrNull(1) as? String ?: return
                        bridgeNames.add(name)
                        probe("jsBridge   " + name + "  ->  " + (param.args[0]?.javaClass?.name ?: "-"))
                    } catch (t: Throwable) {
                    }
                }
            })

        XposedHelpers.findAndHookMethod(
            "android.webkit.WebView", cl, "evaluateJavascript", String::class.java, "android.webkit.ValueCallback",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val wv = param.thisObject as? WebView ?: return
                    if (!jsHooked.add(wv)) return
                    try {
                        XposedBridge.invokeOriginalMethod(param.method, wv, arrayOf<Any?>(HOOK_JS, null))
                    } catch (t: Throwable) {
                        XposedBridge.log(TAG + "inject hook js failed: " + t)
                    }
                }
            })
    }

    private fun hookDialog(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.app.Dialog", cl, "show",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val d = param.thisObject as? Dialog ?: return
                        if (!dismissEnabled()) return
                        main.postDelayed({ try { dismissDialog(d) } catch (t: Throwable) { } }, 150)
                    } catch (t: Throwable) {
                    }
                }
            })
    }

    /** 侦查模式下记录天学网自己加载的类，给出精准 hook 的目标符号。 */
    private val interesting = Regex(
        "(?i)(quiz|question|answer|exam|paper|homework|exercise|practice|skip|submit|nextquestion|jsbridge|bridge|h5)"
    )

    private fun hookClassLoader(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.ClassLoader", cl, "loadClass", String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!probeEnabled()) return
                        val n = param.args.getOrNull(0) as? String ?: return
                        if (n.startsWith("com.up366.")) probe("class      " + n)
                        else if (interesting.containsMatchIn(n)) probe("class?     " + n)
                    }
                })
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "loadClass hook skipped: " + t)
        }
    }

    // ------------------------------------------------------------ 页面与开关

    @Volatile private var current: Activity? = null
    @Volatile private var autoOn = false
    @Volatile private var autoTouched = false

    fun activity(): Activity? = current

    fun onActivity(act: Activity) {
        current = act
        applyFlags(act)
        syncAuto()
        if (autoOn) startAuto()
    }

    fun onActivityGone(act: Activity) {
        if (current === act) current = null
    }

    fun isAuto(): Boolean = autoOn

    fun toggleAuto() {
        autoTouched = true
        autoOn = !autoOn
        if (autoOn) startAuto() else stopAuto()
        lastResult = if (autoOn) "自动跳过已开启" else "自动跳过已关闭"
    }

    fun syncAuto() {
        if (!autoTouched) autoOn = autoDefault()
    }

    private fun applyFlags(a: Activity) {
        try {
            if (keepScreen()) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (t: Throwable) {
        }
        try {
            if (unlockRotation()) a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } catch (t: Throwable) {
        }
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoOn) return
            try {
                now(current)
            } catch (t: Throwable) {
            }
            try {
                main.postDelayed(this, delayMs())
            } catch (t: Throwable) {
            }
        }
    }

    private fun startAuto() {
        try {
            main.removeCallbacks(autoTick)
            main.postDelayed(autoTick, delayMs())
        } catch (t: Throwable) {
        }
    }

    private fun stopAuto() {
        try {
            main.removeCallbacks(autoTick)
        } catch (t: Throwable) {
        }
    }

    // ------------------------------------------------------------ 执行跳过

    /**
     * 跳过当前题目：只调用天学网自己已有的点击逻辑，不答题、不提交答案。
     * @return 是否找到了可跳过的目标
     */
    fun now(act: Activity? = null, notify: Boolean = false): Boolean {
        val a = act ?: current
        if (a == null) {
            lastResult = "当前没有页面"
            return false
        }
        if (!isEnabled()) {
            lastResult = "跳过功能未启用（设置页第一项没打开）"
            if (notify) toast(a, lastResult)
            return false
        }
        val decor = try {
            a.window?.decorView
        } catch (t: Throwable) {
            null
        }
        if (decor == null) {
            lastResult = "取不到界面"
            if (notify) toast(a, lastResult)
            return false
        }

        val roots = scanRoots(a)
        val target = searchTarget(roots, skipTexts())
        if (target != null) {
            if (target === softHit) Diag.line("soft hit: 文字命中但控件不可点，直接尝试 " + target.javaClass.name)
            val label = labelOf(target)
            val l = listeners[target]
            if (l != null) {
                try {
                    l.onClick(target)
                    lastResult = "已调用天学网自身处理器：" + oneLine(label) + "  <-  " + (listenerOwner[target] ?: "?")
                    Diag.line("skip ok: " + lastResult)
                    if (notify) toast(a, lastResult)
                    return true
                } catch (t: Throwable) {
                    XposedBridge.log(TAG + "listener call failed: " + t)
                }
            }
            try {
                if (target.performClick()) {
                    lastResult = "已点击：" + oneLine(label)
                    Diag.line("skip ok: " + lastResult)
                    if (notify) toast(a, lastResult)
                    return true
                }
            } catch (t: Throwable) {
            }
        }

        if (webEnabled() && clickInWeb(a, roots, skipTexts())) {
            Diag.line("skip web: " + lastResult)
            if (notify) toast(a, lastResult)
            return true
        }

        if (hasCalib() && tapCalib(a)) {
            if (notify) toast(a, lastResult)
            return true
        }

        lastResult = "没找到可跳过的按钮"
        try {
            Diag.line("scan: windows=" + roots.size + " -> " + roots.joinToString(" | ") { it.javaClass.simpleName + "(" + countViews(it) + " 控件)" })
        } catch (t: Throwable) {
        }
        val dump = roots.joinToString(" || ") { clickableLabels(it) }.take(3000)
        if (dump != lastMissDump) {
            lastMissDump = dump
            Diag.line("skip miss，屏幕上的文字：" + dump)
            if (probeEnabled()) probe("skipMiss   " + dump)
        }
        if (notify) {
            toast(a, "没找到可跳过的按钮，正在列出屏幕上的按钮…")
            picker(a)
        }
        return false
    }


    // ------------------------------------------------- 坐标校准（Compose 页面用）
    // 百词斩/天学网答题页是 Jetpack Compose：整页只有一个 AndroidComposeView，
    // 按钮不是 View 对象（是画上去的），遍历 View 树永远找不到「跳过」按钮。
    // 这里让用户校准一次点击坐标，之后模块直接在 decorView 上注入一次真实触摸事件，
    // 等价于手指点击（Compose 会正常做 hit-test）。不依赖无障碍服务，也不需要逆向 Compose。

    private const val TAP_FILE = "txw-a11y-tap.txt"
    @Volatile private var calibX = -1f
    @Volatile private var calibY = -1f
    @Volatile private var calibLoaded = false

    private fun tapFile(): File? {
        val ctx = Hooks.ctx() ?: return null
        val dir = try {
            ctx.getExternalFilesDir(null) ?: ctx.filesDir
        } catch (t: Throwable) {
            ctx.filesDir
        }
        if (dir == null) return null
        return File(dir, TAP_FILE)
    }

    private fun loadCalib() {
        if (calibLoaded) return
        calibLoaded = true
        try {
            val f = tapFile() ?: return
            if (!f.exists()) return
            val parts = f.readText().trim().split(',')
            if (parts.size >= 2) {
                calibX = parts[0].trim().toFloat()
                calibY = parts[1].trim().toFloat()
            }
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "loadCalib: " + t)
        }
    }

    fun hasCalib(): Boolean {
        loadCalib()
        return calibX >= 0f && calibY >= 0f
    }

    private fun saveCalib(x: Float, y: Float) {
        calibX = x
        calibY = y
        calibLoaded = true
        try {
            tapFile()?.writeText(x.toString() + "," + y.toString() + "\n")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "saveCalib: " + t)
        }
    }

    fun clearCalib() {
        calibX = -1f
        calibY = -1f
        calibLoaded = true
        try {
            tapFile()?.delete()
        } catch (t: Throwable) {
        }
        Diag.line("calib cleared")
    }

    fun say(a: Activity?, msg: String) {
        if (a != null) toast(a, msg)
    }

    /** 全屏透明层截获用户下一次点击，记下它相对 decorView 的坐标（15 秒超时）。 */
    fun startCalibration(act: Activity? = null, notify: Boolean = true) {
        val a = act ?: current ?: return
        a.runOnUiThread {
            try {
                val decor = a.window?.decorView as? ViewGroup
                if (decor == null) {
                    toast(a, "取不到界面")
                    return@runOnUiThread
                }
                val layer = View(a)
                layer.setBackgroundColor(0x3300E676)
                layer.isClickable = true
                layer.setOnTouchListener(object : View.OnTouchListener {
                    override fun onTouch(v: View, ev: MotionEvent): Boolean {
                        if (ev.actionMasked == MotionEvent.ACTION_UP) {
                            val loc = IntArray(2)
                            decor.getLocationOnScreen(loc)
                            val x = ev.rawX - loc[0]
                            val y = ev.rawY - loc[1]
                            try {
                                decor.removeView(layer)
                            } catch (t: Throwable) {
                            }
                            saveCalib(x, y)
                            Diag.line("calib: x=" + x + " y=" + y + " decor=" + decor.width + "x" + decor.height)
                            toast(a, "已记住位置 (" + x.toInt() + "," + y.toInt() + ")，以后「跳过本题」就点这里")
                        }
                        return true
                    }
                })
                decor.addView(layer, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                if (notify) toast(a, "点一下你要按的那个按钮（15 秒内）")
                main.postDelayed({
                    try {
                        decor.removeView(layer)
                    } catch (t: Throwable) {
                    }
                }, 15000L)
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "startCalibration: " + t)
            }
        }
    }

    /** 在校准坐标注入一次真实触摸。 */
    fun tapCalib(act: Activity? = null, notify: Boolean = false): Boolean {
        val a = act ?: current
        if (a == null) {
            lastResult = "当前没有页面"
            return false
        }
        if (!hasCalib()) {
            if (notify) toast(a, "还没有校准位置：先点面板里的「校准点击位置」")
            return false
        }
        val decor = try {
            a.window?.decorView
        } catch (t: Throwable) {
            null
        }
        if (decor == null) return false
        val x = calibX
        val y = calibY
        try {
            a.runOnUiThread {
                try {
                    val t0 = SystemClock.uptimeMillis()
                    val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
                    down.source = InputDevice.SOURCE_TOUCHSCREEN
                    val up = MotionEvent.obtain(t0, t0 + 70L, MotionEvent.ACTION_UP, x, y, 0)
                    up.source = InputDevice.SOURCE_TOUCHSCREEN
                    decor.dispatchTouchEvent(down)
                    decor.dispatchTouchEvent(up)
                    down.recycle()
                    up.recycle()
                } catch (t: Throwable) {
                    XposedBridge.log(TAG + "tapCalib: " + t)
                }
            }
        } catch (t: Throwable) {
            return false
        }
        lastResult = "已按校准位置点击 (" + x.toInt() + ", " + y.toInt() + ")"
        Diag.line("tap: " + lastResult)
        if (notify) toast(a, lastResult)
        return true
    }

    private const val APP_NAME = "天学网"

    // ------------------------------------------------- 按钮选择器（找不到时的兜底）

    private class Pick(val label: String, val view: View)

    /** 列出当前屏幕上所有可点控件：用户点哪一条，模块就点哪个控件，并把文字记进跳过词表。 */
    fun picker(act: Activity? = null) {
        val a = act ?: current ?: return
        val roots = scanRoots(a)
        val picks = collectPicks(roots)
        a.runOnUiThread {
            try {
                if (picks.isEmpty()) {
                    toast(a, "当前屏幕上没有可点的控件")
                } else {
                    val names = ArrayList<String>()
                    for (p in picks) names.add(p.label)
                    val b = android.app.AlertDialog.Builder(a)
                    b.setTitle("点一下要跳过的按钮（共 " + names.size + " 个）")
                    b.setItems(names.toTypedArray()) { _, which ->
                        val p = picks[which]
                        val ok = clickView(p.view)
                        val owner = listenerOwner[p.view] ?: "?"
                        val msg = if (ok) "已点击「" + oneLine(p.label) + "」 " + owner else "点击失败：" + oneLine(p.label)
                        Diag.line("pick: " + msg)
                        toast(a, msg)
                        if (ok) remember(p.label)
                    }
                    b.setNegativeButton("取消", null)
                    b.show()
                }
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "picker failed: " + t)
            }
        }
    }

    /** 点某个控件：优先调用 hook 拿到的 App 自身点击处理器，失败才 performClick。 */
    fun clickView(v: View): Boolean {
        val label = labelOf(v)
        val l = listeners[v]
        if (l != null) {
            try {
                l.onClick(v)
                lastResult = "已调用" + APP_NAME + "自身处理器：" + oneLine(label) + "  <-  " + (listenerOwner[v] ?: "?")
                Diag.line("skip ok: " + lastResult)
                return true
            } catch (t: Throwable) {
            }
        }
        try {
            if (v.performClick()) {
                lastResult = "已点击：" + oneLine(label)
                Diag.line("skip ok: " + lastResult)
                return true
            }
        } catch (t: Throwable) {
        }
        return false
    }

    /** 让模块自己的设置页把这条文字存进跳过词表（目标 App 进程写不了模块的 prefs）。 */
    private fun remember(label: String) {
        val t = label.trim()
        if (t.isEmpty()) return
        try {
            val i = android.content.Intent()
            i.setClassName(Keys.PKG, Keys.PKG + ".SettingsActivity")
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            i.putExtra("skip_add", t)
            Hooks.ctx()?.startActivity(i)
        } catch (t2: Throwable) {
            XposedBridge.log(TAG + "remember failed: " + t2)
        }
    }

    private fun collectPicks(roots: List<View>): List<Pick> {
        val out = ArrayList<Pick>()
        val seen = LinkedHashSet<String>()
        for (r in roots) collect(r, out, seen, 0)
        return out
    }

    private fun countViews(v: View): Int {
        var n = 1
        try {
            if (v is ViewGroup) for (i in 0 until v.childCount) n += countViews(v.getChildAt(i))
        } catch (t: Throwable) {
        }
        return n
    }

    /** 进程内所有窗口的最顶层 View（含 Dialog / PopupWindow / 其他 Activity 的窗口）。 */
    private fun allRoots(): List<View> {
        val out = ArrayList<View>()
        try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews")
            f.isAccessible = true
            val raw = f.get(inst)
            if (raw is Iterable<*>) {
                for (x in raw) if (x is View) out.add(x)
            }
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "allRoots failed: " + t)
        }
        return out
    }

    /** 当前 Activity 的 decorView + 进程内所有窗口 root，去重。 */
    private fun scanRoots(a: Activity): List<View> {
        val out = ArrayList<View>()
        val seen = LinkedHashSet<Int>()
        try {
            val d = a.window?.decorView
            if (d != null) seen.add(System.identityHashCode(d))
        } catch (t: Throwable) {
        }
        try {
            val d = a.window?.decorView
            if (d != null) out.add(d)
        } catch (t: Throwable) {
        }
        for (v in allRoots()) {
            if (seen.add(System.identityHashCode(v))) out.add(v)
        }
        return out
    }

    private fun collect(root: View, out: MutableList<Pick>, seen: MutableSet<String>, depth: Int) {
        if (depth > 40 || out.size >= 60) return
        if (isOwn(root)) return
        try {
            val c = clickableAncestor(root)
            if (c != null && c.isShown && c.isEnabled && !isOwn(c)) {
                val one = labelOf(c).ifEmpty { labelOf(root) }
                val label = if (one.isNotEmpty()) one else nameOf(c)
                if (seen.add(label)) out.add(Pick(label, c))
            } else if (root.isShown && !isOwn(root)) {
                val one = labelOf(root)
                val label = if (one.isNotEmpty()) one else nameOf(root)
                if (seen.add(label)) out.add(Pick(label, root))
            }
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) collect(root.getChildAt(i), out, seen, depth + 1)
            }
        } catch (t: Throwable) {
        }
    }

    private fun nameOf(v: View): String {
        val idn = try {
            if (v.id == View.NO_ID) "" else v.resources.getResourceEntryName(v.id)
        } catch (t: Throwable) {
            ""
        }
        val cn = v.javaClass.name.substringAfterLast(".")
        return (if (idn.isEmpty()) "" else "#" + idn + " ") + "<" + cn + ">"
    }
    private fun toast(a: Activity?, msg: String) {
        if (a == null) return
        try {
            a.runOnUiThread {
                try {
                    android.widget.Toast.makeText(a, msg, android.widget.Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun oneLine(s: String): String {
        val t = s.replace("\n", " ").trim()
        return if (t.length > 20) t.substring(0, 20) + "…" else t
    }

    /** 图标按钮没有文字时，靠资源名判断（如 btn_skip / iv_next）。 */
    private fun idHint(v: View): Boolean = try {
        if (v.id == View.NO_ID) false
        else {
            val n = v.resources.getResourceEntryName(v.id).lowercase()
            n.contains("skip") || n.contains("next") || n.contains("ignore") ||
                n.contains("unknown") || n.contains("notknow") || n.contains("dontknow") ||
                n.contains("later") || n.contains("jump")
        }
    } catch (t: Throwable) {
        false
    }

    /** 把当前屏幕上的文字收集起来，跳过失败时写进诊断日志，用于补全跳过词表。 */
    private fun clickableLabels(root: View): String {
        val out = LinkedHashSet<String>()
        try {
            walk(root, out, 0)
        } catch (t: Throwable) {
        }
        val s = out.joinToString(" | ")
        return if (s.length > 1200) s.substring(0, 1200) else s
    }

    private fun walk(v: View, out: MutableSet<String>, depth: Int) {
        if (depth > 40 || out.size >= 200) return
        if (isOwn(v)) return
        try {
            val l = labelOf(v)
            if (l.isNotEmpty() && (v.isClickable || v is TextView)) {
                out.add(if (v.isClickable) "[" + oneLine(l) + "]" else oneLine(l))
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), out, depth + 1)
            }
        } catch (t: Throwable) {
        }
    }

    /** 文字命中但找不到可点祖先时的「软命中」，最后再试一次。 */
    @Volatile private var softHit: View? = null

    /** 在所有窗口里找跳过目标；硬命中优先，找不到再退而用软命中。 */
    private fun searchTarget(roots: List<View>, words: List<String>): View? {
        softHit = null
        for (r in roots) {
            val h = findTarget(r, words)
            if (h != null) return h
        }
        return softHit
    }

    private fun findTarget(root: View, words: List<String>): View? {
        if (words.isEmpty()) return null
        if (isOwn(root)) return null
        try {
            val label = labelOf(root)
            val hit = (label.isNotEmpty() && matches(label, words)) || idHint(root)
            if (hit && root.isShown && root.isEnabled) {
                if (listeners[root] != null) return root
                val c = clickableAncestor(root)
                if (c != null) return c
                if (root.isClickable) return root
                if (softHit == null) softHit = root
            }
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    val hit = findTarget(root.getChildAt(i), words)
                    if (hit != null) return hit
                }
            }
        } catch (t: Throwable) {
        }
        return null
    }

    private fun clickableAncestor(v: View): View? {
        var cur: View? = v
        var hop = 0
        while (cur != null && hop <= 15) {
            if (cur.isClickable && cur.isEnabled && cur.isShown) return cur
            cur = cur.parent as? View
            hop++
        }
        return null
    }

    /** H5 页面：调用页面自己注册的 click 回调函数；拿不到回调才退化为按钮 click()。 */
    private fun clickInWeb(a: Activity, roots: List<View>, words: List<String>): Boolean {
        var wv: WebView? = null
        for (r in roots) {
            wv = findWebView(r)
            if (wv != null) break
        }
        if (wv == null) return false
        val js = "__txwSkip(" + jsArray(words) + ")"
        try {
            wv.post {
                try {
                    wv.evaluateJavascript(js) { value ->
                        val r = value?.trim('"') ?: ""
                        lastResult = if (r.isEmpty()) "H5：页面里没找到匹配按钮"
                        else "H5：" + (if (r.startsWith("handler")) "已直接调用页面自身回调 " else "已触发按钮 ") + r.substringAfter(':')
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(TAG + "h5 run failed: " + t)
                }
            }
        } catch (t: Throwable) {
            return false
        }
        lastResult = "H5：已向页面下发跳过脚本"
        return true
    }

    private fun findWebView(v: View?): WebView? {
        if (v == null) return null
        if (v is WebView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findWebView(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    // ------------------------------------------------------------ 小工具

    private fun dismissDialog(d: Dialog) {
        val kws = dismissKeywords()
        if (kws.isEmpty()) return
        val all = collectText(d.window?.decorView)
        if (all.isEmpty()) return
        for (k in kws) {
            if (k.isNotEmpty() && all.contains(k)) {
                d.dismiss()
                probe("dismissed  " + all.take(60))
                lastResult = "已关掉弹窗：" + k
                return
            }
        }
    }

    private fun collectText(v: View?): String {
        if (v == null) return ""
        val sb = StringBuilder()
        try {
            if (v is TextView) sb.append(v.text ?: "")
            if (v.contentDescription != null) sb.append(' ').append(v.contentDescription)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) sb.append(' ').append(collectText(v.getChildAt(i)))
            }
        } catch (t: Throwable) {
        }
        return sb.toString()
    }

    fun labelOf(v: View): String {
        try {
            val t = (v as? TextView)?.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) return t
            return v.contentDescription?.toString()?.trim().orEmpty()
        } catch (t: Throwable) {
            return ""
        }
    }

    /** 允许做「包含」匹配的核心词，只用于这些词以免误点进度文字等无关控件。 */
    private val LOOSE = listOf(
        "跳过", "不认识", "不认得", "不会", "不知道", "不确定", "不记得",
        "没印象", "想不起来", "略过", "下一题", "下一个", "换一个"
    )

    private fun matches(label: String, words: List<String>): Boolean {
        val l = label.replace(" ", "").replace("\u00A0", "").replace("\u3000", "")
        if (l.isEmpty() || l.length > 12) return false
        for (w0 in words) {
            val w = w0.replace(" ", "")
            if (w.isEmpty()) continue
            if (l == w) return true
            if (l.startsWith(w) && l.length <= w.length + 4) return true
        }
        for (w in LOOSE) {
            if (l.contains(w) && l.length <= w.length + 6) return true
        }
        return false
    }

    private fun isOwn(v: View): Boolean = v.tag == OWN_TAG

    private fun jsArray(words: List<String>): String = words.joinToString(prefix = "[", postfix = "]", separator = ",") {
        "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /**
     * 注入到每个 H5 页面的 hook 脚本：
     * 接管 addEventListener，把页面自己注册的 click 回调存下来，跳过时直接调用该回调函数，
     * 不再依赖「找元素再点」的模拟点击。脚本无 $ 字符，避免 Kotlin 模板冲突。
     */
    private const val HOOK_JS =
        "(function(){if(window.__txwHooked)return 'already';window.__txwHooked=1;" +
                "window.__txwL=[];var ra=EventTarget.prototype.addEventListener;" +
                "EventTarget.prototype.addEventListener=function(t,f,o){try{if(t==='click'&&typeof f==='function'){" +
                "var has=false;for(var i=0;i<window.__txwL.length;i++){if(window.__txwL[i].el===this){has=true;break}}" +
                "if(!has)window.__txwL.push({el:this,fn:f})}}catch(e){}return ra.call(this,t,f,o)};" +
                "window.__txwSkip=function(words){" +
                "var els=document.querySelectorAll('button,a,input,div,span,p,li,label');" +
                "for(var i=0;i<els.length;i++){var e=els[i];var t=(e.innerText||e.value||'').trim();" +
                "if(!t||t.length>10)continue;" +
                "for(var j=0;j<words.length;j++){var w=words[j];" +
                "if(t===w||(t.indexOf(w)===0&&t.length<=w.length+4)){" +
                "for(var k=0;k<window.__txwL.length;k++){var o=window.__txwL[k];if(o.el===e){" +
                "try{o.fn.call(e,{type:'click',target:e,currentTarget:e,preventDefault:function(){},stopPropagation:function(){}});" +
                "return 'handler:'+t}catch(x){}}}" +
                "try{e.click();return 'click:'+t}catch(x){}}}}return ''};return 'hooked'})()";
}
