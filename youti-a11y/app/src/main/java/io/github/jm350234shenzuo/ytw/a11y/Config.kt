package io.github.jm350234shenzuo.ytw.a11y

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject

object Keys {
    const val PKG = "io.github.jm350234shenzuo.ytw.a11y"
    const val PREFS = "config"
    const val AUTHORITY = "io.github.jm350234shenzuo.ytw.a11y.config"

    const val ENABLED = "enabled"
    const val TARGETS = "targets"
    const val FONT_SCALE = "font_scale"
    const val NIGHT_MODE = "night_mode"
    const val REDUCE_MOTION = "reduce_motion"
    const val BIG_TOUCH = "big_touch"
    const val OVERLAY = "overlay"
    const val TTS_RATE = "tts_rate"
    const val SPEAK_ON_OPEN = "speak_on_open"
    const val TIMER_MUL = "timer_mul"
    const val TIMER_UNLIMITED = "timer_unlimited"

    const val DEFAULT_TARGETS = "com.ytw.app"
}

data class Cfg(
    val enabled: Boolean = true,
    val targets: Set<String> = setOf("com.ytw.app"),
    val fontScale: Float = 1.0f,
    val nightMode: Int = 0,
    val reduceMotion: Boolean = false,
    val bigTouch: Boolean = false,
    val overlay: Boolean = true,
    val ttsRate: Float = 0.85f,
    val speakOnOpen: Boolean = false,
    val timerMul: Float = 1.0f,
    val timerUnlimited: Boolean = false
) {
    fun covers(pkg: String?): Boolean =
        pkg != null && (targets.contains("*") || targets.contains(pkg))
}

/** Process-local, non persistent tweaks driven by the in-app floating control. */
object Live {
    @Volatile var fontScale: Float? = null
    @Volatile var timerUnlimited: Boolean? = null
}

object Prefs {

    fun splitTargets(raw: String?): Set<String> =
        (raw ?: Keys.DEFAULT_TARGETS)
            .split(',', ';', ' ', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun dump(sp: SharedPreferences): String {
        val d = Cfg()
        val o = JSONObject()
        o.put(Keys.ENABLED, sp.getBoolean(Keys.ENABLED, d.enabled))
        o.put(Keys.TARGETS, sp.getString(Keys.TARGETS, Keys.DEFAULT_TARGETS))
        o.put(Keys.FONT_SCALE, sp.getFloat(Keys.FONT_SCALE, d.fontScale).toDouble())
        o.put(Keys.NIGHT_MODE, sp.getInt(Keys.NIGHT_MODE, d.nightMode))
        o.put(Keys.REDUCE_MOTION, sp.getBoolean(Keys.REDUCE_MOTION, d.reduceMotion))
        o.put(Keys.BIG_TOUCH, sp.getBoolean(Keys.BIG_TOUCH, d.bigTouch))
        o.put(Keys.OVERLAY, sp.getBoolean(Keys.OVERLAY, d.overlay))
        o.put(Keys.TTS_RATE, sp.getFloat(Keys.TTS_RATE, d.ttsRate).toDouble())
        o.put(Keys.SPEAK_ON_OPEN, sp.getBoolean(Keys.SPEAK_ON_OPEN, d.speakOnOpen))
        o.put(Keys.TIMER_MUL, sp.getFloat(Keys.TIMER_MUL, d.timerMul).toDouble())
        o.put(Keys.TIMER_UNLIMITED, sp.getBoolean(Keys.TIMER_UNLIMITED, d.timerUnlimited))
        return o.toString()
    }

    fun parse(json: String): Cfg {
        val d = Cfg()
        val o = JSONObject(json)
        return Cfg(
            enabled = o.optBoolean(Keys.ENABLED, d.enabled),
            targets = splitTargets(o.optString(Keys.TARGETS, Keys.DEFAULT_TARGETS)),
            fontScale = o.optDouble(Keys.FONT_SCALE, d.fontScale.toDouble()).toFloat(),
            nightMode = o.optInt(Keys.NIGHT_MODE, d.nightMode),
            reduceMotion = o.optBoolean(Keys.REDUCE_MOTION, d.reduceMotion),
            bigTouch = o.optBoolean(Keys.BIG_TOUCH, d.bigTouch),
            overlay = o.optBoolean(Keys.OVERLAY, d.overlay),
            ttsRate = o.optDouble(Keys.TTS_RATE, d.ttsRate.toDouble()).toFloat(),
            speakOnOpen = o.optBoolean(Keys.SPEAK_ON_OPEN, d.speakOnOpen),
            timerMul = o.optDouble(Keys.TIMER_MUL, d.timerMul.toDouble()).toFloat(),
            timerUnlimited = o.optBoolean(Keys.TIMER_UNLIMITED, d.timerUnlimited)
        )
    }

    fun fromPrefs(sp: SharedPreferences): Cfg {
        val d = Cfg()
        return Cfg(
            enabled = sp.getBoolean(Keys.ENABLED, d.enabled),
            targets = splitTargets(sp.getString(Keys.TARGETS, Keys.DEFAULT_TARGETS)),
            fontScale = sp.getFloat(Keys.FONT_SCALE, d.fontScale),
            nightMode = sp.getInt(Keys.NIGHT_MODE, d.nightMode),
            reduceMotion = sp.getBoolean(Keys.REDUCE_MOTION, d.reduceMotion),
            bigTouch = sp.getBoolean(Keys.BIG_TOUCH, d.bigTouch),
            overlay = sp.getBoolean(Keys.OVERLAY, d.overlay),
            ttsRate = sp.getFloat(Keys.TTS_RATE, d.ttsRate),
            speakOnOpen = sp.getBoolean(Keys.SPEAK_ON_OPEN, d.speakOnOpen),
            timerMul = sp.getFloat(Keys.TIMER_MUL, d.timerMul),
            timerUnlimited = sp.getBoolean(Keys.TIMER_UNLIMITED, d.timerUnlimited)
        )
    }
}

object Config {

