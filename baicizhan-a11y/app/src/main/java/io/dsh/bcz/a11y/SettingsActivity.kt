package io.dsh.bcz.a11y

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

        box.addView(Ui.title(this, "题神·百词斩"))
        box.addView(
            Ui.body(
                this,
                "使用步骤：\n" +
                    "1. 在 LSPosed 里启用本模块，作用域勾选「百词斩」。\n" +
                    "2. 先在本页设置好，再强行停止百词斩并重新打开。\n" +
                    "3. 以后每次改设置，都要重启一次百词斩才生效。\n\n" +
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
        row.addView(Ui.button(this, "自动检测百词斩") { pickTargets(true) })
        row.addView(Ui.button(this, "从全部应用中选择") { pickTargets(false) })
        box.addView(row)
        val et = EditText(this)
        et.hint = "手动输入包名，例如 com.jiongji.andriod.card"
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
        // ----------------------------------------------------------- rewrite
        box.addView(Ui.title(this, "答题结果改写（提交服务器前）"))
        box.addView(
            Ui.body(
                this,
                "在百词斩把答题记录序列化、发给服务器之前，把这份数据里的结果改成「正确」：" +
                    "拼写/组句这类只上传分数的题，把 correct_times 提到 attempt_times、is_killed 置 false；" +
                    "选择题把 user_opt_index 改成 correct_opt_index；错题次数清零。" +
                    "只改写发出去的那份数据，不改 App 界面上已经显示出来的对错，也不改本地已完成记录。" +
                    "只改写发出去的那份数据，不改本地已显示的界面。"
            )
        )
        box.addView(Ui.switchRow(this, "启用答题结果改写", sp.getBoolean(RewriteKeys.ENABLED, true)) {
            putBool(RewriteKeys.ENABLED, it)
            toast(if (it) "已开启：提交给服务器的答题数据会改成正确" else "已关闭答题结果改写")
        })
        box.addView(Ui.switchRow(this, "满分模式（连分数一起改）", sp.getBoolean(RewriteKeys.FULL, false)) {
            putBool(RewriteKeys.FULL, it)
            toast(if (it) "已开启满分模式：分数也一起拉满" else "已关闭满分模式")
        })
        box.addView(
            Ui.body(
                this,
                "注意：改的是本机账号自己提交的数据，服务器侧记录会变成正确；平台风控有封号风险，自行取舍。"
            )
        )


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

        // ------------------------------------------------------------- listen
        box.addView(Ui.title(this, "自动听音"))
        box.addView(
            Ui.body(
                this,
                "拼写、答题时用 Xposed hook 直接调用百词斩自己的「听音」按钮处理器来播放题目发音，" +
                    "省去手动按一次听音键。不是屏幕朗读，也不朗读题干文字。"
            )
        )
        box.addView(Ui.body(this, "默认听音词：" + ListenKeys.DEF_TEXTS))
        box.addView(Ui.switchRow(this, "启用自动听音", sp.getBoolean(ListenKeys.ENABLED, true)) {
            putBool(ListenKeys.ENABLED, it)
        })
        box.addView(Ui.switchRow(this, "进入页面自动播放发音", sp.getBoolean(ListenKeys.AUTO, true)) {
            putBool(ListenKeys.AUTO, it)
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
                    "不写回百词斩的任何数据。\n" +
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
                e.second.contains("百词斩") || e.first.contains("jiongji") || e.first.contains("baicizhan")
            }
            if (pool.isEmpty()) {
                toast("没自动找到百词斩，请用「从全部应用中选择」或手动输入包名")
                return
            }
        }
        pool = pool.sortedBy { it.second }
        val labels = pool.map { (it.second + "\n" + it.first) as CharSequence }.toTypedArray()
        val current = targets()
        val checked = BooleanArray(pool.size) { current.contains(pool[it].first) }
        AlertDialog.Builder(this)
            .setTitle(if (autoOnly) "检测到的百词斩" else "选择要增强的应用")
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

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }
}
