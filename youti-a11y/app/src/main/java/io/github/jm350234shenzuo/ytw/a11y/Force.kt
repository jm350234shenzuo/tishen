package io.github.jm350234shenzuo.ytw.a11y

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import org.json.JSONArray
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.regex.Pattern

/** 配置键：与设置页「判我对」开关一一对应。 */
object ForceKeys {
    const val ENABLED = "fw_enabled"
}

/**
 * 「判我对」：答错也当成答对，App 因此直接进入下一题（等于跳过，但这一题记为正确）。
 *
 * 四层下手（从最精确到最兜底）：
 *  0) 精确名单 EXACT：优题网真机包（com.ytw.app 2.13.27，无加固、业务层未混淆）的 DEX 方法索引取到的判定入口，
 *     按「类名+方法名」直接 hook：WriteWordFragment.isRightAnswer（拼写题唯一的本地对错判定）、
 *     ASAnswers.getIs_right / listen_do_json_bean.Answers.getIs_right（本地判分结果，进提交报文）、
 *     ListenResultInfo/LookScoreInfo/WordAnswerInfo 内层 AnswersBean|RecordBean.isIs_right（服务器回包里的对错）。
 *  1) 答题域扫描 SCOPED：类在 com.ytw.app.ui.childfragment./ui.activites./bean./audio. 下时，
 *     凡返回 boolean 且名字像判定的方法一律 true，并给「反馈类」方法置参。
 *  2) 全局启发式扫描：引用答题类、或类名像答题页的类，其返回 boolean 的判定方法一律 true（兜底）。
 *  3) 朗读/跟读的分数与提交报文由 Score.kt 负责（本文件不管分数）。
 *
 * 所有动作都写进诊断日志（行首 force:）；出问题可在设置页一键关掉。
 */
object Force {
    private const val TAG = "[YTW-A11Y] "

    private val CLASS_PAT = Pattern.compile("(answer|quiz|spell|judge|grade|study|exam|question|topic)", Pattern.CASE_INSENSITIVE)
    private val METHOD_PAT = Pattern.compile("(correct|isright|judge|rightanswer|answerright|checkspell|verifyspell|passed|issuccess|isok)", Pattern.CASE_INSENSITIVE)
    private val SCOPED_PAT = Pattern.compile("(correct|judg|verdict|isright|rightanswer|passed|issuccess)", Pattern.CASE_INSENSITIVE)
    private val SCOPED_ARG_PAT = Pattern.compile("(feedback|verdict|submitspell|submitanswer|answerfeedback|completeanswer|submitface)", Pattern.CASE_INSENSITIVE)
    private val SKIP_PKG = arrayOf(
        "android.", "androidx.", "java.", "javax.", "kotlin", "org.", "de.robv.", "io.github.jm350234shenzuo.",
        "com.google.", "com.facebook.", "com.squareup.", "okhttp3.", "sun.", "libcore.", "dalvik.", "com.android."
    )

    /** 答题相关包（业务层未混淆，按包做「宽方法名」扫描）。 */
    private val SCOPED_PKG = arrayOf(
        "com.ytw.app.ui.childfragment.",
        "com.ytw.app.ui.activites.",
        "com.ytw.app.bean.",
        "com.ytw.app.audio."
    )

    private val ANCHORS = arrayOf<String>()

    /** 精确目标：[类全名, 方法名]。来自优题网真机包 com.ytw.app 2.13.27 的 DEX 方法索引。 */
    private val EXACT: Array<Array<String>> = arrayOf(
        // 拼写题本地判定（优题网唯一一处本地对错判定）
        arrayOf("com.ytw.app.ui.childfragment.word.WriteWordFragment", "isRightAnswer"),
        // 客观题本地判分结果（提交报文用）
        arrayOf("com.ytw.app.bean.answer_bean.ASAnswers", "getIs_right"),
        arrayOf("com.ytw.app.bean.answer_bean.ASAnswers", "setIs_right"),
        arrayOf("com.ytw.app.bean.answer_bean.ASRecord", "getIs_right"),
        arrayOf("com.ytw.app.bean.answer_bean.ASRecord", "setIs_right"),
        arrayOf("com.ytw.app.bean.function_bean.MineListenRecordBean", "getIs_right"),
        arrayOf("com.ytw.app.bean.listen_do_json_bean.Answers", "getIs_right"),
        // 服务器回包里的对错（成绩单/结果页显示用）
        arrayOf("com.ytw.app.bean.function_bean.ListenResultInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean\$AnswersBean", "isIs_right"),
        arrayOf("com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean\$AnswersBean", "isIs_right"),
        arrayOf("com.ytw.app.bean.function_bean.WordAnswerInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$RecordBean", "isIs_right")
    )

