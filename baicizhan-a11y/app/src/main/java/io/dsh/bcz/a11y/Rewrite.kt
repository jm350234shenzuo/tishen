package io.dsh.bcz.a11y

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** 答题结果改写的开关键。 */
object RewriteKeys {
    const val ENABLED = "rw_enabled"
    const val FULL = "rw_full"
}

/**
 * 答题结果改写：在百词斩把答题记录 Thrift 序列化（结构体 write(TProtocol)）之前，
 * 把这份即将发往服务器的数据改成「正确」。只改写发出去的那份数据，不改本地界面显示。
 */
object Rewrite {

    private const val TAG = "[BCZ-A11Y] "
    private const val GAME = "com.baicizhan.online.game_api."
    private const val HERO = "com.baicizhan.online.hero_api."
    private const val STUDY = "com.baicizhan.online.user_study_api."

    private val targets = listOf(
        GAME + "StudyRecordItem",
        GAME + "SubmitRecordReq",
        GAME + "SubmitAbilityReq",
        GAME + "WordAbilityItem",
        GAME + "FinishRoundReq",
        HERO + "AnswerInfo",
        HERO + "TopicInfo",
        STUDY + "UserDoneWordRecord"
    )

    @Volatile private var count = 0

    fun enabled(): Boolean = try {
        Skip.prefs().getBoolean(RewriteKeys.ENABLED, true)
    } catch (t: Throwable) {
        true
    }

    fun full(): Boolean = try {
        Skip.prefs().getBoolean(RewriteKeys.FULL, false)
    } catch (t: Throwable) {
        false
    }

    fun install(cl: ClassLoader) {
        var hooked = 0
        for (n in targets) {
            try {
                val c = XposedHelpers.findClass(n, cl)
                hookAllMethodsSafe(c, "write", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!enabled()) return
                            apply(n.substringAfterLast('.'), param.thisObject)
                        } catch (t: Throwable) {
                            XposedBridge.log(TAG + "rewrite error: " + t)
                        }
                    }
                })
                hooked++
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "rewrite hook miss: " + n)
            }
        }
        Diag.line("rewrite installed: " + hooked + "/" + targets.size + " structs enabled=" + enabled() + " full=" + full())
    }

    private fun apply(name: String, o: Any?) {
        if (o == null) return
        when (name) {
            "StudyRecordItem" -> {
                var at = i(o, "attempt_times", 0)
                if (at < 1) {
                    w(o, "attempt_times", 1)
                    at = 1
                }
                val ct = i(o, "correct_times", 0)
                if (ct != at) {
                    w(o, "correct_times", at)
                    note(name, "correct_times", ct, at)
                }
                if (z(o, "is_killed")) {
                    wb(o, "is_killed", false)
                    note(name, "is_killed", true, false)
                }
            }
            "AnswerInfo" -> {
                val c = i(o, "correct_opt_index", -1)
                val u = i(o, "user_opt_index", -1)
                if (c >= 0 && u != c) {
                    w(o, "user_opt_index", c)
                    note(name, "user_opt_index", u, c)
                }
            }
            "UserDoneWordRecord" -> {
                val wt = i(o, "wrong_times", 0)
                if (wt != 0) {
                    w(o, "wrong_times", 0)
                    note(name, "wrong_times", wt, 0)
                }
                if (full()) {
                    for (f in listOf("spell_score", "chn_score", "listening_score", "current_score")) {
                        val v = i(o, f, -1)
                        if (v in 2..99) {
                            w(o, f, 100)
                            note(name, f, v, 100)
                        }
                    }
                    val dn = i(o, "done_times", 0)
                    if (dn < 1) {
                        w(o, "done_times", 1)
                        note(name, "done_times", dn, 1)
                    }
                }
            }
            "WordAbilityItem" -> {
                if (!full()) return
                val s = d(o, "score", -1.0)
                val want = if (s <= 1.0) 1.0 else 100.0
                if (s >= 0 && s < want) {
                    wd(o, "score", want)
                    note(name, "score", s, want)
                }
                for (f in listOf("cfa", "cfs", "efau", "efcf", "efd", "efpcf", "efs", "efu", "evcf", "evef")) {
                    val v = d(o, f, -1.0)
                    if (v >= 0 && v < want) {
                        wd(o, f, want)
                        note(name, f, v, want)
                    }
                }
            }
            "FinishRoundReq" -> {
                if (!full()) return
                val cr = i(o, "correctRate", -1)
                if (cr in 0..99) {
                    w(o, "correctRate", 100)
                    note(name, "correctRate", cr, 100)
                }
            }
            else -> {
            }
        }
    }

    private fun note(cls: String, field: String, from: Any?, to: Any?) {
        count++
        if (count <= 50 || count % 20 == 0) {
            Diag.line("rw: " + cls + "." + field + " " + from + " -> " + to + "  (第 " + count + " 次)")
        }
    }

    private fun fld(o: Any, f: String): java.lang.reflect.Field? = try {
        o.javaClass.getField(f)
    } catch (t: Throwable) {
        null
    }

    private fun i(o: Any, f: String, def: Int): Int = try {
        (fld(o, f)?.get(o) as? Number)?.toInt() ?: def
    } catch (t: Throwable) {
        def
    }

    private fun z(o: Any, f: String): Boolean = try {
        (fld(o, f)?.get(o) as? Boolean) ?: false
    } catch (t: Throwable) {
        false
    }

    private fun d(o: Any, f: String, def: Double): Double = try {
        (fld(o, f)?.get(o) as? Number)?.toDouble() ?: def
    } catch (t: Throwable) {
        def
    }

    private fun w(o: Any, f: String, v: Int) {
        try {
            fld(o, f)?.set(o, v)
        } catch (t: Throwable) {
        }
    }

    private fun wb(o: Any, f: String, v: Boolean) {
        try {
            fld(o, f)?.set(o, v)
        } catch (t: Throwable) {
        }
    }

    private fun wd(o: Any, f: String, v: Double) {
        try {
            fld(o, f)?.set(o, v)
        } catch (t: Throwable) {
        }
    }
}
