package io.github.jm350234shenzuo.bcz.a11y

import android.content.Context

/**
 * 现场诊断日志：按用户要求已关闭写盘。
 * 保留 init/line 的同名空实现，兼容其余 40+ 处调用点，业务逻辑不受影响。
 */
object Diag {

    fun init(c: Context?) {
    }

    fun line(msg: String) {
    }
}