    private val done: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val hitCount: MutableMap<String, Int> = Collections.synchronizedMap(HashMap<String, Int>())
    /** 只记日志、不改行为：看清选择题流程到底调了哪些方法（adjsutAnswer 等）。 */
    private val TRACE: Array<Array<String>> = arrayOf(
        arrayOf("com.ytw.app.ui.childfragment.word.LookWordSelectMeanFragment", "adjsutAnswer"),
        arrayOf("com.ytw.app.ui.childfragment.word.LookWordSelectMeanFragment", "submit"),
        arrayOf("com.ytw.app.ui.childfragment.word.LookWordSelectMeanFragment", "redoSubmit"),
        arrayOf("com.ytw.app.ui.childfragment.word.LookWordSelectMeanFragment", "submitFail"),
        arrayOf("com.ytw.app.ui.childfragment.word.LookWordSelectMeanFragment", "setUserVisibleHint"),
        arrayOf("com.ytw.app.ui.childfragment.word.SeeMeanSelectWordFragement", "adjsutAnswer"),
        arrayOf("com.ytw.app.ui.childfragment.word.SeeMeanSelectWordFragement", "submit"),
        arrayOf("com.ytw.app.ui.activites.wordandreadtext.word.DoWordActivity", "lookAnswer"),
        arrayOf("com.ytw.app.ui.activites.wordandreadtext.word.DoWordActivity", "initLookWordSelectAnswerData")
    )
    private val tracePending = Collections.synchronizedSet(HashSet<String>())

    @Volatile private var traceCount = 0

    private val scanning = ThreadLocal<Boolean>()

    @Volatile private var on = true
    @Volatile private var hookedLoad = false
    @Volatile private var session = false
    @Volatile private var scanned = 0
    @Volatile private var forced = 0
    @Volatile private var ticks = 0
    @Volatile private var exactHooked = 0
    @Volatile private var loader: ClassLoader? = null

    fun isEnabled(): Boolean = try {
        Skip.prefs().getBoolean(ForceKeys.ENABLED, true)
    } catch (t: Throwable) {
        true
    }

    /** 原自带日志（写 ytw-a11y-force.txt）已按用户要求移除，保留空实现以兼容调用点。 */
    private fun flog(msg: String) {
    }

    fun install(cl: ClassLoader) {
        on = isEnabled()
        flog("force: install enabled=" + on)
        if (!on) return
        loader = cl
        for (e in EXACT) pending.add(e[0])
        hookLoadClass()
        forceExactNow(cl)
        try {
            hookTrace(cl)
        } catch (t: Throwable) {
            flog("force: hookTrace FAIL " + t)
        }
        scheduleTicks()
        hookDtoRead(cl)
        flog("force installed（判我对已装载）")
    }

    /** 学习页进出：进入时放开扫描范围（类名被混淆的判定类只有在这个窗口才扫得到）。 */
    fun onActivity(act: android.app.Activity) {
        val n = act.javaClass.name.lowercase()
        val s = n.contains("study") || n.contains("exam") || n.contains("quiz") ||
            n.contains("spell") || n.contains("practice") || n.contains("homework")
        if (s != session) {
            session = s
            flog("force: session=" + session + " (" + act.javaClass.name + ") 累计强制 " + forced + " 个方法")
        }
        if (s) tick()
    }

