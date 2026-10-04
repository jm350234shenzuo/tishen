# 题神 · TiShen —— LSPosed 模块集合

针对三个学习类 App 的 LSPosed 增强模块（判分接管 / 跳过题目），每个模块独立发布。

| 模块 | 模块包名 | 目标应用 | 独立仓库 |
| --- | --- | --- | --- |
| 题神·百词斩 | `io.github.jm350234shenzuo.bcz.a11y` | `com.jiongji.andriod.card` | [io.github.jm350234shenzuo.bcz.a11y](https://github.com/jm350234shenzuo/io.github.jm350234shenzuo.bcz.a11y) |
| 题神·天学网 | `io.github.jm350234shenzuo.txw.a11y` | `com.up366.mobile` | [io.github.jm350234shenzuo.txw.a11y](https://github.com/jm350234shenzuo/io.github.jm350234shenzuo.txw.a11y) |
| 题神·优题网 | `io.github.jm350234shenzuo.ytw.a11y` | `com.ytw.app` | [io.github.jm350234shenzuo.ytw.a11y](https://github.com/jm350234shenzuo/io.github.jm350234shenzuo.ytw.a11y) |

三个 APK 分别在各自的仓库 Releases 中发布（tag `1-1.0`）；本仓库是源码与构建脚本总仓。

## 目录

- `baicizhan-a11y/` —— 题神·百词斩
- `tianxuewang-a11y/` —— 题神·天学网
- `youti-a11y/` —— 题神·优题网
- `dist/` —— 构建产物（三个 APK 与图标预览）

## 构建

每个模块目录下都有 `build.cmd`（Gradle 8.9 + JDK 21 + Android SDK）。根目录 `build-all.cmd` 可一键构建三个模块并把 APK 收拢到 `dist/`：

```
build-all.cmd
```

签名使用各模块自带的 release keystore（本仓库不含密钥文件）。

## 安装

1. 从对应模块仓库的 Releases 下载 APK 安装；
2. 在 LSPosed 中启用模块，作用域勾选对应目标应用；
3. 强制停止目标 App（或重启）后重新打开。

## LSPosed 模块仓库

三个模块均已向 [Xposed-Modules-Repo/submission](https://github.com/Xposed-Modules-Repo/submission) 提交收录申请（`[submission] io.github.jm350234shenzuo.*`），收录后可在 LSPosed 的模块列表里直接搜索到。

## 免责声明

本项目仅供学习与研究自动化测试使用，请勿用于违反目标 App 服务条款的用途，使用风险自负。
