package io.github.jm350234shenzuo.bcz.a11y

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object ListenKeys {
    const val ENABLED = "listen_enabled"
    const val AUTO = "listen_auto"
    const val DELAY = "listen_delay"
    const val GAP = "listen_gap"
    const val TEXTS = "listen_texts"
    const val DEF_TEXTS = "听音,听一听,听单词,发音,读音,单词发音,重听,喇叭"
    const val DEF_DELAY = 900
    const val DEF_GAP = 1800
    const val MIN_DELAY = 0
    const val MAX_DELAY = 6000
}

/**
 * 自动听音：用 Xposed hook 调用 百词斩自己的「听音」按钮处理器来播放题目音频，
 * 省去手动按听音键。不是屏幕朗读，也不朗读题干文字。
 * 触发时机：界面文字发生变化（切题）后，等一段延迟，若这一屏还没播过就自动播一次。
 */
object AutoListen {

    private const val TAG = "[BCZ-A11Y] listen: "

    @Volatile
    var lastResult: String = "尚未执行"
        private set

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

    private fun int(key: String, def: Int): Int = try {
        prefs().getInt(key, def)
    } catch (t: Throwable) {
        def
    }

    private fun str(key: String, def: String): String = try {
        prefs().getString(key, def) ?: def
    } catch (t: Throwable) {
        def
    }

    fun enabled(): Boolean = bool(ListenKeys.ENABLED, true)

    fun autoDefault(): Boolean = bool(ListenKeys.AUTO, true)

    fun listenTexts(): List<String> =
        str(ListenKeys.TEXTS, ListenKeys.DEF_TEXTS).split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun delayMs(): Long = int(ListenKeys.DELAY, ListenKeys.DEF_DELAY).toLong()
        .coerceIn(ListenKeys.MIN_DELAY.toLong(), ListenKeys.MAX_DELAY.toLong())

    fun gapMs(): Long = int(ListenKeys.GAP, ListenKeys.DEF_GAP).toLong()

    @Volatile private var current: Activity? = null
    @Volatile private var autoOn = false
    @Volatile private var autoTouched = false
    @Volatile private var lastUi = 0L
    @Volatile private var lastPlayedUi = 0L
    @Volatile private var lastPlayAt = 0L
    @Volatile private var watching = false
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cfgAt = 0L
    @Volatile private var cfgEnabled = true
    @Volatile private var cfgAuto = false
    @Volatile private var cfgDelay = ListenKeys.DEF_DELAY.toLong()
    @Volatile private var cfgGap = ListenKeys.DEF_GAP.toLong()

    private fun refreshCfg() {
        val now = SystemClock.uptimeMillis()
        if (now - cfgAt < 1000) return
        cfgAt = now
        cfgEnabled = enabled()
        cfgAuto = if (autoTouched) autoOn else autoDefault()
        cfgDelay = delayMs()
        cfgGap = gapMs()
    }

    fun activity(): Activity? = current

    fun isAuto(): Boolean = autoOn

    fun toggleAuto() {
        autoTouched = true
        autoOn = !autoOn
        lastResult = if (autoOn) "自动听音已开启" else "自动听音已关闭"
    }

    fun onActivity(act: Activity) {
        val changed = current !== act
        current = act
        if (changed) {
            lastUi = SystemClock.uptimeMillis()
            lastPlayedUi = 0L
        }
        refreshCfg()
        if (cfgEnabled && cfgAuto) scheduleWatch()
    }

    fun onActivityGone(act: Activity) {
        if (current === act) current = null
    }

    @Volatile private var installed = false

    fun install(cl: ClassLoader) {
        if (installed) return
        installed = true
        hookText(cl)
        hookAudio(cl)
    }

    private fun hookText(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView", cl, "setText", CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        markUi()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "text hook: " + t)
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.view.View", cl, "setContentDescription", CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        markUi()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "cd hook: " + t)
        }
    }

    private fun hookAudio(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.MediaPlayer", cl, "start",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        markPlayed()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "media hook: " + t)
        }
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.SoundPool", cl, "play",
                Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        markPlayed()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "soundpool hook: " + t)
        }
    }

    private fun markUi() {
        refreshCfg()
        if (!cfgEnabled) return
        lastUi = SystemClock.uptimeMillis()
        if (cfgAuto) scheduleWatch()
    }

    private fun markPlayed() {
        val now = SystemClock.uptimeMillis()
        lastPlayAt = now
        lastPlayedUi = lastUi
    }

    private fun scheduleWatch() {
        if (watching) return
        watching = true
        main.postDelayed(watch, 150)
    }

    private val watch = object : Runnable {
        override fun run() {
            watching = false
            try {
                refreshCfg()
                val a = current ?: return
                if (a.isFinishing) return
                if (!cfgEnabled || !cfgAuto) return
                val now = SystemClock.uptimeMillis()
                if (now - lastUi < cfgDelay) {
                    scheduleWatch()
                    return
                }
                if (lastUi == lastPlayedUi) return
                if (now - lastPlayAt < cfgGap) return
                if (playNow(a)) lastPlayedUi = lastUi
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "watch: " + t)
            }
        }
    }

    fun once(a: Activity? = null): Boolean {
        val t = a ?: current
        if (t == null) {
            lastResult = "当前没有页面"
            return false
        }
        if (!enabled()) {
            lastResult = "自动听音功能未启用"
            return false
        }
        lastPlayedUi = lastUi
        return playNow(t)
    }

    private fun playNow(a: Activity): Boolean {
        val decor = try {
            a.window?.decorView
        } catch (t: Throwable) {
            null
        }
        val target = findControl(decor, listenTexts())
        if (target == null) {
            lastResult = "没找到听音按钮（可在设置页补充文字/描述词）"
            return false
        }
        val h = Skip.listenerFor(target)
        if (h != null) {
            return try {
                h.onClick(target)
                lastPlayAt = SystemClock.uptimeMillis()
                lastResult = "已调用百词斩自己的听音处理器：" + (Skip.ownerFor(target) ?: target.javaClass.name)
                true
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "handler: " + t)
                fallbackClick(target)
            }
        }
        return fallbackClick(target)
    }

    private fun fallbackClick(target: View): Boolean = try {
        val ok = target.performClick()
        lastPlayAt = SystemClock.uptimeMillis()
        lastResult = if (ok) "已模拟点击听音按钮" else "听音按钮没有响应"
        ok
    } catch (t: Throwable) {
        lastResult = "听音失败：" + t
        false
    }

    private fun label(v: View): String {
        val t = (v as? TextView)?.text?.toString()?.trim() ?: ""
        val d = v.contentDescription?.toString()?.trim() ?: ""
        return if (t.isNotEmpty()) t else d
    }

    private fun match(l: String, words: List<String>): Boolean {
        if (l.isEmpty() || l.length > 14) return false
        for (w in words) {
            if (l == w) return true
            if (w.length >= 2 && l.contains(w)) return true
        }
        return false
    }

    private fun findControl(root: View?, words: List<String>, depth: Int = 0): View? {
        if (root == null || depth > 40) return null
        if (root.tag == Skip.OWN_TAG) return null
        if (root.isShown) {
            if (match(label(root), words)) {
                val c = clickable(root)
                if (c != null) return c
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val r = findControl(root.getChildAt(i), words, depth + 1)
                if (r != null) return r
            }
        }
        return null
    }

    private fun clickable(v: View): View? {
        var c: View? = v
        var hops = 0
        while (c != null && hops < 5) {
            if (c.isClickable && c.isEnabled && c.isShown) return c
            c = c.parent as? View
            hops++
        }
        return null
    }
}