    private const val TTL_MS = 1500L

    @Volatile private var cached: Cfg? = null
    @Volatile private var stamp = 0L

    /**
     * Reads the configuration from whichever channel is available in this process:
     * 1. the module's exported ContentProvider (works on any Xposed framework),
     * 2. XSharedPreferences (LSPosed, xposedsharedprefs meta-data),
     * 3. the local SharedPreferences file (module's own process only).
     */
    fun get(ctx: Context?, force: Boolean = false): Cfg {
        val now = SystemClock.elapsedRealtime()
        val c = cached
        if (!force && c != null && now - stamp < TTL_MS) return apply(Live, c)
        val loaded = load(ctx)
        cached = loaded
        stamp = now
        return apply(Live, loaded)
    }

    private fun apply(live: Live, base: Cfg): Cfg {
        val f = live.fontScale
        val t = live.timerUnlimited
        if (f == null && t == null) return base
        return base.copy(
            fontScale = f ?: base.fontScale,
            timerUnlimited = t ?: base.timerUnlimited
        )
    }

    private fun load(ctx: Context?): Cfg {
        if (ctx == null) return Cfg()
        try {
            val uri = Uri.parse("content://" + Keys.AUTHORITY + "/all")
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val json = cur.getString(0)
                    if (!json.isNullOrEmpty()) return Prefs.parse(json)
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("[YTW-A11Y] provider read failed: " + t)
        }
        try {
            val p = XSharedPreferences(Keys.PKG, Keys.PREFS)
            p.reload()
            if (p.isReadable()) {
                return Cfg(
                    enabled = p.getBoolean(Keys.ENABLED, true),
                    targets = Prefs.splitTargets(p.getString(Keys.TARGETS, Keys.DEFAULT_TARGETS)),
                    fontScale = p.getFloat(Keys.FONT_SCALE, 1.0f),
                    nightMode = p.getInt(Keys.NIGHT_MODE, 0),
                    reduceMotion = p.getBoolean(Keys.REDUCE_MOTION, false),
                    bigTouch = p.getBoolean(Keys.BIG_TOUCH, false),
                    overlay = p.getBoolean(Keys.OVERLAY, true),
                    ttsRate = p.getFloat(Keys.TTS_RATE, 0.85f),
                    speakOnOpen = p.getBoolean(Keys.SPEAK_ON_OPEN, false),
                    timerMul = p.getFloat(Keys.TIMER_MUL, 1.0f),
                    timerUnlimited = p.getBoolean(Keys.TIMER_UNLIMITED, false)
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("[YTW-A11Y] XSharedPreferences read failed: " + t)
        }
        try {
            val sp = ctx.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
            if (sp.contains(Keys.ENABLED) || sp.contains(Keys.FONT_SCALE)) return Prefs.fromPrefs(sp)
        } catch (t: Throwable) {
            XposedBridge.log("[YTW-A11Y] local prefs read failed: " + t)
        }
        return Cfg()
    }
}
