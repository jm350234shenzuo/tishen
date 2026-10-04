package io.github.jm350234shenzuo.ytw.a11y

/**
 * 原「运行时类侦查」（写 ytw-a11y-recon.txt）已按用户要求整体移除。
 * 保留 ReconKeys 与空 install()，避免改动 Hooks.kt / 设置页的调用点。
 */
object ReconKeys {
    const val ENABLED = "rc_enabled"
}

object Recon {

    fun install(cl: ClassLoader) {
    }
}
