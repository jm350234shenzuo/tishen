package io.dsh.txw.a11y

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object Hooks {

    @Volatile private var appCtx: Context? = null

    fun ctx(): Context? = appCtx

    fun install(cl: ClassLoader, app: Context) {
        appCtx = app.applicationContext ?: app
        Diag.init(appCtx)
        guarded("font/theme") { hookFontAndTheme(cl) }
        guarded("motion") { hookMotion(cl) }
        guarded("lifecycle") { hookLifecycle(cl) }
        guarded("timer") { hookTimer(cl) }
        guarded("auto-listen") { AutoListen.install(cl) }
        guarded("force") { Force.install(cl) }
        guarded("recon") { Recon.install(cl) }
        guarded("score") { Score.install(cl) }
    }

    private inline fun guarded(what: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            XposedBridge.log("[TXW-A11Y] hook " + what + " failed: " + t)
        }
    }

    // ---------------------------------------------------------------- display

    private fun hookFontAndTheme(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.app.Activity", cl, "attachBaseContext", Context::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val base = param.args[0] as? Context ?: return
                    val cfg = Config.get(base)
                    if (!cfg.enabled || !cfg.covers(base.packageName)) return
                    val f = cfg.fontScale
                    val night = cfg.nightMode
                    if (f == 1.0f && night == 0) return
                    val conf = Configuration(base.resources.configuration)
                    if (f != 1.0f) {
                        conf.fontScale = f.coerceIn(0.6f, 2.5f)
                    }
                    if (night != 0) {
                        val want = if (night == 2) Configuration.UI_MODE_NIGHT_YES
                        else Configuration.UI_MODE_NIGHT_NO
                        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or want
                    }
                    param.args[0] = base.createConfigurationContext(conf)
                }
            })
    }

    // ---------------------------------------------------------------- motion

    private fun hookMotion(cl: ClassLoader) {
        val zero = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val c = appCtx ?: return
                if (!Config.get(c).reduceMotion) return
                param.args[0] = 0L
            }
        }
        tryHook("android.view.animation.Animation", cl, "setDuration", zero)
        tryHook("android.view.animation.Animation", cl, "setStartOffset", zero)
        tryHook("android.animation.ValueAnimator", cl, "setDuration", zero)
        tryHook("android.animation.ValueAnimator", cl, "setStartDelay", zero)
    }

    private fun tryHook(cls: String, cl: ClassLoader, method: String, cb: XC_MethodHook) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, Long::class.javaPrimitiveType, cb)
        } catch (t: Throwable) {
            XposedBridge.log("[TXW-A11Y] " + cls + "." + method + " not hooked: " + t)
        }
    }

    // ------------------------------------------------------------- lifecycle

    private fun hookLifecycle(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.app.Activity", cl, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val act = param.thisObject as? Activity ?: return
                    val c = appCtx ?: return
                    val cfg = Config.get(c)
                    try {
                        if (cfg.bigTouch) A11y.scheduleTouchFix(act)
                    } catch (t: Throwable) {
                        XposedBridge.log("[TXW-A11Y] bigTouch: " + t)
                    }
                    Diag.line("onResume " + act.javaClass.name + " enabled=" + cfg.enabled +
                        " covers=" + cfg.covers(act.packageName) + " overlay=" + cfg.overlay)
                    try {
                        AutoListen.onActivity(act)
                    } catch (t: Throwable) {
                        XposedBridge.log("[TXW-A11Y] listen: " + t)
                    }
                    try {
                        Force.onActivity(act)
                    } catch (t: Throwable) {
                        XposedBridge.log("[TXW-A11Y] force: " + t)
                    }
                }
            })
        XposedHelpers.findAndHookMethod(
            "android.app.Activity", cl, "onDestroy",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val act = param.thisObject as? Activity ?: return
                    try {
                        AutoListen.onActivityGone(act)
                    } catch (_: Throwable) {
                    }
                }
            })
    }

    // ----------------------------------------------------------------- timer

    private fun hookTimer(cl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.os.CountDownTimer", cl, "start",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val c = appCtx ?: return
                    val cfg = Config.get(c)
                    val mul = cfg.timerMul
                    val unlimited = cfg.timerUnlimited
                    if (mul <= 1.0f && !unlimited) return
                    val self = param.thisObject ?: return
                    val base = try {
                        XposedHelpers.getLongField(self, "mMillisInFuture")
                    } catch (_: Throwable) {
                        return
                    }
                    if (base <= 0L) return
                    val span = if (unlimited) Long.MAX_VALUE / 8 else (base.toDouble() * mul).toLong()
                    try {
                        XposedHelpers.setLongField(
                            self, "mStopTimeInFuture",
                            SystemClock.elapsedRealtime() + span
                        )
                    } catch (t: Throwable) {
                        XposedBridge.log("[TXW-A11Y] timer: " + t)
                    }
                }
            })
    }
}
