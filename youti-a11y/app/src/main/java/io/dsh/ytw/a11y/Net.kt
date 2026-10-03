package io.dsh.ytw.a11y

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.IdentityHashMap
import java.util.regex.Pattern

/** 配置键：与设置页「网络回包改写」开关一一对应。 */
object NetKeys {
    const val ENABLED = "nt_enabled"
}

/**
 * 网络层改写与取证。
 *
 * 优题网的选择题在选中一瞬间由本地判定，随后还有一次校验把结果覆盖成错。
 * 本文件分三路下手：
 *   1) 格式化器（真实签名来自真机包 dex：JSONFormatter 的 formatJSON/format 都是 String 进 String 出，
 *      FastjsonFormatter/GsonFormatter 的 format 同形）：报文里 is_right 的 false 或 0 改 true 或 1，
 *      score/total/overall/full/integrity/fluency/pron 抬到 100；
 *   2) 解析器 ResponseParser#onParse：解析出来的 Bean 图里，凡是布尔字段名像对错判定的
 *      （is_right / isRight / right / isCorrect / correct）一律置 true；
 *   3) LogInterceptor#intercept：可用于确认判定来自哪里的响应体观察点。
 * 设置页可单独关闭（键 nt_enabled）。
 */
object Net {
    private const val PARSER = "com.ytw.app.http.ResponseParser"
    private const val INTERCEPTOR = "com.ytw.app.http.LogInterceptor"

    private val FORMATTERS = arrayOf<Array<String>>(
        arrayOf("com.ytw.app.http.formatter.JSONFormatter", "formatJSON"),
        arrayOf("com.ytw.app.http.formatter.JSONFormatter", "format"),
        arrayOf("com.ytw.app.http.formatter.FastjsonFormatter", "format"),
        arrayOf("com.ytw.app.http.formatter.GsonFormatter", "format")
    )

    private val BOOL = Pattern.compile("\"?\\b(?:isis_?right|is_?right|isright|is_?correct|iscorrect|right|correct)\"?\\s*:\\s*(true|false|0|1)", Pattern.CASE_INSENSITIVE)
    private val SCORE = Pattern.compile("\"?\\b(?:score|total|overall|full|integrity|fluency|flucey|pron|percentage)\"?\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)")
    private val LOOK = Pattern.compile("(?i)(is_right|isright|\"score\"|total|integrity|fluency|pron|status|commit)")
    private val VERDICT_FIELD = Pattern.compile("^(is_?right|is_?correct|right|correct)$", Pattern.CASE_INSENSITIVE)

    private val done = HashSet<String>()
    private val saw = intArrayOf(0)
    private val fixes = intArrayOf(0)
    private val dumps = intArrayOf(0)
    private val traces = intArrayOf(0)
    private val papers = intArrayOf(0)
    private val beans = intArrayOf(0)
    private val PAPERS = Pattern.compile("\"questions\"\\s*:\\s*\\[")
    private val SUBMITS = Pattern.compile("\"answers\"\\s*:\\s*\\[")
    private val HASR = Pattern.compile("\"is_right\"")
    private val CHOICE = Pattern.compile("(?i)(is_?right|isis_right|\"commit\"|\"is_right\")")

    @Volatile private var on = true

    fun isEnabled(): Boolean = try {
        Skip.prefs().getBoolean(NetKeys.ENABLED, true)
    } catch (t: Throwable) {
        true
    }

    fun install(cl: ClassLoader) {
        on = isEnabled()
        line("net: build YTW-20261003T2340 pkg=" + (Hooks.ctx()?.packageName ?: "?"))
        line("net: install enabled=" + on)
        if (!on) return
        for (h in FORMATTERS) hookFormatter(cl, h[0], h[1])
        hookParser(cl)
        hookInterceptor(cl)
        hookWire(cl)
        hookLoadClass(cl)
    }

