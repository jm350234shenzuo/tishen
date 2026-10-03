package io.dsh.txw.a11y

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import kotlin.math.abs

/**
 * 悬浮控制球（不含朗读）。轻点展开面板，按住可上下拖动。
 * 面板：跳过本题 / 自动跳过开关 / 听音 / 自动听音开关 / 字体＋－ / 收起。
 */
object Overlay {

    private const val TAG = "[TXW-A11Y]"

    private var host: ViewGroup? = null
    private var owner: java.lang.ref.WeakReference<Activity>? = null
    private var ball: View? = null
    private var panel: View? = null
    private var autoBtn: TextView? = null
    private var listenBtn: TextView? = null

    fun attach(act: Activity, cfg: Cfg) {
        try {
            val decor = act.window?.decorView as? ViewGroup ?: return
            if (owner?.get() === act && ball?.parent != null) return
            detachViews()
            owner = java.lang.ref.WeakReference(act)
            host = decor
            val dm = act.resources.displayMetrics
            val p = buildPanel(act, dm)
            val b = buildBall(act, dm)
            decor.addView(p, panelLp(dm))
            decor.addView(b, ballLp(dm))
            ball = b
            panel = p
            XposedBridge.log(TAG + " overlay attached: " + act.javaClass.name)
            Diag.line("overlay attached: " + act.javaClass.name)
        } catch (t: Throwable) {
            XposedBridge.log(TAG + " overlay attach failed: " + t)
            Diag.line("overlay attach FAILED: " + t)
        }
    }

    fun detach(act: Activity) {
        Skip.onActivityGone(act)
        if (owner?.get() === act) detachViews()
    }

    private fun detachViews() {
        try {
            (ball?.parent as? ViewGroup)?.removeView(ball)
        } catch (t: Throwable) {
        }
        try {
            (panel?.parent as? ViewGroup)?.removeView(panel)
        } catch (t: Throwable) {
        }
        ball = null
        panel = null
        host = null
        owner = null
        autoBtn = null
        listenBtn = null
    }

    // ------------------------------------------------------------ 布局参数

    private fun dp(dm: DisplayMetrics, v: Float): Int = (v * dm.density + 0.5f).toInt()

    private fun ballLp(dm: DisplayMetrics): FrameLayout.LayoutParams {
        val s = dp(dm, 52f)
        val lp = FrameLayout.LayoutParams(s, s, Gravity.TOP or Gravity.END)
        lp.topMargin = (dm.heightPixels * 0.35f).toInt()
        lp.rightMargin = dp(dm, 6f)
        return lp
    }

