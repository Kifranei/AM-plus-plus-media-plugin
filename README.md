# AM 媒体增强插件

用于 [AM++](https://github.com/Zennmn/AM-plus-plus) 插件系统 SDK v1 的独立插件，适配 Apple Music **7.0.0-beta / 1606、1607、1609、1611、1613** 及 **7.0.0 / 1614**（同一个发布包按宿主版本号自动选择对应 profile；其他版本会拒绝加载并列出受支持版本）。提供八个功能开关，中英文设置跟随界面语言。

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

## 使用

需要包含 [插件系统 SDK v1](https://github.com/Zennmn/AM-plus-plus/pull/79) 的 AM++。最低 Android 9；歌词分享/保存需要 Android 10+，玻璃音质弹窗需要 Android 13+。

1. 安装并启用支持插件系统的 AM++，完全停止并重开 Apple Music。
2. 从 Releases 下载最新的 `ampp-media-integrations-*.zip`。
3. 在 AM++ 设置的「插件」中导入 ZIP，开启「下次启动启用」，重开 Apple Music。
4. 打开「AM 媒体增强」插件设置，开启需要的功能，重开 Apple Music 后生效。

插件默认关闭；各功能开关默认关闭。设置保存在插件独立目录，更新保留配置；修改设置、启停、更新和删除均在重开 Apple Music 后生效。

「解除分享白名单限制」同时控制歌曲卡片与歌词卡片。旧版歌词分享开关自动迁移，明确保存的新开关值优先；新增图片入口位于原生可分享应用横栏，原有拷贝链接和更多选项保留。

v1.1.0 将四个玻璃功能合并为「播放页界面启用液态玻璃」。升级时，旧版任一玻璃开关已开启则迁移为开启；明确保存的新开关值优先。把手与系统圆角仍各有独立开关，旧版合并的播放页外观设置会迁移到两项。此版本同时修复媒体按钮冷启动和蓝牙连接后隐藏、全屏封面把手、玻璃动画切角、菜单背景跟手及关闭崩溃，并加入蓝牙设备名称与图标、播放页音量条。

v1.3.4 / 16 同时新增 Apple Music 7.0.0 / 1614 profile `applemusic-1614.json`（1614 的 versionName 为 `7.0.0`，不再带 `-beta`；profile 仍按包名 + 版本名 + 版本号精确匹配）。由 1613 逐项按 1614 DEX 重新核对：引用的类均未改名，仅 `utils.Q0$a`→`R0$a`（CJK 判定）；RecyclerView.Adapter 覆写方法整体后移一位：`player.x` 的 `V`→`W`（CJK 逐字动画）、`k`→`l`、`m`→`n`、`n0`→`o0`，「更多」菜单适配器 `Z6.d.m`→`n`、`Z6.b.l`→`m`，`ea.a` 的 `w`→`x`、`k`→`l`；其余字段、方法与资源名不变，并用 1614 APK 校验全部符号与资源名。

v1.3.4 / 16 also adds the Apple Music 7.0.0 / 1614 profile `applemusic-1614.json` (1614 reports versionName `7.0.0` without `-beta`; selection is still exact on package + version name + version code). Re-resolved from 1613 against the 1614 DEX: no referenced class was renamed except `utils.Q0$a` → `R0$a` (CJK predicate); RecyclerView.Adapter overrides shifted by one letter: `player.x` `V`→`W` (CJK karaoke animation), `k`→`l`, `m`→`n`, `n0`→`o0`; More-menu adapter `Z6.d.m`→`n`, `Z6.b.l`→`m`; `ea.a` `w`→`x`, `k`→`l`. All other fields, methods and resource names are unchanged, and every symbol and resource name is verified against the 1614 APK.

v1.3.4 / 16 适配 Apple Music 7.0.0-beta / 1613，并修复 1613 上词幕状态栏歌词无法启动（1613 的歌词指针字段由 `g1` 改为 `f1`，旧名称仍存在但类型为 int）；平板横屏双栏播放页不再显示重复的把手（宽度 ≥ 600dp 时由宿主/AM++ 提供把手）。profile 校验脚本现按精确类型／签名检查词幕、音质对话框和卡片分享的命名成员。新增 Apple Music 7.0.0-beta / 1613 profile `applemusic-1613.json`，由 1611 逐项按 1613 DEX 重新映射（39 个类改名（含 a7.d），如 `player.y`→`x`、`fragment.m`→`l`、`fragment.e`→`d`、`androidx.fragment.app.l`→`n`；字段普遍前移一位，如 `PlayerMainFragment.b0`→`a0`、`BottomSheetBehavior.o0`→`n0`；卡片分享观察者 `commerce.activities.m`→`a7.d`；「更多」菜单点击方法改回 `z(int, View, item)`），并用 1613 APK 校验全部符号与资源名。Hook 改为严格失败开放：回调在暂存副本上运行，抛出异常时宿主调用保持原样、不再让 AM++ 停用整个插件，同一 Hook 失败 3 次后仅注销该 Hook。新增测试诊断：所有诊断行以 `media-diag:` 开头，写入 LSPosed 日志、logcat 标签 `AMPP-media` 及插件数据目录 `media-diag.log`，包括启动时的宿主版本／profile、每个功能的 ACTIVE／UNSUPPORTED／FAILED、启动自检列出的全部无法解析的合约（类.方法(参数) 或 类.字段:类型），以及运行中失败的 Hook 成员。测试者提交日志时请过滤 `media-diag:`。

v1.3.4 / 16 adapts to Apple Music 7.0.0-beta / 1613 and fixes Lyricon status-bar lyrics not starting on 1613 (the lyrics pointer field moved from `g1` to `f1`; the old name still exists as an int); the two-pane tablet-landscape player no longer shows a duplicate drag handle (at ≥ 600dp the host/AM++ provides it). The profile verifier now checks exact types/signatures for the Lyricon, audio-quality dialog and card-sharing named members. Adds the Apple Music 7.0.0-beta / 1613 profile `applemusic-1613.json`, remapped from 1611 against the 1613 DEX (39 class renames, most fields shifted by one letter, card-sharing observer `commerce.activities.m` → `a7.d`, More-menu click back to `z(int, View, item)`), with all symbols and resource names verified against the 1613 APK. Hooks are now strictly fail-open: callbacks run on a staged copy of the call, a throwing callback leaves the host call untouched and no longer makes AM++ stop the whole plugin, and a hook failing 3 times unregisters only itself. Tester diagnostics: every line starts with `media-diag:` (LSPosed log, logcat tag `AMPP-media`, and `media-diag.log` in the plugin data directory) and covers host build/profile at startup, each capability's ACTIVE/UNSUPPORTED/FAILED result, a startup self-check listing every unresolved contract, and any failing hook member at runtime.

v1.3.3 / 15 修复专辑详情页标题带「E」（Explicit）标识时下载按钮消失：标题行把标识与标题放在同一文本节点（内联占位符／附加「E」），旧逻辑要求文本与专辑名严格相等，匹配失败后按钮被隐藏。现改为容忍标题后仅跟随 Explicit／Clean 标识的文本（精确匹配优先），按钮定位到包含标识的行尾；原生 TextView 标题若以复合图标显示标识也会避开。适用于 Apple Music 1606/1607/1609/1611（共用同一实现），并新增单元测试。

v1.3.3 / 15 fixes the album-page download button disappearing when the title carries the "E" (explicit) badge: the title row puts the badge in the same text node (inline placeholder / trailing "E"), while the old lookup required the text to equal the album name exactly, so no anchor was found and the button was hidden. Matching now tolerates a title followed only by an Explicit/Clean badge (exact matches still win) and places the button after the end of the line including the badge; a native TextView title whose badge is a compound drawable is also cleared. Applies to Apple Music 1606/1607/1609/1611 (shared code); unit tests added.

v1.3.2 / 14 新增专辑详情页「专辑封面（静态 JPEG，原始最大尺寸）」下载，与动态封面分开，适用于 Apple Music 1606/1607/1609/1611；另修复在较新 AM++（含 Apple Music 1611 适配构建）上打开播放页「更多」菜单时的崩溃 `NoSuchMethodError: drawBackdrop-QPzRGKA$default`：AM++ 的 `drawBackdrop` 签名（编译器改写的方法名与参数）已变化（新增 `minLayerSize`），旧版插件按旧签名直接调用。现通过反射解析宿主实际签名（兼容旧 14 参数与新 15 参数），并在不可用或失败时退回为普通半透明圆角面板，不再让宿主崩溃。「更多」菜单、音质弹窗、删除确认/睡眠定时弹窗均已改用该兼容层；校验脚本禁止直接引用该改写方法名。同时修复弹窗回归：兼容层的 Kotlin `$default` 掩码曾把扩展接收者计入位序，导致高光与着色表面丢失（磨砂变透明）；现按声明参数位序计算并加单元测试。音质弹窗、删除确认等弹窗改用与 AM++ 底栏一致的液态玻璃材质（vibrancy + 4dp 模糊、24dp 透镜、0xFF121212 40% 着色面、宿主默认高光描边，无蓝色边框）；删除确认弹窗文字为白色，主要按钮=白色胶囊（「从资料库删除」为红字），次要=半透明玻璃胶囊。新增「弹窗液态玻璃样式」下拉设置（同底栏 / 同更多菜单 / 无磨砂（早期效果），默认同底栏）；「无磨砂」补回与更多菜单相同的中性白渐变描边（仍无着色面、不启用宿主默认高光）；「同底栏」裁剪到 32dp 圆角避免方角描边；睡眠定时／删除确认／音质弹窗统一实时取样背景（与更多菜单相同的 live PixelCopy）。

v1.3.2 / 14 adds a separate "Album cover (still JPEG, full resolution)" download on the album page (distinct from the animated cover; Apple Music 1606/1607/1609/1611) and fixes a crash when opening the player More menu on newer AM++ builds (including the Apple Music 1611 build): `NoSuchMethodError: drawBackdrop-QPzRGKA\$default`. AM++'s `drawBackdrop` signature (compiler-mangled name and parameters) changed (`minLayerSize` was added) while the plugin called the old one directly. The plugin now resolves the host's real signature reflectively (older 14-parameter and newer 15-parameter forms) and falls back to a plain translucent rounded panel if unavailable or failing, so the host cannot crash. The More menu, audio-quality card and delete/sleep-timer dialogs all use this layer; the verifier forbids direct references to the mangled method. Also fixes a popup regression: the compat layer's Kotlin `$default` mask counted the extension receiver as a bit, dropping the highlight and tint surface (frosted → transparent); it now indexes declared parameters, with unit tests. The audio-quality card, delete confirmation and similar popups now use the same liquid glass as the AM++ bottom bar (vibrancy + 4dp blur, 24dp lens, 0xFF121212 @ 40% tint, host-default highlight rim, no blue rim); delete-dialog text is white, primary = white pill ("从资料库删除" in red), secondary = translucent glass pill. Adds a "Popup Liquid Glass style" dropdown (Same as bottom bar / Same as More menu / No frost (early look); default bottom bar); "No frost" restores the same neutral white gradient rim as the More menu (still no surface tint; host-default highlight stays off); "Same as bottom bar" clips to 32dp rounded corners (no square rim peek); sleep timer / delete confirmation / audio quality popups all use live PixelCopy backdrop sampling like the More menu.

v1.3.1 / 13 修复 Apple Music 1611 的歌曲／歌词卡片图片分享与保存：1611 把分享进度页的 LiveData 观察者由 `commerce.activities.l` 改为 `commerce.activities.m`，旧 profile 沿用了 1609 的类名，导致 hook 未命中、原生流程接管（与商店地区无关）。1606/1607/1609 不变。同时加强 APK 校验：除符号存在外，还检查分享进度页确实实例化该观察者类，观察者持有／转换进度页与结果封装类型，且结构候选唯一（1606 的类型化字段与 1607+ 的 check-cast 均接受）。新增 DexKit 辅助的后台结构检查（试点，仅卡片分享）：JSON profile 优先；profile 观察者通过反射校验即立即安装全部 hook（含分享按钮），DexKit 仅在后台核对，确认「profile 观察者并非由进度页实例化」且候选唯一时才缓存（按 Apple Music `versionCode`+`lastUpdateTime`，SharedPreferences）并于下次启动生效；DexKit 或原生库不可用只记录 `card_sharing:` 日志，不影响按钮。仅当 profile 观察者本身不可用且 DexKit 无法唯一解析时才不安装。插件包只含 DexKit Java API，`libdexkit.so` 从 AM++ 模块加载。

v1.3.1 / 13 fixes image sharing/saving of song and lyrics cards on Apple Music 1611: 1611 renamed the share-progress LiveData observer from `commerce.activities.l` to `commerce.activities.m`, but the profile kept the 1609 name, so the hook never matched. 1606/1607/1609 are unchanged. The APK verifier is stricter: besides symbol existence it checks that the progress fragment instantiates the observer, the observer holds/casts the progress and result types, and the structural candidate is unique. Adds a DexKit-assisted background structure check (pilot, card sharing only): JSON profile first; if the profile observer passes reflection checks, all hooks (including the share buttons) install immediately, and DexKit only verifies in the background — a confirmed unique mismatch is cached (Apple Music `versionCode`+`lastUpdateTime`, SharedPreferences) and applied on the next launch. DexKit/native problems only log `card_sharing:` and never affect the buttons. Hooks are skipped only if the profile observer itself is unusable and DexKit cannot resolve a unique one. The plugin ZIP ships the DexKit Java API only; `libdexkit.so` loads from the AM++ module.

v1.3.0 / 12 同时支持 Apple Music 7.0.0-beta / 1606、1607、1609、1611：`assets/profiles.json` 列出全部 profile，插件启动时按宿主的包名 + 版本名 + 版本号精确选择 `applemusic-<版本号>.json`（不做范围或最近版本回退）；新增一个宿主版本只需增加一个 profile JSON 及索引条目，并运行 `scripts/verify-all-profiles.py`。音质弹窗的玻璃现与「更多」菜单一样持续实时取样背景。1611 的宿主 profile（`applemusic-1611.json`，类名、方法名和字段名按 1609 → 1611 重新映射；「更多」菜单点击方法的参数顺序由 `(int, View, item)` 变为 `(item, View, int)`，插件按参数类型传参）。以下为 1.2.0 / 11（1609）适配说明：重新适配 Apple Music 7.0.0-beta / 1609（宿主 profile `applemusic-1611.json`）；移除了平板播放界面的全部修改（平板双栏/紧凑控件/歌词收起等），并移除对封面尺寸设置方法 `fragment.l.E1(boolean, Size)`（1609 中为 `fragment.m`）的 hook，以消除与其他注册的冲突。同时在欧加真（ColorOS / realme UI / OxygenOS）系统上自动禁用播放页音量条：按 `Build.MANUFACTURER`/`Build.BRAND` 及系统属性识别，即使已保存为开启也不安装，设置开关显示为关闭且不可切换。此版本还新增「艺术家特色字标」下载：读取原生 `editorialArtwork["musicContentColorLogoTrimmed"]`（1609 `EditorialImageType.ARTIST_LOGO`），按原始尺寸请求透明 PNG 并保存到 `Download/AM++`，仅在艺术家有字标时显示；CI 改为从 `media-plugin/plugin.json` 读取版本号校验 ZIP。

以下为 `1.1.0 / 10` 的历史说明：音量条适应平板、横竖屏及不同 DPI；平板使用较小的歌曲信息和紧凑的底部控件，双栏按 iPad 风格排列，歌词可收起后居中。下方区域按播放时完整封面宽度对齐，暂停时保持宽度，轻微拖动和回弹后保留布局。手机切页保留三个原生动作按钮。静态封面尺寸跟随原生容器，旋转和后台返回时修复失效的背景缓存；保留原生播放／暂停缩放。更多玻璃菜单固定在窗口右上安全区。详情页提供内容下载，兼容动态视频时间轴和 Apple HLS 扩展属性；支持特殊 Logo 字标艺人的下载入口。构建及设备验证范围见 [验证记录](VALIDATION.md) 和 [布局验证](LAYOUT_VALIDATION.md)。

玻璃开关的中英文标题和说明：

| 语言 | 标题 | 下方说明 |
| --- | --- | --- |
| 中文 | 播放页界面启用液态玻璃 | 为睡眠定时、删除确认弹窗、更多菜单和音质弹窗启用液态玻璃效果。 |
| 中文 | 弹窗液态玻璃样式（下拉：同底栏 / 同更多菜单 / 无磨砂（早期效果）） | 作用于睡眠定时、删除确认和音质弹窗；更多菜单保持自身效果。保存后重开 Apple Music 生效。 |
| English | Enable Liquid Glass on the player screen | Apply Liquid Glass to the sleep timer, deletion confirmation dialog, More menu, and audio quality dialog. |
| English | Popup Liquid Glass style (dropdown) | Applies to the sleep timer, deletion confirmation and audio quality popups (the More menu keeps its own look): **Same as bottom bar** (default; AM++ bottom-bar material: vibrancy + 4dp blur, lens, 0xFF121212 @ 40% tint, host-default highlight; clipped to 32dp rounded corners), **Same as More menu** (the player More menu's neutral gray glass: 20dp blur, 0xFF3A3A3C @ 36%, thin white gradient rim), **No frost (early look)** (vibrancy + 20dp blur + lens, no surface tint; the same neutral white gradient rim as the More menu, not the host-default highlight). All neutral gray/white, never blue. Reopen Apple Music after saving. |

系统没有提供有效圆角时沿用 AM 原样式。平板按钮排列只在实际存在双栏空间时生效，单栏保留原生动作位置。翻译和人声控制仍使用原生按钮。

内部10进一步缩小平板播放按钮图形，保留触摸范围；双栏歌词可点击收起，封面和播放控件整列居中，再点重新展开。平板下面的区域按播放时完整封面宽度对齐，暂停时保持宽度，音量条两端的 Flamingo 喇叭图标也收在该范围内；切页临时画面保留原生封面和队列胶囊的圆角，音质标签采用更小的尺寸和适当留白。手机切页保留三个原生动作按钮。音量条的空间预留只使用完成原生布局的实际尺寸，避免高 DPI 下的错误预测量和切页后残留的约束。特殊 Logo 字标艺人页的下载入口位于原生顶部栏的安全空位。验证范围见 [布局修正](LAYOUT_VALIDATION.md)。

动态封面使用当前详情页的原生资源地址，保存为真实 MP4。支持直链 MP4 和有限、无加密、单视频轨的 fMP4 HLS（包括同一 MP4 的连续字节段），兼容 Apple 播放列表的 `_AVG-BANDWIDTH` 扩展字段。分片视频在保存前无损重新封装为普通 MP4，补全时长、帧索引并将播放起点归零，改善系统相册播放兼容性；遇到其他结构会显示明确失败原因。

当前玻璃功能复用已安装 AM++ 提供的 Compose/Backdrop 运行库和资源。纯嵌入模式的玻璃资源访问尚未单独验收。

## 独立构建

需要 JDK 17+、Android SDK Platform 37.0 和 Build Tools 37.0.0。配置 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir`，然后执行：

```powershell
.\gradlew.bat :media-plugin:testDebugUnitTest :media-plugin:lintDebug :media-plugin:pluginZip
python -B scripts/verify-sdk.py
python -B scripts/verify-media-plugin.py media-plugin/build/dist/ampp-media-integrations-1.3.1.zip
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