    private fun hookLoadClass(cl: ClassLoader) {
        try {
            hookAllMethodsSafe(
                ClassLoader::class.java, "loadClass",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val nm = param.args[0] as? String ?: return
                        if (!nm.startsWith("com.ytw.app.http.")) return
                        retry(cl)
                    }
                })
            line("net: loadClass hooked")
        } catch (t: Throwable) {
            XposedBridge.log("[YTW-A11Y] net loadClass: " + t)
        }
    }

    private fun retry(cl: ClassLoader) {
        for (h in FORMATTERS) hookFormatter(cl, h[0], h[1])
        hookParser(cl)
        hookInterceptor(cl)
        hookWire(cl)
    }

    private fun hookFormatter(cl: ClassLoader, cls: String, method: String) {
        val key = cls + "#" + method
        if (!done.add(key)) return
        try {
            XposedHelpers.findAndHookMethod(
                cls, cl, method, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val raw = param.args[0] as? String ?: return
                            if (raw.length < 8 || !LOOK.matcher(raw).find()) return
                            dumpChoice("in", raw)
                            if (PAPERS.matcher(raw).find() && raw.length > 800 && papers[0] < 6 && raw.length < 300000) {
                                papers[0]++
                                line("net paper: " + raw)
                            }
                            var body = raw
                            if (SUBMITS.matcher(raw).find()) {
                                if (dumps[0] < 40 && raw.length < 20000) line("net submit body: " + raw)
                                trace("submit")
                                val fixed = fixSubmit(raw)
                                if (fixed != null) {
                                    body = fixed
                                    line("net submit new: " + oneLine(fixed))
                                }
                            } else if (HASR.matcher(raw).find()) {
                                try {
                                    val n = Ans.remember(raw)
                                    if (n > 0) line("net ans: 登记 " + n + " 条正确答案，共 " + Ans.size + " 条")
                                } catch (t: Throwable) {
                                }
                            }
                            val now = rewrite(body)
                            if (now != raw) param.args[0] = now
                            if (saw[0] < 300) {
                                saw[0]++
                                line("net in " + cls.substringAfterLast(".") + "#" + method +
                                    (if (now != raw) " REWRITTEN" else " seen"))
                                line("net in raw: " + oneLine(raw))
                                if (now != raw) line("net in new: " + oneLine(now))
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[YTW-A11Y] net in: " + t)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.hasThrowable()) return
                            val r = param.result as? String ?: return
                            if (r.length < 8 || !LOOK.matcher(r).find()) return
                            dumpChoice("out", r)
                            val now = rewrite(r)
                            if (now == r) return
                            param.result = now
                            if (saw[0] < 300) {
                                saw[0]++
                                line("net out " + cls.substringAfterLast(".") + "#" + method + " REWRITTEN")
                                line("net out raw: " + oneLine(r))
                                line("net out new: " + oneLine(now))
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[YTW-A11Y] net out: " + t)
                        }
                    }
                })
            line("net: hook " + key + " OK")
        } catch (t: Throwable) {
            line("net: hook " + key + " FAIL " + t)
        }
    }

    private fun hookParser(cl: ClassLoader) {
        val key = PARSER + "#onParse"
        if (!done.add(key)) return
        val resp = try {
            XposedHelpers.findClass("okhttp3.Response", cl)
        } catch (t: Throwable) {
            line("net: hook " + key + " FAIL: okhttp3.Response 找不到")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                PARSER, cl, "onParse", resp,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.hasThrowable()) return
                            val o = param.result ?: return
                            val cnt = flip(o, 0, IdentityHashMap<Any, Boolean>())
                            if (beans[0] < 80) {
                                beans[0]++
                                line("net bean: " + o.javaClass.name + " 翻转 " + cnt + " 个对错字段")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[YTW-A11Y] net bean: " + t)
                        }
                    }
                })
            line("net: hook " + key + " OK")
        } catch (t: Throwable) {
            line("net: hook " + key + " FAIL " + t)
        }
    }

    private fun hookInterceptor(cl: ClassLoader) {
        val key = INTERCEPTOR + "#intercept"
        if (!done.add(key)) return
        val chainName = "okhttp3.Interceptor" + "\u0024" + "Chain"
        val chain = try {
            XposedHelpers.findClass(chainName, cl)
        } catch (t: Throwable) {
            line("net: hook " + key + " FAIL: " + chainName + " 找不到")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                INTERCEPTOR, cl, "intercept", chain,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.hasThrowable()) return
                            val resp = param.result ?: return
                            val peek = XposedHelpers.callMethod(resp, "peekBody", 1024L * 1024L) ?: return
                            val body = XposedHelpers.callMethod(peek, "string") as? String ?: return
                            val big = body.contains("\"info_id\"") || body.contains("is_right") ||
                                body.contains("\"answers\"") || body.contains("\"item_id\"")
                            if (body.length < 8) return
                            if (!big && !LOOK.matcher(body).find()) return
                            if (saw[0] >= 400) return
                            saw[0]++
                            if (big) {
                                line("net resp full: " + (if (body.length > 60000) body.substring(0, 60000) + "..." else body))
                            } else {
                                line("net resp: " + oneLine(body))
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("[YTW-A11Y] net resp: " + t)
                        }
                    }
                })
            line("net: hook " + key + " OK")
        } catch (t: Throwable) {
            line("net: hook " + key + " FAIL " + t)
        }
    }

    /** 深度优先遍历 Bean 图，把名字像对错判定的布尔字段置 true。返回改动数。 */
    private fun flip(o: Any?, depth: Int, seen: IdentityHashMap<Any, Boolean>): Int {
        if (o == null || depth > 4) return 0
        if (o is String || o is Number || o is Boolean) return 0
        val c = o.javaClass
        val nm = c.name
        if (nm.startsWith("java.") || nm.startsWith("android.") || nm.startsWith("kotlin.") ||
            nm.startsWith("okhttp3.") || nm.startsWith("okio.")
        ) return 0
        if (seen.put(o, true) != null) return 0
        var count = 0
        val fields: Array<Field> = try {
            c.declaredFields
        } catch (t: Throwable) {
            return 0
        }
        for (f in fields) {
            if (f.isSynthetic) continue
            try {
                f.isAccessible = true
            } catch (t: Throwable) {
                continue
            }
            val t = f.type
            if (t == java.lang.Boolean.TYPE || t == java.lang.Boolean::class.java) {
                if (!VERDICT_FIELD.matcher(f.name).matches()) continue
                try {
                    if (!f.getBoolean(o)) {
                        f.set(o, java.lang.Boolean.TRUE)
                        count++
                    }
                } catch (t2: Throwable) {
                }
            } else if (t == java.lang.String::class.java || t.isPrimitive) {
                continue
            } else if (Collection::class.java.isAssignableFrom(t) || t.isArray || Map::class.java.isAssignableFrom(t)) {
                try {
                    val v = f.get(o)
                    if (v is Collection<*>) for (e in v) count += flip(e, depth + 1, seen)
                    else if (v is Map<*, *>) for (e in v.values) count += flip(e, depth + 1, seen)
                } catch (t2: Throwable) {
                }
            } else if (t.name.startsWith("com.ytw")) {
                try {
                    count += flip(f.get(o), depth + 1, seen)
                } catch (t2: Throwable) {
                }
            }
        }
        return count
    }

    /** 把含对错判定键的报文原文单独打一份（判断判定来自服务器回包哪一层）。 */
    private fun dumpChoice(dir: String, body: String) {
        try {
            if (!CHOICE.matcher(body).find()) return
            if (dumps[0] >= 60) return
            dumps[0]++
            line("net choice " + dir + ": " + oneLine(body))
        } catch (t: Throwable) {
        }
    }

    /** 提交前把「用户所选答案」换成登记的正确答案（服务器按 answer_id 判对错）。 */
    private fun fixSubmit(raw: String): String? {
        return try {
            val o = JSONObject(raw)
            if (!o.has("info_id") || !o.has("items")) return null
            val info = o.optString("info_id", "")
            val items = o.optJSONArray("items") ?: return null
            var n = 0
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val itemId = it.optString("item_id", "")
                val arr = it.optJSONArray("answers") ?: continue
                val cur = arr.optJSONObject(0) ?: continue
                val c = Ans.correct(info, itemId) ?: continue
                val want = c.optInt("answer_id", -1)
                if (want <= 0 || want == cur.optInt("answer_id", -1)) continue
                val na = JSONObject(cur.toString())
                na.put("answer_id", want)
                na.put("content", c.optString("content", cur.optString("content", "")))
                it.put("answers", JSONArray().put(na))
                n++
                line("net submit fix: info_id=" + info + " item_id=" + itemId + " " +
                    cur.optInt("answer_id", -1) + " -> " + want + " (" + c.optString("content", "") + ")")
            }
            if (n > 0) o.toString() else null
        } catch (t: Throwable) {
            line("net submit fix FAIL " + t)
            null
        }
    }

    private fun rewrite(src: String): String {
        var s = replaceAll(BOOL, src) { m ->
            val v = m.group(2) ?: "false"
            val fix = if (v == "true" || v == "1") v else if (v == "0") "1" else "true"
            m.group(1) + fix
        }
        s = replaceAll(SCORE, s) { m ->
            val old = (m.group(2) ?: "0").toDoubleOrNull() ?: 0.0
            m.group(1) + (if (old <= 1.5) "1" else "100")
        }
        if (s != src && fixes[0] < 80) {
            fixes[0]++
            line("net fix: " + oneLine(src))
            line("net fix -> " + oneLine(s))
        }
        return s
    }

    private fun replaceAll(p: Pattern, s: String, f: (java.util.regex.Matcher) -> String): String {
        val m = p.matcher(s)
        val sb = StringBuffer()
        var any = false
        while (m.find()) {
            any = true
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(f(m)))
        }
        if (!any) return s
        m.appendTail(sb)
        return sb.toString()
    }

    /** 记录调用方（只保留 App 自己的帧），用来看清哪段代码在提交/解析。 */
    private fun trace(tag: String) {
        if (traces[0] >= 40) return
        traces[0]++
        try {
            val sb = StringBuilder()
            var n = 0
            for (e in Thread.currentThread().stackTrace) {
                val c = e.className
                if (!c.startsWith("com.ytw.app.")) continue
                if (c.startsWith("com.ytw.app.http.")) continue
                if (n++ >= 10) break
                sb.append(c).append("#").append(e.methodName).append(":").append(e.lineNumber).append(" < ")
            }
            line("net trace " + tag + ": " + sb)
        } catch (t: Throwable) {
        }
    }

    private fun oneLine(s: String): String = if (s.length > 1500) s.substring(0, 1500) + "..." else s

    // ---- 传输层：真正改写出站请求体 / 入站响应体（此前只改了 formatter 的日志副本） ----
    private val wire = intArrayOf(0)

    private fun hookWire(cl: ClassLoader) {
        if (!done.add("wire")) return
        val rb = try {
            XposedHelpers.findClass("okhttp3.RequestBody", cl)
        } catch (t: Throwable) {
            line("net wire: okhttp3.RequestBody 找不到 -> " + t)
            return
        }
        val builder = try {
            XposedHelpers.findClass("okhttp3.Request\u0024Builder", cl)
        } catch (t: Throwable) {
            line("net wire: okhttp3.Request.Builder 找不到 -> " + t)
            return
        }
        try {
            XposedHelpers.findAndHookMethod(builder, "post", rb, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    wireBody(param, 0)
                }
            })
            line("net wire: hook Request.Builder#post(RequestBody) OK")
        } catch (t: Throwable) {
            line("net wire: post FAIL " + t)
        }
        try {
            XposedHelpers.findAndHookMethod(builder, "method", String::class.java, rb, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    wireBody(param, 1)
                }
            })
            line("net wire: hook Request.Builder#method(String,RequestBody) OK")
        } catch (t: Throwable) {
            line("net wire: method FAIL " + t)
        }
        hookResponseString(cl)
    }

    private fun wireBody(param: XC_MethodHook.MethodHookParam, idx: Int) {
        try {
            val body = param.args[idx] ?: return
            val raw = readBody(body) ?: return
            if (!raw.contains("\"info_id\"") && !raw.contains("\"answers\"")) return
            if (wire[0] < 60) {
                wire[0]++
                line("net wire req[" + idx + "]: " + oneLine(raw))
            }
            val fixed = fixSubmit(raw) ?: return
            val nb = makeBody(body, fixed)
            if (nb == null) {
                line("net wire: 无法重建 RequestBody，放弃改写")
                return
            }
            param.args[idx] = nb
            line("net wire fix: " + oneLine(fixed))
        } catch (t: Throwable) {
            line("net wire ERR: " + t)
        }
    }

    private fun readBody(body: Any): String? {
        val cl = body.javaClass.classLoader
        val bufCls = Class.forName("okio.Buffer", false, cl)
        val buf = bufCls.getDeclaredConstructor().newInstance()
        XposedHelpers.callMethod(body, "writeTo", buf)
        return XposedHelpers.callMethod(buf, "readUtf8") as? String
    }

    private fun makeBody(old: Any, text: String): Any? {
        val cl = old.javaClass.classLoader
        val rb = Class.forName("okhttp3.RequestBody", false, cl)
        val mtCls = Class.forName("okhttp3.MediaType", false, cl)
        val mt = try {
            XposedHelpers.callMethod(old, "contentType")
        } catch (t: Throwable) {
            null
        }
        try {
            return rb.getMethod("create", mtCls, String::class.java).invoke(null, mt, text)
        } catch (t: Throwable) {
        }
        try {
            return rb.getMethod("create", String::class.java, mtCls).invoke(null, text, mt)
        } catch (t: Throwable) {
        }
        try {
            return rb.getMethod("create", mtCls, ByteArray::class.java).invoke(null, mt, text.toByteArray(Charsets.UTF_8))
        } catch (t: Throwable) {
        }
        return null
    }

    private fun hookResponseString(cl: ClassLoader) {
        try {
            val rb = XposedHelpers.findClass("okhttp3.ResponseBody", cl)
            XposedHelpers.findAndHookMethod(rb, "string", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val raw = param.result as? String ?: return
                        if (!raw.contains("is_right") && !raw.contains("\"answers\"") && !raw.contains("\"sound\"")) return
                        if (wire[0] < 60) {
                            wire[0]++
                            line("net wire resp: " + oneLine(raw))
                        }
                        val now = rewrite(raw)
                        if (now != raw) {
                            param.result = now
                            line("net wire resp fix: " + oneLine(now))
                        }
                    } catch (t: Throwable) {
                    }
                }
            })
            line("net wire: hook ResponseBody#string() OK")
        } catch (t: Throwable) {
            line("net wire: resp FAIL " + t)
        }
    }

    private fun line(msg: String) {
        // 日志文件生成已按用户要求移除
    }
}