    private fun panelLp(dm: DisplayMetrics): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        )
        lp.topMargin = (dm.heightPixels * 0.35f).toInt()
        lp.rightMargin = dp(dm, 6f) + dp(dm, 52f) + dp(dm, 8f)
        return lp
    }

    // ---------------------------------------------------------------- 悬浮球

    private fun buildBall(act: Activity, dm: DisplayMetrics): View {
        val tv = TextView(act)
        tv.text = "跳"
        tv.textSize = 20f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.alpha = 0.88f
        tv.tag = Skip.OWN_TAG
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#3F51B5"))
        tv.background = bg
        tv.setOnClickListener { togglePanel() }
        tv.setOnTouchListener(object : View.OnTouchListener {
            private var downY = 0f
            private var startTop = 0
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return false
                        downY = e.rawY
                        startTop = lp.topMargin
                        moved = false
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return false
                        lp.topMargin = (startTop + (e.rawY - downY)).toInt()
                        if (abs(e.rawY - downY) > 12f) moved = true
                        v.layoutParams = lp
                        val pl = panel
                        if (pl != null) {
                            val plp = pl.layoutParams as? FrameLayout.LayoutParams
                            if (plp != null) {
                                plp.topMargin = lp.topMargin
                                pl.layoutParams = plp
                            }
                        }
                        return moved
                    }
                }
                return false
            }
        })
        return tv
    }

    // ---------------------------------------------------------------- 面板

    private fun buildPanel(act: Activity, dm: DisplayMetrics): View {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(dm, 10f), dp(dm, 10f), dp(dm, 10f), dp(dm, 10f))
        box.tag = Skip.OWN_TAG
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(dm, 14f).toFloat()
        bg.setColor(Color.parseColor("#E6202124"))
        box.background = bg
        box.visibility = View.GONE

        val skip = btn(act, dm, "跳过本题")
        skip.setOnClickListener {
            val a = Skip.activity()
            if (a == null) {
                XposedBridge.log(TAG + " manual skip: no activity")
            } else {
                val ok = Skip.now(a, true)
                XposedBridge.log(TAG + " manual skip ok=" + ok + " " + Skip.lastResult)
                if (!ok) android.widget.Toast.makeText(a, Skip.lastResult, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        box.addView(skip)

        val pick = btn(act, dm, "找不到按钮？")
        pick.setOnClickListener {
            val a = Skip.activity()
            if (a != null) Skip.picker(a)
        }
        box.addView(pick)

        val calib = btn(act, dm, "校准点击位置")
        calib.setOnClickListener {
            val a = Skip.activity()
            if (a != null) Skip.startCalibration(a)
        }
        box.addView(calib)

        val tapTest = btn(act, dm, "试按校准位置")
        tapTest.setOnClickListener {
            val a = Skip.activity()
            if (a != null) Skip.tapCalib(a, true)
        }
        tapTest.setOnLongClickListener {
            Skip.clearCalib()
            Skip.say(Skip.activity(), "已清除校准位置")
            true
        }
        box.addView(tapTest)

        val auto = btn(act, dm, autoLabel())
        auto.setOnClickListener {
            Skip.toggleAuto()
            auto.text = autoLabel()
        }
        autoBtn = auto
        box.addView(auto)

        val listen = btn(act, dm, "听音")
        listen.setOnClickListener {
            AutoListen.once()
            XposedBridge.log(TAG + " manual listen: " + AutoListen.lastResult)
        }
        box.addView(listen)

        val autoListen = btn(act, dm, listenLabel())
        autoListen.setOnClickListener {
            AutoListen.toggleAuto()
            autoListen.text = listenLabel()
        }
        listenBtn = autoListen
        box.addView(autoListen)

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.tag = Skip.OWN_TAG
        val bigger = btn(act, dm, "字体＋")
        bigger.setOnClickListener {
            val a = Skip.activity()
            if (a != null) bumpFont(a, 0.15f)
        }
        val smaller = btn(act, dm, "字体－")
        smaller.setOnClickListener {
            val a = Skip.activity()
            if (a != null) bumpFont(a, -0.15f)
        }
        row.addView(bigger)
        row.addView(smaller)
        box.addView(row)

        val hide = btn(act, dm, "收起")
        hide.setOnClickListener { togglePanel() }
        box.addView(hide)
        return box
    }

    private fun btn(ct: Context, dm: DisplayMetrics, label: String): TextView {
        val tv = TextView(ct)
        tv.text = label
        tv.textSize = 15f
        tv.setTextColor(Color.WHITE)
        tv.gravity = Gravity.CENTER
        tv.tag = Skip.OWN_TAG
        tv.setPadding(dp(dm, 14f), dp(dm, 9f), dp(dm, 14f), dp(dm, 9f))
        return tv
    }

    private fun autoLabel(): String = if (Skip.isAuto()) "自动跳过：开" else "自动跳过：关"

    private fun listenLabel(): String = if (AutoListen.isAuto()) "自动听音：开" else "自动听音：关"

    private fun togglePanel() {
        val p = panel ?: return
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        val a = autoBtn
        if (a != null) a.text = autoLabel()
        val l = listenBtn
        if (l != null) l.text = listenLabel()
    }

    // ---------------------------------------------------------------- 字体

    @Suppress("DEPRECATION")
    private fun bumpFont(act: Activity, delta: Float) {
        try {
            val res = act.resources
            val c = Configuration(res.configuration)
            c.fontScale = (c.fontScale + delta).coerceIn(0.7f, 2.5f)
            res.updateConfiguration(c, res.displayMetrics)
            XposedBridge.log(TAG + " fontScale -> " + c.fontScale)
        } catch (t: Throwable) {
            XposedBridge.log(TAG + " font bump failed: " + t)
        }
    }
}
