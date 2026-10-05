package io.github.jm350234shenzuo.bcz.a11y

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 设置页外观：随系统深浅色自动切换，内容按「卡片」分组。
 * 颜色全部在代码里算，避免旧机型上主题不生效导致白底白字。
 */
object Ui {

    const val HEAD = "ts_head"

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun night(c: Context): Boolean =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun pick(c: Context, light: String, dark: String): Int =
        Color.parseColor(if (night(c)) dark else light)

    fun bg(c: Context): Int = pick(c, "#FFF1F3F8", "#FF0E1116")
    fun surface(c: Context): Int = pick(c, "#FFFFFFFF", "#FF181C22")
    fun stroke(c: Context): Int = pick(c, "#FFE2E6EE", "#FF2A3138")
    fun accent(c: Context): Int = pick(c, "#FF1B5FD9", "#FF74ABFF")
    fun textMain(c: Context): Int = pick(c, "#FF191C20", "#FFE7EAEE")
    fun textSub(c: Context): Int = pick(c, "#FF5E6672", "#FFA6AEB9")

    fun round(c: Context, fill: Int, radius: Float, line: Int? = null, width: Float = 1f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(c, radius).toFloat()
        d.setColor(fill)
        if (line != null) d.setStroke(dp(c, width).coerceAtLeast(1), line)
        return d
    }

    fun applyWindow(act: Activity) {
        act.window?.setBackgroundDrawable(ColorDrawable(bg(act)))
    }

    fun card(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.background = round(c, surface(c), 16f, stroke(c))
        l.setPadding(dp(c, 14f), dp(c, 12f), dp(c, 14f), dp(c, 14f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(c, 12f)
        l.layoutParams = lp
        return l
    }

    /** 把平铺的一堆控件按「小节标题」收进卡片，设置页立刻从清单变成卡片式布局。 */
    fun cardify(act: Activity, box: LinearLayout) {
        val kids = ArrayList<View>()
        for (i in 0 until box.childCount) kids.add(box.getChildAt(i))
        box.removeAllViews()
        var card: LinearLayout? = null
        for (v in kids) {
            val head = v is TextView && v.tag == HEAD
            if (head || card == null) {
                card = card(act)
                box.addView(card)
            }
            if (head) {
                (v as TextView).setPadding(0, dp(act, 2f), 0, dp(act, 8f))
            }
            card!!.addView(v)
        }
    }

    fun title(c: Context, s: String): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(16f)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setTextColor(accent(c))
        t.setPadding(0, dp(c, 14f), 0, dp(c, 8f))
        t.tag = HEAD
        return t
    }

    fun body(c: Context, s: String): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(14f)
        t.setTextColor(textSub(c))
        t.setLineSpacing(0f, 1.15f)
        t.setPadding(0, dp(c, 2f), 0, dp(c, 10f))
        return t
    }

    fun switchRow(c: Context, s: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val w = Switch(c)
        w.text = s
        w.setTextSize(16f)
        w.setTextColor(textMain(c))
        w.isChecked = checked
        w.setPadding(0, dp(c, 8f), 0, dp(c, 8f))
        w.setOnCheckedChangeListener { _, v -> onChange(v) }
        return w
    }

    fun button(c: Context, s: String, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = s
        b.textSize = 14f
        b.setTextColor(accent(c))
        b.background = round(c, surface(c), 12f, stroke(c))
        b.setPadding(dp(c, 14f), dp(c, 8f), dp(c, 14f), dp(c, 8f))
        b.setOnClickListener { onClick() }
        return b
    }

    fun slider(
        c: Context,
        label: String,
        min: Float,
        max: Float,
        step: Float,
        value: Float,
        fmt: (Float) -> String,
        onChange: (Float) -> Unit
    ): LinearLayout {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(0, dp(c, 6f), 0, dp(c, 4f))
        val tv = TextView(c)
        tv.textSize = 15f
        tv.setTextColor(textMain(c))
        tv.text = label + "：" + fmt(value)
        row.addView(tv)
        val steps = ((max - min) / step).toInt().coerceAtLeast(1)
        val sb = SeekBar(c)
        sb.max = steps
        sb.progress = ((value - min) / step).toInt().coerceIn(0, steps)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                tv.text = label + "：" + fmt(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(seek: SeekBar?) {}

            override fun onStopTrackingTouch(seek: SeekBar?) {}
        })
        row.addView(sb)
        return row
    }
}
