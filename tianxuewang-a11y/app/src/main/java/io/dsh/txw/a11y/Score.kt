package io.dsh.txw.a11y

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 天学网口语/朗读评分（读单词判分）改写。
 *
 * 证据：APK 里的 libases_engine.so 导出 JNI 符号
 *   Java_com_up366_asecengine_jni_AsesJni_LsxAsesSessionGetResult / SessionBegin /
 *   SessionSpeechIn / SessionEnd / LsxAsesSessionGetSubResource / LsxAsesVersion …
 * 评分引擎本体是 liblsx-ases-export-4.6.1.so（15MB，C++ polly 命名空间，
 * 含 PaWordScorer / KeySentenceRetellScorer / PronNet / WordNet2 等评分器），
 * 即 **本机离线评测**：分数由 com.up366.asecengine.jni.AsesJni 回给 Java 层。
 * 天学网整体被百度加固，业务类静态看不到，但 JNI 桥的类名从 so 符号里就能确定。
 *
 * 本对象把「评测结果」抬到满分：
 *  ①hook JNI 桥 com.up366.asecengine.jni.AsesJni 里所有返回 String 的方法（结果 JSON），
 *    把 JSON 里名字含 score/total/pron/fluency/accuracy/integrity/similar… 的数值改成满分；
 *  ②hook 包名 com.up366.* 且类名像评分的类里，名字像分数的数值 getter（int/long/float/double → 满分）
 *    与判定用 boolean getter（→ true）；
 */
object ScoreKeys {
    const val ENABLED = "sc_enabled"
}

object Score {

    private const val TAG = "[TXW-A11Y] "
    private const val BRIDGE = "com.up366.asecengine.jni.AsesJni"

    /** 真机 probe 文件实测到的天学网业务类（WebView/H5 作业页 + JS 桥），逐个读它们的 String 载荷。 */
    private val PRECISE = arrayOf(
        "com.up366.mobile.book.jsinterface.JSInterfaceV8",
        "com.up366.mobile.book.webview.StudyPageWebView",
        "com.up366.mobile.book.StudyActivity",
        "com.up366.mobile.course.task.JsSdk2023TaskActivity",
        "com.up366.mobile.common.utils.ViewUtil"
    )

    private val KEY = Regex(
        "(?i)(score|total|overall|fluency|accuracy|integrit|pron|precision|completeness|standard|similar|content|rhythm|stress|mark|point|grade|level)"
    )
    // 真机实测：分数走 WebViewJavascriptBridge._callJsFuncWithResult("...jsFunc":"recordStateChange({\"score\":32.84...})")
    // 即 JSON 被塞进 JSON 字符串，键的收尾引号前多一个反斜杠（\"score\"），下面两个正则都允许可选反斜杠。
    private val JSON_NUM = Regex("(\\\\?\"[A-Za-z0-9_]*\\\\?\"\\s*:\\s*)(-?[0-9]+(?:\\.[0-9]+)?)")
    private val JSON_STR = Regex("(\\\\?\"[A-Za-z0-9_]*\\\\?\"\\s*:\\s*\\\\?\")(-?[0-9]+(?:\\.[0-9]+)?)(\\\\?\")")
    /** 名字里带 KEY 字样但不是分数的字段，不抬。 */
    private val BAD = setOf("audio_content", "repeated_content", "content_type", "content_id", "session_content", "low_precision")

    private val hooked = HashSet<String>()

    @Volatile private var cl: ClassLoader? = null
    @Volatile private var dumped = 0
    @Volatile private var boosted = 0
    @Volatile private var probed = 0

