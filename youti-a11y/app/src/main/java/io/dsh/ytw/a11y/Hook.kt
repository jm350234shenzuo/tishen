package io.dsh.ytw.a11y

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 优题网真机适配：该机运行时里 XposedBridge.hookMethod(Member, XC_MethodHook) 会抛
 *   NoSuchMethodError: No static method hookMethod(Member, XC_MethodHook)
 *   （类解析到 Ljaw/LC/q/ljibKIrRq/jnH/XposedBridge，来自未混淆包的替代实现），
 * 表现为「hook 一个都挂不上但日志不报错」。实测 XposedHelpers.findAndHookMethod 与
 * XposedBridge.hookAllMethods 均正常，故统一走「三级兜底」，成功即返回，全失败才抛出。
 */
internal fun hookMethodSafe(c: Class<*>, m: java.lang.reflect.Method, cb: XC_MethodHook) {
    try {
        val arr = ArrayList<Any?>()
        for (p in m.parameterTypes) arr.add(p)
        arr.add(cb)
        XposedHelpers.findAndHookMethod(c, m.name, *arr.toTypedArray())
        return
    } catch (t: Throwable) {
        // 继续兜底
    }
    try {
        XposedBridge.hookAllMethods(c, m.name, cb)
        return
    } catch (t: Throwable) {
        // 继续兜底
    }
    XposedBridge.hookMethod(m, cb)
}

/** 按名字挂上该类的全部同名方法（含多重重载）；一个都没挂上则抛异常。 */
internal fun hookAllMethodsSafe(c: Class<*>, name: String, cb: XC_MethodHook) {
    var any = false
    var last: Throwable? = null
    for (m in c.declaredMethods) {
        if (m.name != name || m.isSynthetic) continue
        try {
            hookMethodSafe(c, m, cb)
            any = true
        } catch (t: Throwable) {
            last = t
        }
    }
    if (!any) throw last ?: NoSuchMethodException(name)
}
