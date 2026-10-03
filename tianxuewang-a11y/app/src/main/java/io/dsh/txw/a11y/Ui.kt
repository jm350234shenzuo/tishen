package io.dsh.txw.a11y

import android.content.Context
import android.graphics.Color
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

object Ui {

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    fun title(c: Context, s: String): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(20f)
        t.setTextColor(Color.parseColor("#FF0D47A1"))
        t.setPadding(0, dp(c, 16f), 0, dp(c, 6f))
        return t
    }

    fun body(c: Context, s: String): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(15f)
        t.setTextColor(Color.parseColor("#FF444444"))
        t.setPadding(0, dp(c, 4f), 0, dp(c, 10f))
        return t
    }

    fun switchRow(c: Context, s: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val w = Switch(c)
        w.text = s
        w.setTextSize(17f)
        w.isChecked = checked
        w.setPadding(0, dp(c, 8f), 0, dp(c, 8f))
        w.setOnCheckedChangeListener { _, v -> onChange(v) }
        return w
    }

    fun button(c: Context, s: String, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = s
        b.textSize = 15f
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
        val tv = TextView(c)
        tv.textSize = 16f
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