    fun install(cl: ClassLoader) {
        val enabled = try {
            Skip.prefs().getBoolean(ScoreKeys.ENABLED, true)
        } catch (t: Throwable) {
            true
        }
        Diag.line("score installed enabled=" + enabled)
        if (!enabled) return
        this.cl = cl
        tryHook(BRIDGE, true)
        for (n in PRECISE) tryHook(n, false)
        hookWebView()
        try {
            XposedBridge.hookAllMethods(ClassLoader::class.java, "loadClass", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val c = param.result as? Class<*> ?: return
                        val n = c.name
                        if (n == BRIDGE) tryHook(n, true)
                        else if (n.startsWith("com.up366.asecengine")) tryHook(n, false)
                        else if (n.startsWith("com.up366.") && KEY.containsMatchIn(n)) tryHook(n, false)
                    } catch (t: Throwable) {
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score loadClass hook failed: " + t)
        }
        Diag.line("score loadClass hooked")
    }

    @Volatile private var ticks = 0

    /** 加固 App 的业务类往往在 WebView 起来后才加载，周期补挂（findClass 失败会从 hooked 移除，可重试）。 */
    private fun retry() {
        tryHook(BRIDGE, true)
        for (n in PRECISE) tryHook(n, false)
    }

    private fun tryHook(name: String, bridge: Boolean) {
        synchronized(hooked) {
            if (!hooked.add(name)) return
        }
        try {
            val ld = cl ?: return
            val c = de.robv.android.xposed.XposedHelpers.findClass(name, ld)
            hookClass(c, bridge)
        } catch (t: Throwable) {
            synchronized(hooked) { hooked.remove(name) }
        }
    }

    private fun hookClass(c: Class<*>, bridge: Boolean) {
        val ms: Array<Method> = try {
            c.declaredMethods
        } catch (t: Throwable) {
            return
        }
        var n = 0
        for (m in ms) {
            try {
                if (Modifier.isAbstract(m.modifiers) || Modifier.isNative(m.modifiers)) continue
                val ret = m.returnType?.name ?: continue
                if (bridge) {
                    if (ret == "java.lang.String") {
                        hookString(m)
                        n++
                    }
                    continue
                }
                if (PRECISE.contains(c.name)) {
                    if (probe(m)) n++
                    continue
                }
                if (!KEY.containsMatchIn(m.name)) continue
                when (ret) {
                    "java.lang.String" -> {
                        hookString(m)
                        n++
                    }
                    "boolean" -> {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                param.result = java.lang.Boolean.TRUE
                            }
                        })
                        n++
                    }
                    "int", "long", "float", "double" -> {
                        hookNumber(m, ret)
                        n++
                    }
                }
            } catch (t: Throwable) {
            }
        }
        if (n > 0) dump("score: hook " + c.name + " -> " + n + " 个方法\n")
    }

    private val WEB = Regex("(?i)(score|total|pron|fluenc|accura|integrit|grade|rating|result)")

    /** 只读式侦查：把这类里返回 String / 收 String 参数的方法载荷写进 score 文件；命中分数 JSON 就顺手抬满分。 */
    private fun probe(m: Method): Boolean {
        val ret = m.returnType.name
        var strArg = false
        for (t in m.parameterTypes) if (t.name == "java.lang.String") strArg = true
        if (ret != "java.lang.String" && !strArg) return false
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val a = param.args ?: return
                    for (i in a.indices) {
                        val v = a[i] as? String ?: continue
                        if (v.length < 4) continue
                        if (probed < 80) {
                            probed++
                            dump("score arg[" + m.name + "/" + i + "]: " + v.take(500) + "\n")
                        }
                    }
                } catch (t: Throwable) {
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val v = param.result as? String ?: return
                    if (v.length < 4) return
                    if (probed < 80) {
                        probed++
                        dump("score ret[" + m.name + "]: " + v.take(600) + "\n")
                    }
                    if (v.contains("{") && KEY.containsMatchIn(v)) {
                        val f = boost(v)
                        if (f != v) {
                            param.result = f
                            dump("score ret boost[" + m.name + "]: " + f.take(400) + "\n")
                        }
                    }
                } catch (t: Throwable) {
                }
            }
        })
        return true
    }

    /** H5 作业页：分数常由 native 通过 evaluateJavascript 塞回页面，这里把那条脚本里的分数也抬满分。 */
    private fun hookWebView() {
        try {
            XposedBridge.hookAllMethods(android.webkit.WebView::class.java, "evaluateJavascript", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val js = param.args?.getOrNull(0) as? String ?: return
                        if (js.length < 8 || !WEB.containsMatchIn(js)) return
                        if (probed < 80) {
                            probed++
                            dump("score js: " + js.take(800) + "\n")
                        }
                        if (++ticks % 25 == 1) retry()
                        val f = boost(js)
                        if (f != js) {
                            param.args[0] = f
                            dump("score js boost: " + f.take(600) + "\n")
                        }
                    } catch (t: Throwable) {
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score webview hook failed: " + t)
        }
        try {
            XposedBridge.hookAllMethods(android.webkit.WebView::class.java, "loadUrl", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val u = param.args?.getOrNull(0) as? String ?: return
                        if (u.length < 8 || !WEB.containsMatchIn(u)) return
                        if (probed < 80) {
                            probed++
                            dump("score url: " + u.take(500) + "\n")
                        }
                    } catch (t: Throwable) {
                    }
                }
            })
        } catch (t: Throwable) {
        }
        dump("score webview hooked\n")
    }

    private fun hookNumber(m: Method, ret: String) {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (ret) {
                    "int" -> param.result = java.lang.Integer.valueOf(100)
                    "long" -> param.result = java.lang.Long.valueOf(100L)
                    "float" -> param.result = java.lang.Float.valueOf(100.0f)
                    "double" -> param.result = java.lang.Double.valueOf(100.0)
                }
            }
        })
    }

    private fun hookString(m: Method) {
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val orig = param.result as? String ?: return
                    if (orig.length < 6) return
                    if (!orig.contains("{") && !orig.contains(":")) return
                    if (dumped < 4) {
                        dumped++
                        dump("score raw[" + m.name + "]: " + orig.take(2000) + "\n")
                    }
                    val fixed = boost(orig)
                    if (fixed != orig) {
                        param.result = fixed
                        if (boosted < 40) {
                            boosted++
                            dump("score boost[" + m.name + "]: " + fixed.take(600) + "\n")
                        }
                    }
                } catch (t: Throwable) {
                }
            }
        })
    }

    /** 把 JSON 里「名字像分数」的数值抬到满分：<=1.5 的按比例抬到 1（保留小数写法），其余抬到 100。 */
    private fun boost(s: String): String {
        var out = JSON_STR.replace(s) { mr ->
            val key = mr.groupValues[1]
            if (!hit(key)) mr.value
            else key + full(mr.groupValues[2], true) + mr.groupValues[3]
        }
        out = JSON_NUM.replace(out) { mr ->
            val key = mr.groupValues[1]
            if (!hit(key)) mr.value
            else key + full(mr.groupValues[2], false)
        }
        return out
    }

    /** 从 "\"score\":" 这样的键片段里取出字段名再判定。 */
    private fun hit(key: String): Boolean {
        val n = key.replace("\\", "").trim().trim('"').trimEnd(':').trim().trim('"').lowercase()
        if (BAD.contains(n)) return false
        return KEY.containsMatchIn(n)
    }

    private fun full(num: String, quoted: Boolean): String {
        val d = num.toDoubleOrNull() ?: return num
        val dec = num.contains('.')
        val v = if (d <= 1.5) 1.0 else 100.0
        val text = if (dec) String.format(java.util.Locale.US, "%.1f", v) else v.toInt().toString()
        return text
    }

    private fun dump(text: String) {
        // 日志文件生成已按用户要求移除
    }
}
