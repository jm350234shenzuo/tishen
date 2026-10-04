package io.github.jm350234shenzuo.ytw.a11y

import android.content.Context

/**
 * 原「现场诊断日志」（写 ytw-a11y-log.txt）已按用户要求整体移除写文件功能。
 * 保留同名空实现，避免逐个改动调用点。
 */
object Diag {

    fun init(c: Context?) {
    }

    fun line(msg: String) {
    }
}
