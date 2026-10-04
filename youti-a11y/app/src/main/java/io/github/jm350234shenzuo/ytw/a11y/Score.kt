package io.github.jm350234shenzuo.ytw.a11y

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.regex.Pattern

/** 配置键：与设置页「朗读/跟读评分改满分」开关一一对应。 */
object ScoreKeys {
    const val ENABLED = "sc_enabled"
}

/**
 * 优题网「朗读/跟读/口语评测 → 判满分」。
 *
 * 优题网没有加固、类名未混淆，评测与提交链路已静态确认：
 *  1) 评测本体＝本机离线「智言」SDK：com.ytw.app.audio.EvalUtils（字段 mEval: com.zhiyan.speech_eval_sdk.SpeechEval）。
 *     结果 JSON 的收敛点：convertZhiYanToLegacy(JSONObject) -> JSONObject，再由 onResult(JSONObject) / postSuccess(JSONObject)
 *     抛给上层，并会经 onResult(String, boolean)（WebView 桥）回传 H5。
 *  2) 提交给服务器的报文＝com.ytw.app.bean.function_bean.ReadTextSubmitInfo
 *     （DetailsBean.score / DetailsBean.fluency{overall,pause,speed} / DetailsBean.snt_details[].score /
 *      SoundBean{score,fluency,integrity,pron}），界面展示用 ReadTextScoreBean。
 *
 * 因此两层下手：
 *  A. 结果 JSON：把 EvalUtils 里流出的每个 JSON 串里「名字像分数」的数字抬到满分（≤1.5 → 1，其余 → 100）。
 *  B. 报文/界面 getter：对提交与展示 Bean 的分数 getter 直接返回满分（fastjson 序列化时读的就是这些 getter，
 *     所以上传给服务器的也变成满分）。
 *
 */
object Score {
    private const val TAG = "[YTW-A11Y] "

    private const val BUILD = "YTW-20261003T2340"
    private const val STAR = "com.ytw.app.ui.view.StarBarView"
    private const val EVAL = "com.ytw.app.audio.EvalUtils"
    private const val PKG = "com.ytw.app."

    /** 名字像分数的键。 */
    private val KEY = Pattern.compile(
        "(score|total|overall|fluency|integrit|pron|accur|full|percent|precision|rank|completeness|standard)",
        Pattern.CASE_INSENSITIVE
    )

    /** 命中名字但绝不是分数的键（避免把音频/时长/ID 一起改坏）。 */
    private val BAD = setOf(
        "low_precision", "audio_content", "repeated_content", "content_type", "content_id", "session_content",
        "sound_id", "sound_url", "origin_audio_url", "mine_audio_url", "reftext", "coretype", "attachaudiourl",
        "evaltime", "record_id", "score_id", "item_id", "start", "end", "dur", "dp_type", "sort", "type",
        "answer_id", "qs_id", "info_id", "pager_id", "question_sort", "time_start", "time_end"
    )

    /** 允许键前面有一个反斜杠：分数 JSON 常被塞进 JSON 字符串里，键写作 \"score\"。 */
    private val JSON_NUM = Pattern.compile("(\\\\?\"[A-Za-z0-9_]*\\\\?\"\\s*:\\s*)(-?[0-9]+(?:\\.[0-9]+)?)")
    private val JSON_STR = Pattern.compile("(\\\\?\"[A-Za-z0-9_]*\\\\?\"\\s*:\\s*\\\\?\")(-?[0-9]+(?:\\.[0-9]+)?)(\\\\?\")")

    /** 返回 String 的分数 getter：[类全名, 方法名]。 */
    private val FULL_STRING = arrayOf(
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$SoundBean#getScore",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$SoundBean#getFluency",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$SoundBean#getIntegrity",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$SoundBean#getPron",
        "com.ytw.app.bean.function_bean.ReadTextScoreBean#getScore",
        "com.ytw.app.bean.function_bean.ReadTextScoreBean#getFlucey",
        "com.ytw.app.bean.function_bean.ReadTextScoreBean#getIntegrity",
        "com.ytw.app.bean.function_bean.ReadTextScoreBean#getPron",
        "com.ytw.app.bean.function_bean.ListenRecordScoreBean#getScore"
    )

