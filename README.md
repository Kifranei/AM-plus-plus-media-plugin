# AM 媒体增强插件

用于 [AM++](https://github.com/Zennmn/AM-plus-plus) 插件系统 SDK v1 的独立插件，适配 Apple Music **7.0.0-beta / 1606、1607、1609、1611、1613** 及 **7.0.0 / 1614**（同一个发布包按宿主版本号自动选择对应 profile；其他版本会拒绝加载并列出受支持版本）。提供八个功能开关及五个系统歌词投递选项（预发布，待测试），中英文设置跟随界面语言。

更新日志见 [CHANGELOG.md](CHANGELOG.md)。

下载：[最新发布](https://github.com/Kifranei/AM-plus-plus-media-plugin/releases/latest)。

## 功能

| 功能 | 效果 |
| --- | --- |
| 词幕集成 | 传递原生及替换歌词、逐词时间轴和播放进度，保留英文空格、v1/v2、x-bg。副行跟随 AM 翻译/注音开关；同时开启时优先翻译，没有翻译的行回退注音。 |
| 使用 iOS 媒体控制按钮 | 替换播放页 Chromecast 图标，调用系统音频输出；冷启动首次进入封面页即可显示，不依赖 Chromecast 路由，连接或断开蓝牙后保持可见。图标和灰色设备名跟随当前媒体输出路由，区分耳机、AirPods 和音响；多个蓝牙设备同时连接时显示当前使用的设备，切回手机扬声器恢复默认投放（广播塔）图标并清除蓝牙名称，音响使用音响图标。封面、歌词及队列共用标签层，长名称省略；原生 SharePlay 占用位置时保留其控件。平板采用更小的歌曲信息；双栏时输出在左下，歌词和队列在右下，翻译在右上，随机和循环在上一曲／下一曲外侧。Xiaomi 系优先妙播，Android 14+ 尝试 AOSP 输出面板，不可用时打开蓝牙设置。 |
| 解除分享白名单限制 | 在歌曲和歌词的原生分享应用横栏中增加「分享图片」和「保存歌曲卡片／保存歌词卡片」，通过系统分享面板选择应用，也可保存到相册。使用 AM 原生封面、歌曲／艺人文字、标志和渐变背景；歌词分享保留原生选行与字符限制。 |
| 播放页界面启用液态玻璃 | 一个开关为睡眠定时、删除确认弹窗、更多菜单和音质弹窗启用液态玻璃效果。更多菜单实时模糊播放页，保留原生操作；删除确认使用浅灰胶囊按钮和红色删除文字；睡眠定时保留原生选项与勾选；音质弹窗使用白色图标、居中参数和同排「设置」「好」按钮。Android 13+ 可用。 |
| 弹窗液态玻璃样式（下拉） | 作用于睡眠定时、删除确认和音质弹窗（更多菜单保持自身效果）。三种：**同底栏**（默认，AM++ 底栏材质：vibrancy + 4dp 模糊、透镜、0xFF121212 40% 着色、宿主默认高光；裁剪到 32dp 圆角）；**同更多菜单**（与播放页更多菜单相同的中性灰玻璃：20dp 模糊、0xFF3A3A3C 36%、白色渐变细描边）；**无磨砂（早期效果）**（vibrancy + 20dp 模糊 + 透镜，无着色面；描边同更多菜单的中性白渐变细边，非宿主默认高光）。均为中性灰白，不含蓝色。保存后重开 Apple Music 生效。 |
| 播放页顶部把手 | 手机和平板普通封面显示 Flamingo 风格的非交互把手；全屏封面隐藏，随原生展开进度显隐，保留原生手势。 |
| 播放页系统圆角 | 读取 Android 12+ 系统圆角，接口缺失时使用系统资源回退；原生背景和动画层使用相同顶部圆角，避免裁剪正在展开的玻璃卡片。 |
| 播放页音量条 | 手机和平板、横竖屏的封面、歌词和播放列表页均支持 iOS 风格音量条，控制系统媒体音量并同步硬件音量键。按当前控件实际尺寸预留高度，适应不同系统及 AM 内部 DPI；双栏时音量条留在播放控件所在列。原生进出动画期间保持控件布局，切页时逐帧恢复音量条的有效位置。收起播放页时隐藏，固定音量设备显示为不可调节。平板同时开启 iOS 媒体控制时，按实测控件高度紧凑排列进度条、播放键、音量条和底部动作，为封面保留更多空间。OPPO / realme / OnePlus（ColorOS / realme UI / OxygenOS）因默认 DPI 较大暂不支持，自动禁用且开关不可开启。 |
| 下载封面、歌词和介绍 | 艺术家详情页标题旁下载静态 JPEG、动态 MP4 和传记，带特色字标的艺术家另可下载最高分辨率透明 PNG 字标；专辑详情页可分别下载静态封面（原始最大尺寸 JPEG）、动态 MP4 和介绍。播放页更多菜单或长按歌词按钮保存原始 TTML，包括逐词时间、翻译、注音及人声信息。保存到 `Download/AM++`，支持取消，失败后清理未完成文件。 |
| 系统歌词投递（1.3.5-Pre，待测试） | 复用词幕的歌词与时间轴捕获，五个独立开关，默认关闭：**状态栏歌词（魅族 Ticker）**、**桌面歌词（逐字）**（需为 Apple Music 开启「显示在其他应用上层」）、**媒体通知歌词**（写入媒体通知标题，供蓝牙/车机显示）、**SuperLyric 投递**、**Lyric Getter 投递**（未安装对应模块时自动跳过）。 |

## 使用

需要包含 [插件系统 SDK v1](https://github.com/Zennmn/AM-plus-plus/pull/79) 的 AM++。最低 Android 9；歌词分享/保存需要 Android 10+，玻璃音质弹窗需要 Android 13+。

1. 安装并启用支持插件系统的 AM++，完全停止并重开 Apple Music。
2. 从 Releases 下载最新的 `ampp-media-integrations-*.zip`。
3. 在 AM++ 设置的「插件」中导入 ZIP，开启「下次启动启用」，重开 Apple Music。
4. 打开「AM 媒体增强」插件设置，开启需要的功能，重开 Apple Music 后生效。

插件默认关闭；各功能开关默认关闭。设置保存在插件独立目录，更新保留配置；修改设置、启停、更新和删除均在重开 Apple Music 后生效。

「解除分享白名单限制」同时控制歌曲卡片与歌词卡片。旧版歌词分享开关自动迁移，明确保存的新开关值优先；新增图片入口位于原生可分享应用横栏，原有拷贝链接和更多选项保留。

玻璃开关的中英文标题和说明：

| 语言 | 标题 | 下方说明 |
| --- | --- | --- |
| 中文 | 播放页界面启用液态玻璃 | 为睡眠定时、删除确认弹窗、更多菜单和音质弹窗启用液态玻璃效果。 |
| 中文 | 弹窗液态玻璃样式（下拉：同底栏 / 同更多菜单 / 无磨砂（早期效果）） | 作用于睡眠定时、删除确认和音质弹窗；更多菜单保持自身效果。保存后重开 Apple Music 生效。 |
| English | Enable Liquid Glass on the player screen | Apply Liquid Glass to the sleep timer, deletion confirmation dialog, More menu, and audio quality dialog. |
| English | Popup Liquid Glass style (dropdown) | Applies to the sleep timer, deletion confirmation and audio quality popups (the More menu keeps its own look): **Same as bottom bar** (default; AM++ bottom-bar material: vibrancy + 4dp blur, lens, 0xFF121212 @ 40% tint, host-default highlight; clipped to 32dp rounded corners), **Same as More menu** (the player More menu's neutral gray glass: 20dp blur, 0xFF3A3A3C @ 36%, thin white gradient rim), **No frost (early look)** (vibrancy + 20dp blur + lens, no surface tint; the same neutral white gradient rim as the More menu, not the host-default highlight). All neutral gray/white, never blue. Reopen Apple Music after saving. |

系统没有提供有效圆角时沿用 AM 原样式。平板按钮排列只在实际存在双栏空间时生效，单栏保留原生动作位置。翻译和人声控制仍使用原生按钮。

动态封面使用当前详情页的原生资源地址，保存为真实 MP4。支持直链 MP4 和有限、无加密、单视频轨的 fMP4 HLS（包括同一 MP4 的连续字节段），兼容 Apple 播放列表的 `_AVG-BANDWIDTH` 扩展字段。分片视频在保存前无损重新封装为普通 MP4，补全时长、帧索引并将播放起点归零，改善系统相册播放兼容性；遇到其他结构会显示明确失败原因。

当前玻璃功能复用已安装 AM++ 提供的 Compose/Backdrop 运行库和资源。纯嵌入模式的玻璃资源访问尚未单独验收。

## 独立构建

需要 JDK 17+、Android SDK Platform 37.0 和 Build Tools 37.0.0。配置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir`，然后执行：

```powershell
.\gradlew.bat :media-plugin:testDebugUnitTest :media-plugin:lintDebug :media-plugin:pluginZip
python -B scripts/verify-sdk.py
python -B scripts/verify-media-plugin.py media-plugin/build/dist/ampp-media-integrations-1.3.5-Pre.zip
```

Linux/macOS 使用 `./gradlew`。产物位于 `media-plugin/build/dist/`。

仓库携带固定版本的编译用 SDK 与玻璃公共接口 JAR，来源及校验和见 [lib/README.md](lib/README.md)。构建不依赖本地 AM++ 源码。SDK、宿主类、Compose 和玻璃参考库均不打入插件 ZIP；LyricProvider 及其运行依赖随插件分发。

可用自己的 Apple Music APK 验证目标符号：

```text
python -B scripts/verify-host-profile.py apple-music-1613.apks --glass   # 按 APK 自身版本选择 profile
python -B scripts/verify-all-profiles.py --apk-dir ~/Downloads   # 逐个核对所有已发布 profile（没有 APK 的版本显示 SKIP）
```

代码通过 SDK 注册 Hook 和观察者，使用插件自己的版本适配表、素材和设置。验收与构建记录见 [VALIDATION.md](VALIDATION.md)。

## 许可证

[GPL-3.0](LICENSE)。本插件源自 AM++ 媒体集成实现；第三方来源和许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，相关声明也随插件 ZIP 分发。
