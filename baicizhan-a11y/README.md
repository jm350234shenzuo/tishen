# 百词斩无障碍助手 (百词斩 A11y)

一个 LSPosed 模块，用于让视障、肢障、认知障碍用户能更顺利地使用「百词斩」做题/背单词。
只做可访问性增强与操作代劳，全程离线，不触碰学习数据。

## 功能

| 功能 | 面向 | 说明 |
| --- | --- | --- |
| 字体放大 1.0–2.0 倍 | 低视力 | 通过 Configuration.fontScale 全局生效 |
| 强制浅色 / 深色 | 低视力、光敏感 | 覆盖 App 的日夜模式 |
| 降低动画 | 光敏感、晕动 | 把动画时长归零，减少闪烁位移 |
| 加大点击区域 | 肢障、精细动作困难 | 小按钮扩到 48dp |
| 悬浮控制球 | 通用 | 可拖动；面板里一键跳过本题、开关自动跳过/自动听音、放大缩小文字 |
| 跳过本题（hook 级） | 肢障、认知障碍 | 直接调用 App 自己注册的点击处理器触发原有逻辑，优先于模拟点击 |
| 自动跳过 | 无法连续操作 | 打开后持续扫描并按文字代点已有按钮 |
| 网页(H5)按钮识别 | 通用 | hook 页面 addEventListener，直接调用页面自己的回调 |
| 自动关弹窗 | 视障、认知障碍 | 按关键词关闭广告/推荐类弹窗 |
| 自动听音（hook 级） | 省操作 | 直接调用 App 自己的「听音」按钮处理器播放题目发音，省一次手动按键 |
| 保持常亮 / 解除方向锁 | 肢障 | 答题时不黑屏、屏幕可旋转 |
| 答题计时放宽/不限时 | 认知、行动不便 | 把倒计时按倍数放宽，不影响学习记录 |

## 安装（4 步）

1. 安装 app/build/outputs/apk/release/baicizhan-a11y-1.0.apk（或仓库根目录同名 APK）。
2. 打开 LSPosed → 模块，启用本模块，作用域勾选「百词斩」。
3. 强行停止「百词斩」并重新打开。
4. 打开本模块的设置页（桌面图标「百词斩无障碍助手」）做进一步设置；每次改设置后需重启一次 百词斩 才生效。

目标包名默认：com.jiongji.andriod.card、com.jiongji.andriod.pocket（可在设置页增删）。

## 边界说明

- 跳过 = 代点屏幕上已经存在的按钮，通过 Xposed hook 拿到 App 自己注册的 onClick 后直接调用，全程只触发 App 已有的逻辑。
- 自动听音 = 代点「听音」按钮，播放的是 App 自己提供的题目发音，不是屏幕朗读、也不朗读题干文字。
- 不自动答题、不伪造/修改学习记录、不绕过会员付费内容。

## 技术实现

- Xposed 编译期桩（xposed-api 子工程，纯 JDK 桩类），不依赖 api.xposed.info 仓库。
- 跳过引擎：hook View#setOnClickListener 捕获 App 自身监听器；hook View#performClick 做侦查学习；WebView#addJavascriptInterface + evaluateJavascript 注入脚本 hook 页面 EventTarget.prototype.addEventListener。
- 自动听音：hook TextView#setText / View#setContentDescription 侦测界面变化；hook MediaPlayer#start / SoundPool#play 标记已播防重播；稳定延迟后每屏最多播一次，优先调用捕获到的 App 自身 onClick。
- 配置经 XSharedPreferences（baicizhan-a11y / prefs 文件 config）读写，模块与设置页同源。

## 构建

build.cmd

产物 app/build/outputs/apk/release/baicizhan-a11y-1.0.apk，签名密钥 app/release.keystore（store/key 口令均为 bcz123456，别名 bcz）。