    /** 返回数值的分数 getter：[类全名, 方法名]。 */
    private val FULL_NUM = arrayOf(
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$DetailsBean#getScore",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$DetailsBean\$FluencyBean#getOverall",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$DetailsBean\$FluencyBean#getPause",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$DetailsBean\$FluencyBean#getSpeed",
        "com.ytw.app.bean.function_bean.ReadTextSubmitInfo\$DetailsBean\$SntDetailsBean#getScore",
        "com.ytw.app.bean.WordScoreBean#getFull",
        "com.ytw.app.bean.WordScoreBean#getTotal",
        "com.ytw.app.bean.WordScoreBean#getPercentage",
        "com.ytw.app.bean.WordScoreBean\$TypeListBean#getFull",
        "com.ytw.app.bean.WordScoreBean\$TypeListBean#getTotal",
        "com.ytw.app.bean.function_bean.ScoreReportBean#getFull",
        "com.ytw.app.bean.function_bean.ScoreReportBean#getTotal",
        "com.ytw.app.bean.function_bean.ScoreReportBean\$TypeListBean#getFull",
        "com.ytw.app.bean.function_bean.ScoreReportBean\$TypeListBean#getTotal",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean#getScore",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean#getFull",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean#getTotal",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean\$SoundBean#getScore",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean\$SoundBean#getFluency",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean\$SoundBean#getIntegrity",
        "com.ytw.app.bean.function_bean.LookScoreInfo\$DataBeanX\$DataBean\$QuestionsBean\$InfosBean\$ItemsBean\$SoundBean#getPron",
        // ---- 读单词/朗读：提交报文里的 sound 节点与 items 分数（直接决定上传给服务器的分数）
        "com.ytw.app.bean.answer_bean.ASSound#getScore",
        "com.ytw.app.bean.answer_bean.ASSound#getFluency",
        "com.ytw.app.bean.answer_bean.ASSound#getIntegrity",
        "com.ytw.app.bean.answer_bean.ASSound#getPron",
        "com.ytw.app.bean.answer_bean.ASItems#getScore",
        "com.ytw.app.bean.answer_bean.ASItems#getFull",
        "com.ytw.app.bean.answer_bean.ASBigData#getFull",
        "com.ytw.app.bean.answer_bean.ASBigData#getTotal",
        "com.ytw.app.bean.answer_bean.ASBigData#getPercentage",
        "com.ytw.app.bean.listen_do_json_bean.Items#getScore",
        // ---- 云端评测回包 Bean
        "com.ytw.app.bean.function_bean.PingCeResultBean\$ResultBean\$DetailsBean#getScore",
        "com.ytw.app.bean.function_bean.PingCeResultBean\$ResultBean\$DetailsBean\$SntDetailsBean#getScore",
        "com.ytw.app.bean.function_bean.ReadTextPingCeInfo\$ResultBean\$DetailsBean#getScore",
        "com.ytw.app.bean.function_bean.ReadTextPingCeInfo\$ResultBean\$DetailsBean\$SntDetailsBean#getScore",
        "com.ytw.app.bean.ping_ce.PingCeDetails#getScore",
        "com.ytw.app.bean.ping_ce.PingCeSnt_details#getScore",
        "com.ytw.app.bean.sentence_ping_ce.SenDetails#getScore",
        "com.ytw.app.bean.sentence_ping_ce.SenStatics#getScore"
    )

    @Volatile private var enabled = true
    @Volatile private var cl: ClassLoader? = null
    @Volatile private var count = 0
    private val hooked = Collections.synchronizedSet(HashSet<String>())

    // ---------------------------------------------------------------- 对外入口

    fun install(loader: ClassLoader) {
        try {
            cl = loader
            enabled = readEnabled()
            log("score build " + BUILD + " installed enabled=" + enabled + " pkg=" + PKG)
            hookEval(loader)
            for (t in FULL_STRING) tryHook(t, loader)
            for (t in FULL_NUM) tryHook(t, loader)
            installUi(loader)
            installSubmit(loader)
            hookLoadClass(loader)
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score install failed: " + t)
            log("score install FAILED: " + t)
        }
    }

    private fun readEnabled(): Boolean = try {
        Skip.prefs().getBoolean(ScoreKeys.ENABLED, true)
    } catch (t: Throwable) {
        true
    }

    // ---------------------------------------------------------------- A. 结果 JSON

