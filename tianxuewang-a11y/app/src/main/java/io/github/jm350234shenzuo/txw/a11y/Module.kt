package io.github.jm350234shenzuo.txw.a11y

import android.app.Application
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

class Module : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == Keys.PKG) return
        if (pkg == "android") return
        if (pkg.startsWith("com.android.systemui")) return
        if (pkg.startsWith("com.google.android.webview")) return
        XposedBridge.log("[TXW-A11Y] load pkg=" + pkg + " process=" + lpparam.processName)
        try {
            install(lpparam.classLoader)
        } catch (t: Throwable) {
            XposedBridge.log("[TXW-A11Y] cannot hook " + pkg + ": " + t)
        }
    }

    /**
     * 三条入口，谁先到谁负责初始化：Application.attach（最早，只要模块被加载进这个进程就会触发，
     * 用来证明「模块到底有没有进来」）、Instrumentation.callApplicationOnCreate（常规路径）、
     * Application.onCreate（兜底）。三条都写诊断日志，避免出现「什么都看不到」的死角。
     */
    private fun install(cl: ClassLoader) {
        val flags = AtomicBoolean(false)
        val started = AtomicBoolean(false)

        // 最早的痕迹：Application.attach(Context)
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", cl, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        Diag.init(app)
                        Diag.line("attach " + app.packageName + " module=loaded")
            Diag.line("module build TXW-20261003T1450 pkg=" + app.packageName)
                        XposedBridge.log("[TXW-A11Y] attach " + app.packageName)
                        if (started.compareAndSet(false, true)) boot(cl, app)
                    }
                })
        } catch (t: Throwable) {
            XposedBridge.log("[TXW-A11Y] attach hook failed: " + t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Instrumentation", cl, "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.args[0] as? Application ?: return
                        if (started.compareAndSet(false, true)) boot(cl, app)
                    }
                })
        } catch (t: Throwable) {
            XposedBridge.log("[TXW-A11Y] callApplicationOnCreate hook failed: " + t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", cl, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        if (started.compareAndSet(false, true)) boot(cl, app)
                    }
                })
        } catch (t: Throwable) {
            XposedBridge.log("[TXW-A11Y] Application.onCreate hook failed: " + t)
        }

        // 保险丝：万一上面三条都没触发，30 秒后再试一次（用当前 Application）
        if (flags.compareAndSet(false, true)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    val app = currentApplication(cl)
                    if (app != null) {
                        Diag.init(app)
                        Diag.line("fallback boot " + app.packageName)
                        if (started.compareAndSet(false, true)) boot(cl, app)
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("[TXW-A11Y] fallback failed: " + t)
                }
            }, 30000L)
        }
    }

    private fun currentApplication(cl: ClassLoader): Application? {
        return try {
            val helper = XposedHelpers.findClass("android.app.AndroidAppHelper", cl)
            val m = helper.getMethod("currentApplication")
            m.invoke(null) as? Application
        } catch (t: Throwable) {
            null
        }
    }

    private fun boot(cl: ClassLoader, app: Application) {
        Diag.init(app)
        try {
            val cfg = Config.get(app, true)
            val covered = cfg.covers(app.packageName)
            Diag.line(
                "boot " + app.packageName + " enabled=" + cfg.enabled + " covers=" + covered +
                    " targets=" + cfg.targets + " overlay=" + cfg.overlay + " font=" + cfg.fontScale
            )
            XposedBridge.log(
                "[TXW-A11Y] boot " + app.packageName + " enabled=" + cfg.enabled +
                    " covers=" + covered + " targets=" + cfg.targets
            )
            if (!cfg.enabled) {
                Diag.line("skip: module disabled in settings")
                return
            }
            if (!covered) {
                Diag.line("skip: " + app.packageName + " not in targets " + cfg.targets)
                return
            }
            Hooks.install(cl, app)
            Diag.line("hooks installed in " + app.packageName)
            XposedBridge.log("[TXW-A11Y] active in " + app.packageName)
        } catch (t: Throwable) {
            Diag.line("boot FAILED: " + t)
            XposedBridge.log("[TXW-A11Y] init failed: " + t)
        }
    }
}