    /**
     * 周期性补挂：类可能由自定义 ClassLoader 加载（此时 ClassLoader.loadClass 的 hook 不会触发），
     * 也可能在模块 install 之后才被加载，所以每 8 秒重试一次，并把「已挂/待挂」写进日志。
     */
    private fun scheduleTicks() {
        try {
            val h = Handler(Looper.getMainLooper())
            val r = object : Runnable {
                override fun run() {
                    try {
                        tick()
                    } catch (t: Throwable) {
                    }
                    if (on && ticks < 90) h.postDelayed(this, 8000L)
                }
            }
            h.postDelayed(r, 8000L)
            flog("force: retry tick 已排程（每 8 秒一次，最多 90 次）")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "force schedule failed: " + t)
        }
    }

    /** 一次补挂 + 每 5 次写一条汇总（已挂多少、还差哪些）。 */
    fun tick() {
        if (!on) return
        ticks++
        val cl = loader ?: return
        try {
            forceExactNow(cl)
        } catch (t: Throwable) {
        }
        if (ticks % 5 == 1) {
            val names = HashSet<String>()
            for (e in EXACT) names.add(e[0])
            var hooked = 0
            for (n in names) if (done.contains(n)) hooked++
            flog("force: 汇总 已处理 " + hooked + "/" + names.size + " 个类，实际挂上 " + exactHooked + " 个方法，待挂 " + pending.size +
                (if (pending.isEmpty()) "" else " " + pending.joinToString(",")) +
                "；累计强制 " + forced + " 个方法，精确命中 " + hitCount.size + " 处")
        }
    }

    private fun hookLoadClass() {
        if (hookedLoad) return
        hookedLoad = true
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java, "loadClass", String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!on) return
                        val name = param.args[0] as? String ?: return
                        if (name.startsWith("[")) return
                        val c = param.result as? Class<*> ?: return
                        if (pending.remove(name)) {
                            done.add(name)
                            exactHooked += forceExactClass(c)
                        }
                        if (tracePending.remove(name)) traceClass(c)
                        if (!interesting(name)) return
                        scan(c)
                    }
                })
            flog("force: loadClass hooked")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "force loadClass hook failed: " + t)
        }
    }

    private fun interesting(n: String): Boolean {
        for (p in SKIP_PKG) if (n.startsWith(p)) return false
        if (isScoped(n)) return true
        if (CLASS_PAT.matcher(n).find()) return true
        return session
    }

    private fun isScoped(n: String): Boolean {
        for (p in SCOPED_PKG) if (n.startsWith(p)) return true
        return false
    }

    private fun isAnchor(n: String): Boolean {
        for (d in ANCHORS) if (n == d) return true
        return false
    }

    /** 第 0 层：按精确类名+方法名 hook（能确定命中的主路径）。 */
    private fun forceExactNow(cl: ClassLoader) {
        val names = HashSet<String>()
        for (e in EXACT) names.add(e[0])
        var ok = 0
        var hookedMethods = 0
        for (n in names) {
            if (done.contains(n)) continue
            val c = try {
                XposedHelpers.findClass(n, cl)
            } catch (t: Throwable) {
                null
            }
            if (c == null) {
                pending.add(n)
                continue
            }
            done.add(n)
            pending.remove(n)
            hookedMethods += forceExactClass(c)
            ok++
        }
        exactHooked = hookedMethods
        flog("force: exact " + ok + "/" + names.size + " 个类已处理，实际挂上 " + hookedMethods + " 个方法，待加载 " + pending.size)
    }

    private fun forceExactClass(c: Class<*>): Int {
        var hooked = 0
        val n = c.name
        val methods = HashSet<String>()
        val found = HashSet<String>()
        for (e in EXACT) if (e[0] == n) methods.add(e[1])
        if (methods.isEmpty()) return 0
        for (m in c.declaredMethods) {
            if (!methods.contains(m.name)) continue
            found.add(m.name)
            if (Modifier.isAbstract(m.modifiers) || Modifier.isNative(m.modifiers)) continue
            val rt = m.returnType
            val isBool = rt == Boolean::class.javaPrimitiveType || rt == java.lang.Boolean::class.java
            var argIndex = -1
            if (!isBool) {
                val ps = m.parameterTypes
                for (i in ps.indices) {
                    if (ps[i] == Boolean::class.javaPrimitiveType) {
                        argIndex = i
                        break
                    }
                }
            }
            val ai = argIndex
            try {
                hookMethodSafe(c, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!on) return
                        try {
                            if (ai >= 0) {
                                p.args[ai] = java.lang.Boolean.TRUE
                            } else {
                                p.result = java.lang.Boolean.TRUE
                            }
                            hit(n + "#" + m.name)
                        } catch (t: Throwable) {
                        }
                    }
                })
                hooked++
                flog("force: exact hook " + n + "#" + m.name + (if (ai >= 0) " (参数#" + ai + " 置 true)" else " (返回 true)"))
            } catch (t: Throwable) {
                flog("force: exact hook FAIL " + n + "#" + m.name + " " + t)
            }
        }
        for (e in EXACT) if (e[0] == n && !found.contains(e[1]))
            flog("force: exact MISS " + n + "#" + e[1] + " （这个类里没有该方法）")
        return hooked
    }

    /** 只挂日志钩子：把选择题流程里被调用的方法和参数写进 force 日志（不改任何行为）。 */
    private fun hookTrace(cl: ClassLoader) {
        val names = HashSet<String>()
        for (e in TRACE) names.add(e[0])
        var ok = 0
        for (n in names) {
            val c = try {
                XposedHelpers.findClass(n, cl)
            } catch (t: Throwable) {
                null
            }
            if (c == null) {
                tracePending.add(n)
                continue
            }
            ok += traceClass(c)
        }
        flog("force: trace " + ok + " 个方法已挂，待加载 " + tracePending.size + " 个类")
    }

    private fun traceClass(c: Class<*>): Int {
        val names = HashSet<String>()
        for (e in TRACE) if (e[0] == c.name) names.add(e[1])
        if (names.isEmpty()) return 0
        var k = 0
        for (m in c.declaredMethods) {
            if (!names.contains(m.name)) continue
            if (Modifier.isAbstract(m.modifiers) || Modifier.isNative(m.modifiers)) continue
            try {
                hookMethodSafe(c, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!on) return
                        if (m.name == "initLookWordSelectAnswerData") {
                            // ① 先按原始 is_right 登记正确答案（供 Net 层提交前改写）
                            // ② 再把题面里所有 is_right 置 true —— 本地判定也会认为选对了
                            try {
                                val a0 = p.args?.let { if (it.isNotEmpty()) it[0] else null }
                                if (a0 is JSONObject) {
                                    val reg = Ans.remember(a0)
                                    if (reg > 0) flog("force ans: 登记 " + reg + " 条正确答案（info_id/item_id 映射），共 " + Ans.size + " 条")
                                    val f = allRight(a0)
                                    if (f > 0) flog("force ans: 题面 is_right 全部置 true（" + f + " 处），本地判定按选对走")
                                }
                            } catch (t: Throwable) {
                            }
                        }
                        if (traceCount >= 80) return
                        traceCount++
                        try {
                            flog("force trace: " + c.name + "#" + m.name + " args=" + argsOf(p.args))
                        } catch (t: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (!on) return
                        if (m.name != "adjsutAnswer") return
                        try {
                            flog("force trace: " + c.name + "#" + m.name + " fields=" + fieldsOf(p.thisObject))
                        } catch (t: Throwable) {
                        }
                    }
                })
                k++
            } catch (t: Throwable) {
                flog("force: trace FAIL " + c.name + "#" + m.name + " " + t)
            }
        }
        return k
    }

    /** 把题面 JSON 里所有 is_right / isRight 置 true，返回改动处数。 */
    private fun allRight(o: Any?): Int {
        var n = 0
        when (o) {
            is JSONObject -> {
                val keys = ArrayList<String>()
                val it = o.keys()
                while (it.hasNext()) keys.add(it.next())
                for (k in keys) {
                    if (k.equals("is_right", true) || k.equals("isRight", true)) {
                        o.put(k, true)
                        n++
                    } else {
                        n += allRight(o.opt(k))
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until o.length()) n += allRight(o.opt(i))
            }
        }
        return n
    }

    private fun fieldsOf(o: Any?): String {
        if (o == null) return "-"
        val sb = StringBuilder()
        var i = 0
        var c: Class<*>? = o.javaClass
        var depth = 0
        while (c != null && depth < 3) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (i++ >= 24) {
                    sb.append(" …")
                    return sb.toString()
                }
                try {
                    f.isAccessible = true
                    val v = f.get(o)
                    val s = v?.toString() ?: "null"
                    if (sb.isNotEmpty()) sb.append(", ")
                    sb.append(f.name).append("=").append(if (s.length > 220) s.substring(0, 220) + "…" else s)
                } catch (t: Throwable) {
                }
            }
            c = c.superclass
            depth++
        }
        return sb.toString()
    }

    private fun argsOf(a: Array<Any?>?): String {
        if (a == null) return "-"
        val sb = StringBuilder()
        var i = 0
        for (v in a) {
            if (i++ >= 4) {
                sb.append(", …")
                break
            }
            if (sb.isNotEmpty()) sb.append(", ")
            val s = v?.toString() ?: "null"
            val cn = v?.javaClass?.name ?: ""
            val big = cn == "org.json.JSONObject" || cn == "org.json.JSONArray" || (v is String && s.trim().startsWith("{"))
            val lim = if (big) 20000 else 120
            sb.append(v?.javaClass?.simpleName ?: "null").append("=")
                .append(if (s.length > lim) s.substring(0, lim) + "…[+" + (s.length - lim) + "]" else s)
        }
        return sb.toString()
    }

    /** 命中日志：前 30 次每次都记，之后每 50 次记一次。 */
    private fun hit(key: String) {
        val c = hitCount[key] ?: 0
        hitCount[key] = c + 1
        if (c < 30 || c % 50 == 0) flog("force hit: " + key + " (第 " + (c + 1) + " 次) -> 正确")
    }

    /** 第 1/2 层：扫描一个类，把判定方法强制成 true。 */
    private fun scan(c: Class<*>) {
        val n = c.name
        if (scanning.get() == true) return
        if (!done.add(n)) return
        if (scanned > 4000) return
        scanned++
        scanning.set(true)
        var hit = 0
        var anchored = false
        try {
            val scoped = isScoped(n)
            val pat = if (scoped) SCOPED_PAT else METHOD_PAT
            for (m in c.declaredMethods) {
                try {
                    val rt = m.returnType
                    val isBool = rt == Boolean::class.javaPrimitiveType || rt == java.lang.Boolean::class.java
                    if (m.isSynthetic || Modifier.isAbstract(m.modifiers) || Modifier.isNative(m.modifiers)) continue
                    if (isBool) {
                        if (m.parameterTypes.size > 2) continue
                        var a = false
                        for (p in m.parameterTypes) if (isAnchor(p.name)) {
                            a = true
                            break
                        }
                        if (isAnchor(rt.name)) a = true
                        val byName = pat.matcher(m.name).find()
                        if (!a && !byName) continue
                        if (a) anchored = true
                        hookMethodSafe(c, m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) {
                                if (on) p.result = java.lang.Boolean.TRUE
                            }
                        })
                        hit++
                    } else if (scoped) {
                        // 反馈/提交类方法：首个 boolean 参数是「答对了没有」，改成 true
                        val ps = m.parameterTypes
                        if (ps.isEmpty()) continue
                        var bi = -1
                        for (i in ps.indices) if (ps[i] == Boolean::class.javaPrimitiveType) {
                            bi = i
                            break
                        }
                        if (bi < 0) continue
                        if (!SCOPED_ARG_PAT.matcher(m.name).find()) continue
                        hookMethodSafe(c, m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: MethodHookParam) {
                                if (!on) return
                                try {
                                    p.args[bi] = java.lang.Boolean.TRUE
                                } catch (t: Throwable) {
                                }
                            }
                        })
                        hit++
                    }
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        } finally {
            scanning.set(false)
        }
        if (hit > 0) {
            forced += hit
            flog("force: " + n + " -> " + hit + " 个方法强制（" + (if (isScoped(n)) "学习域" else "启发式") + ", anchored=" + anchored + "）")
        }
    }

    private fun hookDtoRead(cl: ClassLoader) {
        var n = 0
        for (d in ANCHORS) {
            val c = try {
                XposedHelpers.findClass(d, cl)
            } catch (t: Throwable) {
                null
            } ?: continue
            n++
            try {
                hookAllMethodsSafe(c, "read", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (on) fix(p.thisObject)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "force read hook failed: " + t)
            }
        }
        flog("force: dto read hooked " + n + "/" + ANCHORS.size)
    }

    /** 把回包里的「对错」改成正确。 */
    private fun fix(o: Any?) {
        if (o == null) return
        var changed = 0
        try {
            for (f in o.javaClass.fields) {
                val nm = f.name.lowercase()
                if (f.type != Boolean::class.javaPrimitiveType && f.type != java.lang.Boolean::class.java) continue
                if (!(nm.contains("correct") || nm.contains("right") || nm.contains("pass") ||
                        nm.contains("success") || nm == "ok" || nm == "is_ok")) continue
                try {
                    if (f.get(o) != java.lang.Boolean.TRUE) {
                        f.set(o, java.lang.Boolean.TRUE)
                        changed++
                    }
                } catch (t: Throwable) {
                }
            }
        } catch (t: Throwable) {
        }
        try {
            val u = o.javaClass.getField("user_opt_index")
            val c = o.javaClass.getField("correct_opt_index")
            val want = c.getInt(o)
            if (u.getInt(o) != want) {
                u.setInt(o, want)
                changed++
            }
        } catch (t: Throwable) {
        }
        if (changed > 0) flog("force: 回包 " + o.javaClass.simpleName + " 改了 " + changed + " 处 -> 正确")
    }
}
