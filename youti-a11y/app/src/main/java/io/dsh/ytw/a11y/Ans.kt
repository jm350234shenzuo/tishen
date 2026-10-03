package io.dsh.ytw.a11y

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 正确答案登记表。
 *
 * 优题网的选择题是本地判定（LookWordSelectMeanFragment 选下标 → adjsutAnswer 播对/错音），
 * 但记进学习记录的提交报文里只带「用户所选选项的 answer_id」，服务器按 answer_id 判对错。
 * 本地题面（DoWordActivity#initLookWordSelectAnswerData 的 JSONObject）与服务器卷子里
 * 每个选项都带 is_right，这里把 is_right=true 的选项按 info_id/item_id 记下来，
 * 供 Net 层在报文真正发出去之前把 answers 换成正确答案。
 */
internal object Ans {
    private val map = ConcurrentHashMap<String, JSONObject>()

    fun remember(json: String): Int = try {
        remember(JSONObject(json))
    } catch (t: Throwable) {
        0
    }

    fun remember(root: JSONObject?): Int = walk(root, "", "", 0)

    fun correct(infoId: String, itemId: String): JSONObject? {
        map[key(infoId, itemId)]?.let { return it }
        if (itemId.isNotEmpty()) map[key(infoId, "")]?.let { return it }
        return null
    }

    val size: Int get() = map.size

    private fun key(infoId: String, itemId: String) = infoId + "/" + itemId

    private fun walk(v: Any?, info: String, item: String, depth: Int): Int {
        if (depth > 12) return 0
        var n = 0
        when (v) {
            is JSONObject -> {
                val i2 = if (v.has("info_id")) v.optString("info_id", info) else info
                val t2 = if (v.has("item_id")) v.optString("item_id", item) else item
                val ans = v.optJSONArray("answers")
                if (ans != null && ans.length() > 0 && ans.optJSONObject(0)?.has("is_right") == true) {
                    for (k in 0 until ans.length()) {
                        val a = ans.optJSONObject(k) ?: continue
                        if (!a.optBoolean("is_right", false)) continue
                        val rec = JSONObject()
                        rec.put("answer_id", a.optInt("answer_id", -1))
                        rec.put("content", a.optString("content", ""))
                        map[key(i2, t2)] = rec
                        if (t2.isNotEmpty()) map[key(i2, "")] = rec
                        n++
                    }
                }
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    n += walk(v.opt(k), i2, t2, depth + 1)
                }
            }
            is JSONArray -> {
                for (k in 0 until v.length()) n += walk(v.opt(k), info, item, depth + 1)
            }
        }
        return n
    }
}
