package io.github.jm350234shenzuo.ytw.a11y

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import de.robv.android.xposed.XposedBridge

object A11y {

    const val TAG = "ytw-a11y-tag"

    /** Enlarges every small clickable view to at least 48dp without touching the layout. */
    fun scheduleTouchFix(act: Activity) {
        val decor = act.window?.decorView ?: return
        decor.postDelayed({
            try {
                walk(decor, act.resources.displayMetrics.density)
            } catch (t: Throwable) {
                XposedBridge.log("[YTW-A11Y] touch fix: " + t)
            }
        }, 600L)
    }

    private fun walk(parent: View, density: Float) {
        if (parent.visibility != View.VISIBLE) return
        if (parent !is ViewGroup) return
        if (parent.tag == TAG) return
        val minPx = (48f * density).toInt()
        val existing = parent.touchDelegate
        if (existing == null || existing is MultiTouchDelegate) {
            var composite = existing as? MultiTouchDelegate
            for (i in 0 until parent.childCount) {
                val c = parent.getChildAt(i) ?: continue
                if (!c.isClickable || c.visibility != View.VISIBLE) continue
                if (c.width <= 0 || c.height <= 0) continue
                val dw = (minPx - c.width).coerceAtLeast(0) / 2
                val dh = (minPx - c.height).coerceAtLeast(0) / 2
                if (dw <= 0 && dh <= 0) continue
                val r = Rect()
                c.getHitRect(r)
                r.left -= dw
                r.right += dw
                r.top -= dh
                r.bottom += dh
                val comp = composite
                if (comp == null) {
                    val made = MultiTouchDelegate(r, c)
                    parent.touchDelegate = made
                    composite = made
                } else {
                    comp.add(r, c)
                }
            }
        }
        for (i in 0 until parent.childCount) {
            parent.getChildAt(i)?.let { walk(it, density) }
        }
    }

    // ------------------------------------------------------------ text reading

    fun collectText(v: View, sb: StringBuilder) {
        if (v.visibility != View.VISIBLE) return
        if (v.tag == TAG) return
        if (!v.isShown) return
        if (v is TextView) {
            val t = v.text?.toString()
            if (!t.isNullOrBlank()) sb.append(t.trim()).append('，')
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { collectText(it, sb) }
            }
        }
    }

    fun collectWebViews(v: View, out: MutableList<WebView>) {
        if (v.visibility != View.VISIBLE) return
        if (v is WebView) {
            out.add(v)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { collectWebViews(it, out) }
            }
        }
    }
}

/**
 * A TouchDelegate that fans a parent's touches out to several enlarged children.
 * View.setTouchDelegate only accepts one delegate, so compound layouts need this.
 */
class MultiTouchDelegate(bounds: Rect, view: View) : TouchDelegate(bounds, view) {

    private class Entry(val bounds: Rect, val view: View)

    private val entries = ArrayList<Entry>()
    private var targeted: Entry? = null

    init {
        entries.add(Entry(bounds, view))
    }

    fun add(b: Rect, v: View) {
        entries.add(Entry(b, v))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val x = event.x.toInt()
            val y = event.y.toInt()
            targeted = entries.firstOrNull { it.bounds.contains(x, y) }
        }
        val t = targeted ?: return false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            targeted = null
        }
        val ox = (t.bounds.left - t.view.left).toFloat()
        val oy = (t.bounds.top - t.view.top).toFloat()
        event.setLocation(event.x - ox, event.y - oy)
        return try {
            t.view.dispatchTouchEvent(event)
        } finally {
            event.setLocation(event.x + ox, event.y + oy)
        }
    }
}
