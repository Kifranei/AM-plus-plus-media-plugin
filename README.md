# AM 媒体增强插件

用于 [AM++](https://github.com/Zennmn/AM-plus-plus) 插件系统 SDK v1 的独立插件，适配 Apple Music **7.0.0-beta / 1606**。四项功能均已由用户测试通过，每项都有独立开关。

下载：[最新发布](https://github.com/Kifranei/AM-plus-plus-media-plugin/releases/latest)。

## 功能

| 功能 | 效果 |
| --- | --- |
| 词幕集成 | 传递原生及替换歌词、逐词时间轴和播放进度，保留英文空格、v1/v2、x-bg。副行跟随 AM 翻译/注音开关；同时开启时优先翻译，没有翻译的行回退注音。 |
| 使用 iOS 媒体控制按钮 | 替换播放页 Chromecast 图标，调用系统音频输出。Xiaomi 系优先妙播，Android 14+ 尝试 AOSP 输出面板，不可用时打开蓝牙设置。 |
| 解除歌词分享限制 | 保留 AM 原生选行、字符限制及渐变卡片，增加系统图片分享与保存到相册。 |
| 液态玻璃音质弹窗 | 白色音质图标、居中参数和同排「设置」「好」按钮；准备好玻璃内容再显示，避免原生弹窗闪现。 |

## 使用

需要包含 [插件系统 SDK v1](https://github.com/Zennmn/AM-plus-plus/pull/79) 的 AM++。最低 Android 9；歌词分享/保存需要 Android 10+，玻璃音质弹窗需要 Android 13+。

1. 安装并启用支持插件系统的 AM++，完全停止并重开 Apple Music。
2. 从 Releases 下载 `ampp-media-integrations-1.0.0.zip`。
3. 在 AM++ 设置的「插件」中导入 ZIP，开启「下次启动启用」，重开 Apple Music。
4. 打开「AM 媒体增强」插件设置，开启需要的功能，重开 Apple Music 后生效。

插件和四项功能默认关闭。设置保存在插件独立目录，更新保留配置；修改设置、启停、更新和删除均在重开 Apple Music 后生效。

当前玻璃功能复用已安装 AM++ 提供的 Compose/Backdrop 运行库和资源。纯嵌入模式的玻璃资源访问尚未单独验收。

## 独立构建

需要 JDK 17+、Android SDK Platform 37.0 和 Build Tools 37.0.0。配置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir`，然后执行：

```powershell
.\gradlew.bat :media-plugin:testDebugUnitTest :media-plugin:lintDebug :media-plugin:pluginZip
python -B scripts/verify-sdk.py
python -B scripts/verify-media-plugin.py media-plugin/build/dist/ampp-media-integrations-1.0.0.zip
```

Linux/macOS 使用 `./gradlew`。产物位于 `media-plugin/build/dist/`。

仓库携带固定版本的编译用 SDK 与玻璃公共接口 JAR，来源及校验和见 [lib/README.md](lib/README.md)。构建不依赖本地 AM++ 源码。SDK、宿主类、Compose 和玻璃参考库均不打入插件 ZIP；LyricProvider 及其运行依赖随插件分发。

可用自己的 Apple Music APK 验证目标符号：

```text
python -B scripts/verify-host-profile.py apple-music-1606.apk --glass
```

代码通过 SDK 注册 Hook 和观察者，使用插件自己的版本适配表、素材和设置。验收与构建记录见 [VALIDATION.md](VALIDATION.md)。

## 许可证

[GPL-3.0](LICENSE)。本插件源自 AM++ 媒体集成实现；第三方来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，相关声明也随插件 ZIP 分发。
