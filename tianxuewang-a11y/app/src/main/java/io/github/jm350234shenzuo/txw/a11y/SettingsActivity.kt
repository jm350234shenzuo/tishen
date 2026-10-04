package io.github.jm350234shenzuo.txw.a11y

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class SettingsActivity : Activity() {

    private lateinit var sp: SharedPreferences
    private lateinit var box: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
        handleSkipAdd(intent)
        val scroll = ScrollView(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 18f), Ui.dp(this, 12f), Ui.dp(this, 18f), Ui.dp(this, 40f))
        scroll.addView(
            box,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        setContentView(scroll)
        rebuild()
    }

    override fun onDestroy() {
        super.onDestroy()
    }


    /** 按钮选择器把选中的文字发过来：写进跳过词表，让用户不用手打。 */
    private fun handleSkipAdd(intent: Intent?) {
        val add = intent?.getStringExtra("skip_add")?.trim().orEmpty()
        if (add.isEmpty()) return
        val raw = sp.getString(SkipKeys.TEXTS, "").orEmpty()
        val src = if (raw.isBlank()) SkipKeys.DEF_TEXTS else raw
        val list = ArrayList<String>()
        for (x in src.split(",")) {
            val t = x.trim()
            if (t.isNotEmpty()) list.add(t)
        }
        val fresh = !list.contains(add)
        if (fresh) list.add(add)
        sp.edit().putString(SkipKeys.TEXTS, list.joinToString(",")).commit()
        putBool(SkipKeys.ENABLED, true)
        Toast.makeText(this, if (fresh) "已记住按钮：" + add else "已在跳过词表里：" + add, Toast.LENGTH_LONG).show()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 1200)
    }

    // ------------------------------------------------------------------ state
    private fun sBool(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)

    private fun sFloat(k: String, d: Float): Float = sp.getFloat(k, d)

    private fun sPut(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).commit()
    }

    private fun putBool(k: String, v: Boolean) {
        sp.edit().putBoolean(k, v).commit()
    }

    private fun putFloat(k: String, v: Float) {
        sp.edit().putFloat(k, v).commit()
    }

    private fun putInt(k: String, v: Int) {
        sp.edit().putInt(k, v).commit()
    }

    private fun targets(): Set<String> =
        Prefs.splitTargets(sp.getString(Keys.TARGETS, Keys.DEFAULT_TARGETS))

    private fun putTargets(t: Set<String>) {
        sp.edit().putString(Keys.TARGETS, t.joinToString(",")).commit()
    }

    private fun rebuild() {
        box.removeAllViews()
        val cur = Prefs.fromPrefs(sp)

        box.addView(Ui.title(this, "题神·天学网"))
        box.addView(
            Ui.body(
                this,
                "使用步骤：\n" +
                    "1. 在 LSPosed 里启用本模块，作用域勾选「天学网」。\n" +
                    "2. 先在本页设置好，再强行停止天学网并重新打开。\n" +
                    "3. 以后每次改设置，都要重启一次天学网才生效。\n\n" +
                    "本模块只做可访问性增强：放大文字、自动听音、加大点击区域、降低动画、放宽答题计时。" +
                    "开启「判我对 / 评分满分 / 回包改写」后，会改写本机提交的答题结果；这些开关都在本页，可单独关闭。"
            )
        )

        box.addView(Ui.switchRow(this, "启用模块", cur.enabled) {
            putBool(Keys.ENABLED, it)
            box.post { rebuild() }
        })

        // -------------------------------------------------------- target apps
        box.addView(Ui.title(this, "目标应用"))
        box.addView(Ui.body(this, "当前：" + cur.targets.joinToString("、")))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(Ui.button(this, "自动检测天学网") { pickTargets(true) })
        row.addView(Ui.button(this, "从全部应用中选择") { pickTargets(false) })
        box.addView(row)
        val et = EditText(this)
        et.hint = "手动输入包名，例如 com.up366.mobile"
        et.setTextSize(15f)
        box.addView(et)
        box.addView(Ui.button(this, "添加这个包名") {
            val v = et.text.toString().trim()
            if (v.isEmpty()) {
                toast("请先输入包名")
            } else {
                putTargets(targets() + v)
                rebuild()
            }
        })

        // ------------------------------------------------------------- display
        box.addView(Ui.title(this, "看得清"))
        box.addView(
            Ui.slider(
                this, "文字放大倍数", 1.0f, 2.0f, 0.1f, cur.fontScale,
                fmt = { x -> String.format(Locale.US, "%.1f 倍", x) },
                onChange = { putFloat(Keys.FONT_SCALE, it) }
            )
        )
        val rg = RadioGroup(this)
        val opts = listOf("跟随系统" to 0, "强制浅色" to 1, "强制深色" to 2)
        for ((i, p) in opts.withIndex()) {
            val rb = RadioButton(this)
            rb.text = p.first
            rb.id = 2000 + i
            rb.isChecked = cur.nightMode == p.second
            rb.textSize = 16f
            rg.addView(rb)
        }
        rg.setOnCheckedChangeListener { _, id ->
            val i = id - 2000
            if (i in opts.indices) putInt(Keys.NIGHT_MODE, opts[i].second)
        }
        box.addView(rg)
        box.addView(Ui.switchRow(this, "降低动画（减少闪烁和位移）", cur.reduceMotion) {
            putBool(Keys.REDUCE_MOTION, it)
        })

        // ------------------------------------------------------------ motor
        box.addView(Ui.title(this, "点得准"))
        box.addView(Ui.switchRow(this, "加大点击区域（小按钮扩到 48dp）", cur.bigTouch) {
            putBool(Keys.BIG_TOUCH, it)
        })
        // ----------------------------------------------------------- force
        box.addView(Ui.title(this, "判我对（跳过但记为正确）"))
        box.addView(
            Ui.body(
                this,
                "答错也当成答对：模块给「答题判定」方法挂 hook 强制返回 true，并把服务器回包里的对错字段改成正确，" +
                    "App 因此直接进入下一题（等于跳过，但这一题记为正确）。提交给服务器的分数由上面的「答题结果改写」负责。" +
                    "学习页在前台时会放开扫描范围，连类名被混淆的判定类一起扫。"
            )
        )
        box.addView(Ui.switchRow(this, "启用「判我对」", sp.getBoolean(ForceKeys.ENABLED, true)) {
            putBool(ForceKeys.ENABLED, it)
            toast(if (it) "已开启：答错也判对" else "已关闭判我对")
        })

        // ---------------------------------------------------------- score
        box.addView(Ui.title(this, "朗读/读单词评分（改判满分）"))
        box.addView(
            Ui.body(
                this,
                "天学网的口语评分是**本机离线引擎**（libases_engine.so + liblsx-ases-export-4.6.1.so），" +
                    "分数由 com.up366.asecengine.jni.AsesJni 回给 App。打开后模块把评测结果里的分数抬到满分：" +
                    "JSON 里名字含 score/total/pron/fluency/accuracy/integrity 等字段改满分，" +
                    "评分类里名字像分数的 getter 也强制返回满分。"
            )
        )
        box.addView(Ui.switchRow(this, "启用「朗读评分改判满分」", sp.getBoolean(ScoreKeys.ENABLED, true)) {
            putBool(ScoreKeys.ENABLED, it)
            toast(if (it) "已开启：评分抬到满分" else "已关闭评分改写")
        })
        // ------------------------------------------------------------- listen
        box.addView(Ui.title(this, "自动听音"))
        box.addView(
            Ui.body(
                this,
                "拼写、答题时用 Xposed hook 直接调用天学网自己的「听音」按钮处理器来播放题目发音，" +
                    "省去手动按一次听音键。不是屏幕朗读，也不朗读题干文字。"
            )
        )
        box.addView(Ui.body(this, "默认听音词：" + ListenKeys.DEF_TEXTS))
        box.addView(Ui.switchRow(this, "启用自动听音", sBool(ListenKeys.ENABLED, true)) {
            sPut(ListenKeys.ENABLED, it)
        })
        box.addView(Ui.switchRow(this, "进入页面自动播放发音", sBool(ListenKeys.AUTO, true)) {
            sPut(ListenKeys.AUTO, it)
        })
        val etListen = EditText(this)
        etListen.setText(sp.getString(ListenKeys.TEXTS, ""))
        etListen.hint = "留空 = 使用默认词；多个词用逗号分隔"
        etListen.setSingleLine(false)
        etListen.minLines = 2
        box.addView(etListen)
        box.addView(Ui.button(this, "保存听音词") {
            sp.edit().putString(ListenKeys.TEXTS, etListen.text.toString().trim()).commit()
            toast("已保存听音词")
        })
        box.addView(
            Ui.slider(
                this, "识别延迟", ListenKeys.MIN_DELAY.toFloat(), ListenKeys.MAX_DELAY.toFloat(), 100f,
                sp.getInt(ListenKeys.DELAY, ListenKeys.DEF_DELAY).toFloat(),
                fmt = { x -> String.format(Locale.US, "%.1f 秒", x / 1000f) },
                onChange = { putInt(ListenKeys.DELAY, it.toInt()) }
            )
        )
        box.addView(
            Ui.slider(
                this, "每屏最多播一次的间隔", 0f, 6000f, 100f,
                sp.getInt(ListenKeys.GAP, ListenKeys.DEF_GAP).toFloat(),
                fmt = { x -> String.format(Locale.US, "%.1f 秒", x / 1000f) },
                onChange = { putInt(ListenKeys.GAP, it.toInt()) }
            )
        )

        // ------------------------------------------------------------- timer
        box.addView(Ui.title(this, "想得久一点"))
        box.addView(
            Ui.body(
                this,
                "把答题倒计时按倍数放宽。这只影响界面上的计时器，" +
                    "不会修改你的学习记录，也不会让结果变好看；" +
                    "对行动或认知不便的用户属于常见的「延长作答时间」调整。"
            )
        )
        box.addView(
            Ui.slider(
                this, "计时放宽倍数", 1.0f, 5.0f, 0.5f, cur.timerMul,
                fmt = { x -> if (x <= 1.0f) "不调整" else String.format(Locale.US, "%.1f 倍", x) },
                onChange = { putFloat(Keys.TIMER_MUL, it) }
            )
        )
        box.addView(Ui.switchRow(this, "答题不限时", cur.timerUnlimited) {
            putBool(Keys.TIMER_UNLIMITED, it)
        })

        box.addView(Ui.title(this, "说明"))
        box.addView(
            Ui.body(
                this,
                "生效范围仅限你勾选的目标应用进程，所有改动都在应用内存里完成，" +
                    "不写回天学网的任何数据。\n" +
                    "如果某项没生效，多半是该页面用了网页（H5）实现，" +
                    "自动听音会用 hook 触发页面自身的按钮回调。"
            )
        )
    }

    // ------------------------------------------------------------ app picking

    private fun pickTargets(autoOnly: Boolean) {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = try {
            pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            emptyList()
        }
        val map = LinkedHashMap<String, String>()
        for (ri in found) {
            val ai = ri.activityInfo ?: continue
            val pkg = ai.packageName ?: continue
            if (pkg == packageName) continue
            if (map.containsKey(pkg)) continue
            val label = try {
                ri.loadLabel(pm).toString()
            } catch (_: Throwable) {
                pkg
            }
            map[pkg] = label
        }
        var pool = map.entries.map { it.key to it.value }
        if (autoOnly) {
            pool = pool.filter { e ->
                e.second.contains("天学网") || e.first.contains("up366")
            }
            if (pool.isEmpty()) {
                toast("没自动找到天学网，请用「从全部应用中选择」或手动输入包名")
                return
            }
        }
        pool = pool.sortedBy { it.second }
        val labels = pool.map { (it.second + "\n" + it.first) as CharSequence }.toTypedArray()
        val current = targets()
        val checked = BooleanArray(pool.size) { current.contains(pool[it].first) }
        AlertDialog.Builder(this)
            .setTitle(if (autoOnly) "检测到的天学网" else "选择要增强的应用")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (which in checked.indices) checked[which] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val sel = LinkedHashSet<String>()
                for (i in pool.indices) {
                    if (checked[i]) sel.add(pool[i].first)
                }
                if (sel.isEmpty()) sel.addAll(Cfg().targets)
                putTargets(sel)
                rebuild()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // --------------------------------------------------------------- speaking

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