    private fun hookEval(loader: ClassLoader) {
        val c = try {
            XposedHelpers.findClass(EVAL, loader)
        } catch (t: Throwable) {
            log("score: EvalUtils 还没加载，等 loadClass 补挂")
            return
        }
        log("score eval: EvalUtils 已加载，declaredMethods=" + c.declaredMethods.size)
        for (m in c.declaredMethods) {
            if (Modifier.isAbstract(m.modifiers) || m.isSynthetic) continue
            val pt = m.parameterTypes
            try {
                when (m.name) {
                    "convertZhiYanToLegacy", "convertCharactersToDetails", "convertCharactersToDetailsGroup" -> {
                        hookMethodSafe(c, m, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                val o = p.result
                                if (o == null) return
                                val b = boostObj(o)
                                if (b !== o) p.result = b
                            }
                        })
                        log("score: hook " + c.name + "#" + m.name + " -> 结果 JSON 抬满分")
                    }
                    "onResult", "postSuccess", "onRealtimeResult" -> {
                        hookMethodSafe(c, m, object : XC_MethodHook() {
                            override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                                if (p.args == null || p.args.isEmpty()) return
                                val o = p.args[0]
                                if (o == null) return
                                val b = boostObj(o)
                                if (b !== o) p.args[0] = b
                            }
                        })
                        log("score: hook " + c.name + "#" + m.name + " -> 参数抬满分")
                    }
                }
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "score eval hook " + m.name + ": " + t)
                log("score eval hook FAIL " + m.name + ": " + t)
            }
        }
    }

    /** JSONObject / JSONArray / 含 JSON 的 String 一律把分数抬到满分。 */
    private fun boostObj(o: Any?): Any? {
        if (!enabled || o == null) return o
        return try {
            when (o) {
                is JSONObject -> JSONObject(boostText(o.toString()))
                is JSONArray -> JSONArray(boostText(o.toString()))
                is String -> if (o.indexOf('{') < 0 && o.indexOf('[') < 0) o else boostText(o)
                else -> o
            }
        } catch (t: Throwable) {
            o
        }
    }

    /** 把 JSON 串里名字像分数的数字抬到满分。 */
    fun boostText(text: String): String {
        if (!enabled || text.isEmpty()) return text
        var changed = false
        val sb = StringBuffer()
        val m = JSON_NUM.matcher(text)
        while (m.find()) {
            if (!hit(m.group(2)?.let { keyOf(text, m.start()) } ?: "")) continue
            val v = m.group(2)?.toDoubleOrNull() ?: continue
            val full = if (v <= 1.5) "1" else "100"
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement((m.group(1) ?: "") + full))
            changed = true
        }
        m.appendTail(sb)
        val t2 = sb.toString()
        val sb2 = StringBuffer()
        val m2 = JSON_STR.matcher(t2)
        while (m2.find()) {
            if (!hit(m2.group(2)?.let { keyOf(t2, m2.start()) } ?: "")) continue
            val v = m2.group(2)?.toDoubleOrNull() ?: continue
            val full = if (v <= 1.5) "1" else "100"
            m2.appendReplacement(sb2, java.util.regex.Matcher.quoteReplacement((m2.group(1) ?: "") + full + (m2.group(3) ?: "")))
            changed = true
        }
        m2.appendTail(sb2)
        val out = sb2.toString()
        if (changed) {
            count++
            if (count <= 30 || count % 50 == 0) {
                log("score boost[" + count + "] " + one(out, 600))
            }
        }
        return out
    }

    /** 从匹配位置往前找最近的 key。 */
    private fun keyOf(text: String, at: Int): String {
        var i = at - 1
        while (i >= 0) {
            val c = text[i]
            if (c == '{' || c == ',') break
            i--
        }
        var s = i + 1
        var e = text.indexOf(':', at - 1)
        if (e < 0) e = at
        s = if (s in (i + 1)..e) s else i + 1
        val seg = text.substring(s.coerceAtLeast(0), e.coerceAtMost(text.length))
        return seg
    }

    private fun hit(key: String): Boolean {
        if (key.isEmpty()) return false
        val n = key.replace("\\", "").trim().trim('"').trimEnd(':').trim().trim('"').lowercase()
        if (n.isEmpty() || BAD.contains(n)) return false
        return KEY.matcher(n).find()
    }

    // ---------------------------------------------------------------- B. 报文 / 界面 getter

    private fun tryHook(target: String, loader: ClassLoader) {
        val i = target.lastIndexOf('#')
        if (i < 0) return
        val cn = target.substring(0, i)
        val mn = target.substring(i + 1)
        if (!hooked.add(target)) return
        try {
            val c = XposedHelpers.findClass(cn, loader)
            tryHookClass(c, mn)
        } catch (t: Throwable) {
            hooked.remove(target)
            XposedBridge.log(TAG + "score: " + cn + " 还没加载（等补挂）")
        }
    }

    private fun hookLoadClass(loader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "java.lang.ClassLoader", loader, "loadClass", String::class.java, Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                        val n = p.args[0] as? String ?: return
                        if (!n.startsWith(PKG)) return
                        if (n == EVAL) hookEval(loader)
                        for (t in FULL_STRING) if (t.startsWith(n + "#")) tryHook(t, loader)
                        for (t in FULL_NUM) if (t.startsWith(n + "#")) tryHook(t, loader)
                        if (uiPending.contains(n)) {
                            log("score ui: 界面类已加载 " + n)
                            uiExactNow(loader)
                        }
                        if (submitPending.contains(n)) {
                            log("score submit: 类已加载 " + n)
                            retrySubmit(loader)
                        }
                    }
                }
            )
            log("score loadClass hooked")
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score loadClass hook failed: " + t)
        }
    }


    // ---------------------------------------------------------------- C. 读单词 / 朗读 界面层

    /**
     * 界面层精确锚点：优题网各「读」场景的评分回调入口。
     * 读单词 ReadWordFragment、读课文 ReadTextFragment、音标 DoSoundMarkFragment / SoundMarkOneFragment、
     * 句子跟读 DoSentenceToReadActivity / SentenceToReadListActivity、趣味配音 DoFunVoiceActivity、
     * 听力跟读 QuestionInfoFragment、TestSoundActivity，外加星级条 StarBarView#setStarMark。
     *
     * 处理：入口参数里的 JSON/String 先用 A 层同一套规则抬满分（让后续 UI 与提交都从满分起算），
     * 返回后把界面上的分数文本与星级强制拉满（应对分数由本地变量/控件直接设置、不走 JSON 的情况）。
     */
    private val UI_ANCHORS = arrayOf(
        "com.ytw.app.ui.childfragment.word.ReadWordFragment#showPingCeResult",
        "com.ytw.app.ui.childfragment.word.ReadWordFragment#detalPingCeResultUI",
        "com.ytw.app.ui.childfragment.word.ReadWordFragment#detalSubmitResultUI",
        "com.ytw.app.ui.childfragment.read_text.ReadTextFragment#showPingCeResult",
        "com.ytw.app.ui.childfragment.read_text.ReadTextFragment#detalSubmitResultUI",
        "com.ytw.app.ui.childfragment.sound_mark.DoSoundMarkFragment#showPingCeResult",
        "com.ytw.app.ui.childfragment.sound_mark.DoSoundMarkFragment#detalPingCeResultUI",
        "com.ytw.app.ui.childfragment.sound_mark.DoSoundMarkFragment#detalSubmitResultUI",
        "com.ytw.app.ui.childfragment.sound_mark.SoundMarkOneFragment#showPingCeResult",
        "com.ytw.app.ui.childfragment.sound_mark.SoundMarkOneFragment#detalPingCeResultUI",
        "com.ytw.app.ui.childfragment.listen.QuestionInfoFragment#detalPingCeResultUI",
        "com.ytw.app.ui.activites.sentence_toread.DoSentenceToReadActivity#showPingCeResult",
        "com.ytw.app.ui.activites.sentence_toread.DoSentenceToReadActivity#detalPingCeResultUI",
        "com.ytw.app.ui.activites.sentence_toread.SentenceToReadListActivity#showPingCeResult",
        "com.ytw.app.ui.activites.fun_voice.DoFunVoiceActivity#showPingCeResult",
        "com.ytw.app.ui.activites.listenandspecial.listen.TestSoundActivity#showPingCeResult",
        "com.ytw.app.ui.view.StarBarView#setStarMark"
    )

    /** 分数相关的控件字段名。 */
    private val SCORE_FIELD = Pattern.compile(
        "scroe|score|percent|rating|rate|star|full|total|point",
        Pattern.CASE_INSENSITIVE
    )

    private val uiHooked = Collections.synchronizedSet(HashSet<String>())
    private val uiPending = Collections.synchronizedSet(HashSet<String>())
    private var uiCount = 0
    private var tickCount = 0

    private fun installUi(loader: ClassLoader) {
        for (a in UI_ANCHORS) {
            val i = a.lastIndexOf('#')
            if (i > 0) uiPending.add(a.substring(0, i))
        }
        log("score ui: install " + UI_ANCHORS.size + " 个锚点（读单词/朗读界面层），待加载 " + uiPending.size + " 个类")
        uiExactNow(loader)
        try {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.postDelayed(object : Runnable {
                override fun run() {
                    val left = uiExactNow(loader)
                    tickCount++
                    if (left > 0 && (tickCount <= 15 || tickCount % 30 == 0))
                        log("score ui tick: 已挂 " + (UI_ANCHORS.size - left) + "/" + UI_ANCHORS.size + "，待加载类 " + uiPending.size)
                    if (left > 0) h.postDelayed(this, 2000)
                }
            }, 1500)
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score ui tick failed: " + t)
        }
    }

    /** 立刻补挂已加载的界面层锚点，返回仍未挂上的锚点数。 */
    private fun uiExactNow(loader: ClassLoader): Int {
        var left = 0
        for (a in UI_ANCHORS) {
            val i = a.lastIndexOf('#')
            if (i < 0) continue
            val cn = a.substring(0, i)
            val mn = a.substring(i + 1)
            if (uiHooked.contains(a)) continue
            val c = try {
                XposedHelpers.findClass(cn, loader)
            } catch (t: Throwable) {
                null
            }
            if (c == null) {
                left++
                continue
            }
            uiPending.remove(cn)
            var got = false
            val isStar = cn == STAR
            for (m in c.declaredMethods) {
                if (m.name != mn || m.isSynthetic) continue
                try {
                    hookMethodSafe(c, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled) return
                            val changed = if (isStar) starFull(p) else boostArgs(p)
                            if (changed) {
                                uiCount++
                                if (uiCount <= 30 || uiCount % 50 == 0) log("score ui hit: " + c.name + "#" + m.name + " (第 " + uiCount + " 次)")
                            }
                        }

                        override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled || isStar) return
                            forceUi(p.thisObject)
                        }
                    })
                    got = true
                    log("score ui: hook " + cn + "#" + mn + "(" + m.parameterTypes.size + " 参数)")
                } catch (t: Throwable) {
                    XposedBridge.log(TAG + "score ui hook " + cn + "#" + mn + ": " + t)
                }
            }
            if (got) {
                uiHooked.add(a)
            } else {
                log("score ui: 类已加载但没匹配到 " + cn + "#" + mn)
                left++
            }
        }
        return left
    }

    /** 入口参数里的 JSON/JSON 串先抬满分。 */
    private fun boostArgs(p: XC_MethodHook.MethodHookParam): Boolean {
        val args = p.args ?: return false
        var changed = false
        for (k in args.indices) {
            val o = args[k] ?: continue
            val b = boostObj(o)
            if (b !== o) {
                args[k] = b
                changed = true
            }
        }
        return changed
    }

    /** 星级条：把要画的星数直接拉到满星。 */
    private fun starFull(p: XC_MethodHook.MethodHookParam): Boolean {
        val args = p.args ?: return false
        val full = starCountOf(p.thisObject)
        if (full <= 0f) return false
        var changed = false
        for (k in args.indices) {
            when (val a = args[k]) {
                is java.lang.Float -> if (a.toFloat() != full) {
                    args[k] = java.lang.Float.valueOf(full)
                    changed = true
                }
                is java.lang.Double -> if (a.toDouble() != full.toDouble()) {
                    args[k] = java.lang.Double.valueOf(full.toDouble())
                    changed = true
                }
                else -> {}
            }
        }
        return changed
    }

    /** 取星级条的满星数（字段 starCount / 方法 starCount()，取不到就按 5 星）。 */
    private fun starCountOf(o: Any?): Float {
        if (o == null) return 0f
        try {
            val m = o.javaClass.getMethod("starCount")
            val v = m.invoke(o)
            if (v is Int && v > 0) return v.toFloat()
        } catch (t: Throwable) {
        }
        var c: Class<*>? = o.javaClass
        var d = 0
        while (c != null && d < 4) {
            for (f in c.declaredFields) {
                if (f.name != "starCount") continue
                try {
                    f.isAccessible = true
                    val v = f.get(o)
                    if (v is Int && v > 0) return v.toFloat()
                    if (v is Float && v > 0f) return v
                } catch (t: Throwable) {
                }
            }
            c = c.superclass
            d++
        }
        return 5f
    }

    /** 把界面上分数文本与星级直接拉满。 */
    private fun forceUi(o: Any?) {
        if (o == null) return
        try {
            var c: Class<*>? = o.javaClass
            var d = 0
            while (c != null && d < 4) {
                for (f in c.declaredFields) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    val isStar = f.type.name == STAR
                    if (!isStar && !SCORE_FIELD.matcher(f.name).find()) continue
                    try {
                        f.isAccessible = true
                        val v = f.get(o) ?: continue
                        if (isStar) {
                            setStarFull(v)
                        } else if (v is android.widget.TextView) {
                            val t = v.text?.toString() ?: ""
                            if (t != "100") {
                                v.text = "100"
                                uiCount++
                                if (uiCount <= 30 || uiCount % 50 == 0) log("score ui text: " + c.name + "." + f.name + " " + one(t, 40) + " -> 100")
                            }
                        }
                    } catch (t: Throwable) {
                    }
                }
                c = c.superclass
                d++
            }
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score ui force failed: " + t)
        }
    }

    private fun setStarFull(v: Any) {
        try {
            val n = starCountOf(v)
            val m = v.javaClass.getMethod("setStarMark", java.lang.Float.TYPE, java.lang.Float.TYPE)
            m.invoke(v, n, n)
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score ui star failed: " + t)
        }
    }

    private fun tryHookClass(c: Class<*>, methodName: String) {
        for (m in c.declaredMethods) {
            if (m.name != methodName) continue
            try {
                val rt = m.returnType
                hookMethodSafe(c, m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                        if (!enabled) return
                        when (rt) {
                            java.lang.String::class.java -> {
                                val old = try { p.result as? String } catch (t: Throwable) { null }
                                p.result = "100"
                                if (count++ <= 30) log("score ret[fixed] " + c.name + "#" + methodName + " " + old + " -> 100")
                            }
                            java.lang.Double.TYPE -> p.result = 100.0
                            java.lang.Float.TYPE -> p.result = 100f
                            java.lang.Integer.TYPE -> p.result = 100
                            java.lang.Long.TYPE -> p.result = 100L
                            else -> p.result = "100"
                        }
                    }
                })
                log("score: hook " + c.name + "#" + methodName + " -> 满分")
            } catch (t: Throwable) {
                XposedBridge.log(TAG + "score getter hook " + c.name + "#" + methodName + ": " + t)
            }
        }
    }

    // ---------------------------------------------------------------- D. 提交请求 / 云端评测层

    /** 每个「读」场景构造提交报文的入口（返回 JSONObject）。 */
    private val REQ_ANCHORS = arrayOf(
        "com.ytw.app.ui.childfragment.word.ReadWordFragment#RequestJsonObject",
        "com.ytw.app.ui.childfragment.read_text.ReadTextFragment#RequestJsonObject",
        "com.ytw.app.ui.childfragment.sound_mark.DoSoundMarkFragment#RequestJsonObject",
        "com.ytw.app.ui.childfragment.sound_mark.SoundMarkOneFragment#RequestJsonObject",
        "com.ytw.app.ui.childfragment.listen.QuestionInfoFragment#RequestJsonObject",
        "com.ytw.app.ui.activites.sentence_toread.DoSentenceToReadActivity#RequestJsonObject",
        "com.ytw.app.ui.activites.sentence_toread.SentenceToReadListActivity#RequestJsonObject",
        "com.ytw.app.ui.activites.fun_voice.DoFunVoiceActivity#RequestJsonObject",
        "com.ytw.app.ui.activites.listenandspecial.listen.TestSoundActivity#RequestJsonObject"
    )

    /** 提交动作（按方法名挂，签名不限）：拦参数里的分数串与列表 Bean。 */
    private val SUBMIT_ANCHORS = arrayOf(
        "com.ytw.app.util.SubmitUtils#submitWordRead",
        "com.ytw.app.util.SubmitUtils#submit",
        "com.ytw.app.ui.childfragment.word.ReadWordFragment#submit",
        "com.ytw.app.ui.childfragment.read_text.ReadTextFragment#submit",
        "com.ytw.app.ui.childfragment.sound_mark.DoSoundMarkFragment#submit",
        "com.ytw.app.ui.activites.sentence_toread.DoSentenceToReadActivity#submit",
        "com.ytw.app.ui.activites.fun_voice.DoFunVoiceActivity#submit"
    )

    /** 云端评测（ssound，wss://47.103.175.115）：结果 JSON 与最终分数的产地。 */
    private val PING_ANCHORS = arrayOf(
        "com.ytw.app.audio.PingCeUtils#onPingCeResult",
        "com.ytw.app.audio.PingCeUtils#score",
        "com.ytw.app.audio.PingCeUtils#scoreRank"
    )

    /** 纯数字/小数字符串（提交参数里的分数可能以字符串形式传）。 */
    private val SCORE_STR = Pattern.compile("\\d{1,3}(\\.\\d+)?")

    private val submitPending = Collections.synchronizedSet(HashSet<String>())

    private fun installSubmit(loader: ClassLoader) {
        for (a in REQ_ANCHORS) hookReq(a, loader)
        for (a in SUBMIT_ANCHORS) hookSubmit(a, loader)
        for (a in PING_ANCHORS) hookPing(a, loader)
        log("score submit layer: 请求层 " + REQ_ANCHORS.size + " / 提交层 " + SUBMIT_ANCHORS.size + " / 云端评测 " + PING_ANCHORS.size + " 个锚点；待加载类 " + submitPending.size)
    }

    private fun retrySubmit(loader: ClassLoader) {
        for (a in REQ_ANCHORS) if (submitPending.contains(a.substring(0, a.lastIndexOf('#')))) hookReq(a, loader)
        for (a in SUBMIT_ANCHORS) if (submitPending.contains(a.substring(0, a.lastIndexOf('#')))) hookSubmit(a, loader)
        for (a in PING_ANCHORS) if (submitPending.contains(a.substring(0, a.lastIndexOf('#')))) hookPing(a, loader)
    }

    private fun hookReq(target: String, loader: ClassLoader) {
        val i = target.lastIndexOf('#')
        if (i < 0) return
        val cn = target.substring(0, i)
        val mn = target.substring(i + 1)
        try {
            val c = XposedHelpers.findClass(cn, loader)
            var got = false
            for (m in c.declaredMethods) {
                if (m.name != mn || m.isSynthetic) continue
                got = true
                try {
                    hookMethodSafe(c, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled) return
                            boostArgs(p)
                        }

                        override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled) return
                            val r = p.result ?: return
                            if (boostJson(r, 0)) {
                                count++
                                if (count <= 40 || count % 50 == 0) log("score req[fixed] " + cn + "#" + mn + " -> " + one(r.toString(), 500))
                            }
                        }
                    })
                    log("score req: hook " + cn + "#" + mn)
                } catch (t: Throwable) {
                    log("score req hook FAIL " + cn + "#" + mn + ": " + t)
                }
            }
            if (got) submitPending.remove(cn) else submitPending.add(cn)
        } catch (t: Throwable) {
            submitPending.add(cn)
            log("score req: " + cn + " 还没加载（等补挂）")
        }
    }

    private fun hookSubmit(target: String, loader: ClassLoader) {
        val i = target.lastIndexOf('#')
        if (i < 0) return
        val cn = target.substring(0, i)
        val mn = target.substring(i + 1)
        try {
            val c = XposedHelpers.findClass(cn, loader)
            var got = false
            for (m in c.declaredMethods) {
                if (m.name != mn || m.isSynthetic) continue
                got = true
                try {
                    hookMethodSafe(c, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled) return
                            if (boostSubmitArgs(p)) {
                                count++
                                if (count <= 40 || count % 50 == 0) log("score submit[fixed] " + cn + "#" + mn + " -> 100")
                            }
                        }
                    })
                    log("score submit: hook " + cn + "#" + mn + "(" + m.parameterTypes.size + " 参数)")
                } catch (t: Throwable) {
                    log("score submit hook FAIL " + cn + "#" + mn + ": " + t)
                }
            }
            if (got) submitPending.remove(cn) else submitPending.add(cn)
        } catch (t: Throwable) {
            submitPending.add(cn)
            log("score submit: " + cn + " 还没加载（等补挂）")
        }
    }

    private fun hookPing(target: String, loader: ClassLoader) {
        val i = target.lastIndexOf('#')
        if (i < 0) return
        val cn = target.substring(0, i)
        val mn = target.substring(i + 1)
        try {
            val c = XposedHelpers.findClass(cn, loader)
            var got = false
            for (m in c.declaredMethods) {
                if (m.name != mn || m.isSynthetic) continue
                got = true
                val rt = m.returnType
                try {
                    hookMethodSafe(c, m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled) return
                            boostArgs(p)
                        }

                        override fun afterHookedMethod(p: XC_MethodHook.MethodHookParam) {
                            if (!enabled) return
                            val r = p.result
                            if (r is java.lang.Float) {
                                if (r.toFloat() < 100f) {
                                    p.result = java.lang.Float.valueOf(100f)
                                    count++
                                    if (count <= 40) log("score ping[fixed] " + cn + "#" + mn + " " + r + " -> 100")
                                }
                            } else if (r is java.lang.Double) {
                                if (r.toDouble() < 100.0) {
                                    p.result = java.lang.Double.valueOf(100.0)
                                    count++
                                    if (count <= 40) log("score ping[fixed] " + cn + "#" + mn + " " + r + " -> 100")
                                }
                            } else if (r != null) {
                                boostJson(r, 0)
                            }
                        }
                    })
                    log("score ping: hook " + cn + "#" + mn + " -> " + rt.name)
                } catch (t: Throwable) {
                    log("score ping hook FAIL " + cn + "#" + mn + ": " + t)
                }
            }
            if (got) submitPending.remove(cn) else submitPending.add(cn)
        } catch (t: Throwable) {
            submitPending.add(cn)
            log("score ping: " + cn + " 还没加载（等补挂）")
        }
    }

    /** 提交动作参数：数字串 / JSON / List / Bean 里的分数抬到满分。 */
    private fun boostSubmitArgs(p: XC_MethodHook.MethodHookParam): Boolean {
        val args = p.args ?: return false
        var changed = false
        for (k in args.indices) {
            when (val o = args[k]) {
                null -> {}
                is String -> {
                    val s = o.trim()
                    if (SCORE_STR.matcher(s).matches()) {
                        val d = s.toDoubleOrNull()
                        if (d != null && d > 1.5 && d < 100.0) {
                            args[k] = "100"
                            changed = true
                        }
                    }
                }
                is JSONObject, is JSONArray -> if (boostJson(o, 0)) changed = true
                is Collection<*> -> for (e in o) if (boostBeanFields(e, 0)) changed = true
                is Map<*, *> -> for (v in o.values) if (boostBeanFields(v, 0)) changed = true
                else -> if (o.javaClass.name.startsWith("com.ytw.app.") && boostBeanFields(o, 0)) changed = true
            }
        }
        return changed
    }

    /** JSONObject / JSONArray 里名字像分数的数值抬到满分（结构与其它字段不动）。 */
    private fun boostJson(v: Any?, depth: Int): Boolean {
        if (!enabled || v == null || depth > 6) return false
        var changed = false
        try {
            if (v is JSONObject) {
                val keys = ArrayList<String>()
                val it = v.keys()
                while (it.hasNext()) keys.add(it.next())
                for (k in keys) {
                    val child = v.opt(k)
                    if (child is JSONObject || child is JSONArray) {
                        if (boostJson(child, depth + 1)) changed = true
                        continue
                    }
                    if (!hit(k)) continue
                    val d = when (child) {
                        is Number -> child.toDouble()
                        is String -> child.toDoubleOrNull()
                        else -> null
                    } ?: continue
                    if (d >= 100.0) continue
                    val full = if (d <= 1.5) 1 else 100
                    v.put(k, full)
                    changed = true
                    if (count <= 40 || count % 50 == 0) log("score json[fixed] " + k + " " + d + " -> " + full)
                }
            } else if (v is JSONArray) {
                for (i in 0 until v.length()) {
                    if (boostJson(v.opt(i), depth + 1)) changed = true
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score boostJson: " + t)
        }
        return changed
    }

    /** Bean / List / Map 里名字像分数的数值字段抬到满分。 */
    private fun boostBeanFields(o: Any?, depth: Int): Boolean {
        if (!enabled || o == null || depth > 4) return false
        when (o) {
            is JSONObject, is JSONArray -> return boostJson(o, depth)
            is Collection<*> -> {
                var ch = false
                for (e in o) if (boostBeanFields(e, depth + 1)) ch = true
                return ch
            }
            is Map<*, *> -> {
                var ch = false
                for (v in o.values) if (boostBeanFields(v, depth + 1)) ch = true
                return ch
            }
        }
        var changed = false
        var c: Class<*>? = o.javaClass
        var d = 0
        while (c != null && d < 4 && c.name.startsWith("com.ytw.app.")) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                try {
                    f.isAccessible = true
                    val v = f.get(o)
                    if (v is Number || v is String) {
                        if (!hit(f.name)) continue
                        val dv = if (v is Number) v.toDouble() else v.toString().toDoubleOrNull() ?: continue
                        if (dv >= 100.0) continue
                        val full = if (dv <= 1.5) 1 else 100
                        setField(f, o, full)
                        changed = true
                    } else if (v != null) {
                        if (boostBeanFields(v, depth + 1)) changed = true
                    }
                } catch (t: Throwable) {
                }
            }
            c = c.superclass
            d++
        }
        return changed
    }

    private fun setField(f: java.lang.reflect.Field, o: Any, full: Int) {
        try {
            when (f.type) {
                java.lang.Integer.TYPE, java.lang.Integer::class.java -> f.setInt(o, full)
                java.lang.Long.TYPE, java.lang.Long::class.java -> f.setLong(o, full.toLong())
                java.lang.Float.TYPE, java.lang.Float::class.java -> f.setFloat(o, full.toFloat())
                java.lang.Double.TYPE, java.lang.Double::class.java -> f.setDouble(o, full.toDouble())
                java.lang.String::class.java -> f.set(o, full.toString())
                else -> {}
            }
        } catch (t: Throwable) {
            XposedBridge.log(TAG + "score setField " + f.name + ": " + t)
        }
    }

    // ---------------------------------------------------------------- 日志

    private fun log(line: String) {
        // 日志文件生成已按用户要求移除
    }

    private fun one(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n) + "..."
}
