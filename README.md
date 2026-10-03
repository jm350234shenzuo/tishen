# 题神（TiShen）· LSPosed 学习 App 增强模块

针对三款学习类 App 的 LSPosed 增强模块：字号/对比度、降动画、计时放宽、常亮、解方向锁、悬浮控制球，以及本地判定与评分链路的接管。

| 模块 | 目录 | 目标 App | 包名 |
| --- | --- | --- | --- |
| 题神·百词斩 | `baicizhan-a11y/` | 百词斩 7.10.22 | `com.jiongji.andriod.card` |
| 题神·天学网 | `tianxuewang-a11y/` | 天学网学生 6.0.1 | `com.up366.mobile` |
| 题神·优题网 | `youti-a11y/` | 优题网 | `com.ytw.app` |

## 安装
1. 安装 `dist/` 下对应 APK（minSdk 23，已用 v1+v2 签名）。
2. 在 LSPosed 中启用模块，作用域勾选上表对应的目标 App。
3. 强制停止目标 App 后重新打开，悬浮控制球出现即加载成功。

## 构建
每个模块目录下执行 `build.cmd`（Windows，需 JDK 21 + Android SDK 35 + Gradle 8.9）。
仓库不含签名密钥：请自行生成 keystore 并在 `app/build.gradle.kts` 的 signingConfigs 中填写，否则请改用 debug 构建。

## 免责声明
仅供个人学习与研究 Android 逆向与 Xposed 框架使用。使用者需自行承担账号与合规风险，请勿用于违反目标平台服务条款的用途。
