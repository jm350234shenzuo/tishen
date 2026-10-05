package io.github.jm350234shenzuo.txw.a11y

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.regex.Pattern

/** 配置键：与设置页「判你赢了」开关一一对应。 */
object ForceKeys {
    const val ENABLED = "fw_enabled"
}

/**
 * 「判你赢了」：答错也当成答对，App 因此直接进入下一题（等于跳过，但这一题记为正确）。
 *
 * 四层下手（从最精确到最兜底）：
 *  0) 精确名单 EXACT：base.apk 反编译（com.baicizhan.* 业务层未混淆）拿到的判定入口，按「类名+方法名」直接 hook。
 *     拼写判定 FullSpellAnswerEvaluator/CardSpellEvaluationResult/SpellDiffResult/SpellingFeedbackDelegate；
 *     选择题判定 StudyQuestionFaceModelsKt.isCorrectOption 与各 *OptionUiModel.isCorrect；
 *     StudySessionViewModel 的 playAnswerFeedback/playSpellAnswerFeedback/submitFaceVerdict（首个 boolean 参数改 true）。
 *  1) 学习域扫描 SCOPED：类在答题相关包下时，凡返回 boolean 且名字像判定的方法一律 true，并给「反馈类」方法置参。
 *  2) 全局启发式扫描：引用答题 DTO、或类名像答题页的类，其返回 boolean 的判定方法一律 true（旧逻辑兜底）。
 *  3) 服务器回包：hook 8 个答题 DTO 的 read(TProtocol)，把 correct/right/pass 这类布尔字段改成 true，
 *     并把 user_opt_index 改成 correct_opt_index（选择题＝选了正确答案）；提交报文由 Rewrite.kt 改写。
 *
 * 所有动作都写进诊断日志（行首 force:）；出问题可在设置页一键关掉。
 */
object Force {
    private const val TAG = "[TXW-A11Y] "

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
        "com.baicizhan.app.biz.ui.feature.study.",
        "com.baicizhan.app.biz.ui.feature.spelling.",
        "com.baicizhan.app.biz.ui.component.quiz.",
        "com.baicizhan.app.biz.ui.feature.hardreview.",
        "com.baicizhan.app.study."
    )

    private val ANCHORS = arrayOf(
        "hero_api.AnswerInfo", "hero_api.TopicInfo",
        "game_api.SubmitRecordReq", "game_api.StudyRecordItem", "game_api.SubmitAbilityReq",
        "game_api.WordAbilityItem", "game_api.FinishRoundReq", "user_study_api.UserDoneWordRecord"
    )

    /** 精确目标：[类全名, 方法名]。来自 base.apk（com.jiongji.andriod.card 7.10.22）的 DEX 方法索引。 */
    private val EXACT: Array<Array<String>> = arrayOf(
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.spell.FullSpellAnswerEvaluator", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.spell.FullSpellEvaluationResult", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.spell.CardSpellEvaluationResult", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.spell.SpellDiffResult", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.spelling.delegate.SpellingFeedbackDelegate", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.StudyQuestionFaceModelsKt", "isCorrectOption"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.CakeModeOptionUiModel", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.ContextModeOptionUiModel", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.DeepModeOptionUiModel", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.PictureModeOptionUiModel", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.model.RhymeModeOptionUiModel", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.component.quiz.option.QuizImageOptionItem", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.hardreview.HardReviewChoiceAnswer", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.wiki.model.WikiRealExamQuestionOption", "isCorrect"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.StudySessionViewModel", "playAnswerFeedback"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.StudySessionViewModel", "playSpellAnswerFeedback"),
        arrayOf("com.baicizhan.app.biz.ui.feature.study.StudySessionViewModel", "submitFaceVerdict")
    )

    private val done: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val hitCount: MutableMap<String, Int> = Collections.synchronizedMap(HashMap<String, Int>())
    private val scanning = ThreadLocal<Boolean>()

    @Volatile private var on = true
    @Volatile private var hookedLoad = false
    @Volatile private var session = false
    @Volatile private var scanned = 0
    @Volatile private var forced = 0
    @Volatile private var ticks = 0
    @Volatile private var loader: ClassLoader? = null

    fun isEnabled(): Boolean = try {
        Skip.prefs().getBoolean(ForceKeys.ENABLED, true)
    } catch (t: Throwable) {
        true
    }

    fun install(cl: ClassLoader) {
        on = isEnabled()
        Diag.line("force: install enabled=" + on)
        if (!on) return
        loader = cl
        for (e in EXACT) pending.add(e[0])
        hookLoadClass()
        forceExactNow(cl)
        scheduleTicks()
        hookDtoRead(cl)
        Diag.line("force installed（判你赢了已装载）")
    }

    /** 学习页进出：进入时放开扫描范围（类名被混淆的判定类只有在这个窗口才扫得到）。 */
    fun onActivity(act: android.app.Activity) {
        val n = act.javaClass.name.lowercase()
        val s = n.contains("study") || n.contains("exam") || n.contains("quiz") ||
            n.contains("spell") || n.contains("practice") || n.contains("homework")
        if (s != session) {
            session = s
            Diag.line("force: session=" + session + " (" + act.javaClass.name + ") 累计强制 " + forced + " 个方法")
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
            Diag.line("force: retry tick 已排程（每 8 秒一次，最多 90 次）")
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
            Diag.line("force: 汇总 已挂 " + hooked + "/" + names.size + " 个类，待挂 " + pending.size +
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
                            forceExactClass(c)
                        }
                        if (!interesting(name)) return
                        scan(c)
                    }
                })
            Diag.line("force: loadClass hooked")
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
            forceExactClass(c)
            ok++
        }
        Diag.line("force: exact " + ok + "/" + names.size + " 个类已 hook，待加载 " + pending.size)
    }

    private fun forceExactClass(c: Class<*>) {
        val n = c.name
        val methods = HashSet<String>()
        val found = HashSet<String>()
        for (e in EXACT) if (e[0] == n) methods.add(e[1])
        if (methods.isEmpty()) return
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
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
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
                Diag.line("force: exact hook " + n + "#" + m.name + (if (ai >= 0) " (参数#" + ai + " 置 true)" else " (返回 true)"))
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "force exact hook failed: " + n + "#" + m.name + " " + t)
            }
        }
        for (e in EXACT) if (e[0] == n && !found.contains(e[1]))
            Diag.line("force: exact MISS " + n + "#" + e[1] + " （这个类里没有该方法）")
    }

    /** 命中日志：前 30 次每次都记，之后每 50 次记一次。 */
    private fun hit(key: String) {
        val c = hitCount[key] ?: 0
        hitCount[key] = c + 1
        if (c < 30 || c % 50 == 0) Diag.line("force hit: " + key + " (第 " + (c + 1) + " 次) -> 正确")
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
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
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
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
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
            Diag.line("force: " + n + " -> " + hit + " 个方法强制（" + (if (isScoped(n)) "学习域" else "启发式") + ", anchored=" + anchored + "）")
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
                XposedBridge.hookAllMethods(c, "read", object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        if (on) fix(p.thisObject)
                    }
                })
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "force read hook failed: " + t)
            }
        }
        Diag.line("force: dto read hooked " + n + "/" + ANCHORS.size)
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
        if (changed > 0) Diag.line("force: 回包 " + o.javaClass.simpleName + " 改了 " + changed + " 处 -> 正确")
    }
}
