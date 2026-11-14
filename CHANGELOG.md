# 更新日志 / Changelog

最新版本在前。Newest first.

## v1.3.6 / 18

**v1.3.6 (18)** — 正式版：配合 AM++ 1.6.9 的全屏插件设置页，按 Miuix 官方设置页标准重做分类卡片、开关、保存按钮和「弹窗液态玻璃样式」下拉；修复 1614 上「词幕集成」被旧 1613 条件隐藏；同步上游最新歌词投递能力（魅族 Ticker、媒体通知歌词、SuperLyric、Lyric Getter）；自动过渡说明补充需关闭 Apple Music 交叉渐入渐出。（HXJ）
Official release: works with the full-screen plugin settings page in AM++ 1.6.9 and rebuilds it with Miuix's standard settings components (grouped cards, switches, save button and the popup-glass dropdown); restores the Lyricon switch on 1614 which an old 1613-only condition hid; syncs the latest upstream lyric delivery options (Meizu Ticker, media notification, SuperLyric, Lyric Getter); adds the note that Apple Music Crossfade must be off for Auto-Transition. (HXJ)

- 插件设置页改为全屏页面，不再使用二级弹窗 / Plugin settings is a full-screen page instead of a secondary popup.
- 设置页按 Miuix 官方设置示例重做：`Scaffold`、`Card`、`SmallTitle`、`SwitchPreference`、`Button` 均使用默认主题样式 / Rebuilt following Miuix's official settings example; Scaffold, Card, SmallTitle, SwitchPreference and Button use defaults.
- 「弹窗液态玻璃样式」改用 Miuix 官方 `OverlayDropdownPreference`，删除自定义 Popup 定位和阴影 / Uses Miuix's official OverlayDropdownPreference; custom Popup positioning and shadow removed.
- 设置页背景改为 `MiuixTheme.colorScheme.surface`，与 AM++ 设置页一致 / Background now uses MiuixTheme.colorScheme.surface, matching AM++ settings.
- 1614 上「词幕集成」恢复显示，不再被 `hostVersionCode < 1613` 条件隐藏 / Lyricon is visible again on 1614, no longer hidden by the hostVersionCode < 1613 condition.
- 新增「状态栏歌词（魅族 Ticker）」「媒体通知歌词」「SuperLyric 投递」「Lyric Getter 投递」设置项，跟随上游 1.3.5 / Adds the Meizu Ticker, media notification, SuperLyric and Lyric Getter settings from upstream 1.3.5.
- AutoMix 自动过渡、变速匹配和切歌封面过渡保持 / AutoMix transition, beat match and cover transition are retained.
- 自动过渡说明补充：「开启自动过渡需关闭 Apple Music 交叉渐入渐出功能，否则无法生效」 / Adds the note that Auto-Transition requires Apple Music Crossfade to be off.
- 插件设置页大标题上滑折叠、返回按钮走 Miuix 页面退出动画 / Collapsing plugin-settings title on scroll and Miuix page-exit animation for the back button.
- 播放页音量条改用原生播放进度条取色（`player_seekbar_color` / `player_progress_path_color`），保留原生 alpha，音量填充与轨道可见 / Player volume bar now uses the native seek-bar colors and alpha.
- 队列页自动过渡按钮改用相邻原生按钮的实时激活/未激活颜色，移除固定叠加色 / AutoMix queue button uses the live native active/inactive colors, with no hardcoded tint overlay.

更新日志 / Changelog: [CHANGELOG.md](https://github.com/Kifranei/AM-plus-plus-media-plugin/blob/v1.3.6/CHANGELOG.md)

## v1.3.5 / 17

- 移除桌面歌词（逐字）悬浮窗及设置开关；状态栏歌词（魅族 Ticker）、媒体通知歌词、SuperLyric、Lyric Getter 保留。
  Removes the desktop/floating word-by-word lyrics overlay and its setting; status-bar (Meizu Ticker), media-notification, SuperLyric and Lyric Getter delivery remain.
- 自动过渡（AI DJ / AutoMix）：恢复封面溶解与进度条光效，修复歌词页标志残留导致跳过封面溶解；音频过渡不变（HXJ）。
  AutoMix: restores the cover dissolve and progress glow, fixing a stuck lyrics-page flag that skipped the dissolve; audio transitions unchanged (HXJ).
- 项目介绍更新为 Apple Music 7.0.0 / 1614（HXJ）。 / Project intro updated for Apple Music 7.0.0 / 1614 (HXJ).

## v1.3.5-Pre / 17（预发布 / Pre-release）

新增五个系统歌词投递选项，复用词幕的 Apple Music 歌词与时间轴捕获，**全部默认关闭**，适配 Apple Music 1606–1614。
Adds five system lyric delivery options that reuse the Lyricon lyric/timing capture; **all off by default**; Apple Music 1606–1614.

- 状态栏歌词（魅族 Ticker）：通过 Flyme Ticker 通知在魅族状态栏显示当前歌词 — **待测试** / Status-bar lyrics (Meizu Ticker) — **Pending testing**
- 桌面歌词（逐字）：系统悬浮窗显示当前歌词，有逐字时间轴时高亮到字；需为 Apple Music 开启「显示在其他应用上层」 — **待测试** / Desktop lyrics (word-by-word overlay; requires "Display over other apps" for Apple Music) — **Pending testing**
- 媒体通知歌词：将当前歌词写入媒体通知标题，供蓝牙设备、车机或系统媒体中心显示 — **待测试** / Media notification lyrics — **Pending testing**
- SuperLyric 投递：向 SuperLyric 模块发送当前歌词（含逐字，若可用），未安装时跳过 — **待测试** / SuperLyric delivery — **Pending testing**
- Lyric Getter 投递：向 Lyric Getter 发送当前歌词，未安装时跳过 — **待测试** / Lyric Getter delivery — **Pending testing**

## v1.3.4 / 16

v1.3.4 / 16 适配 Apple Music 7.0.0-beta / 1613，并修复 1613 上词幕状态栏歌词无法启动（1613 的歌词指针字段由 `g1` 改为 `f1`，旧名称仍存在但类型为 int）；平板横屏双栏播放页不再显示重复的把手（宽度 ≥ 600dp 时由宿主/AM++ 提供把手）。profile 校验脚本现按精确类型／签名检查词幕、音质对话框和卡片分享的命名成员。新增 Apple Music 7.0.0-beta / 1613 profile `applemusic-1613.json`，由 1611 逐项按 1613 DEX 重新映射（39 个类改名（含 a7.d），如 `player.y`→`x`、`fragment.m`→`l`、`fragment.e`→`d`、`androidx.fragment.app.l`→`n`；字段普遍前移一位，如 `PlayerMainFragment.b0`→`a0`、`BottomSheetBehavior.o0`→`n0`；卡片分享观察者 `commerce.activities.m`→`a7.d`；「更多」菜单点击方法改回 `z(int, View, item)`），并用 1613 APK 校验全部符号与资源名。Hook 改为严格失败开放：回调在暂存副本上运行，抛出异常时宿主调用保持原样、不再让 AM++ 停用整个插件，同一 Hook 失败 3 次后仅注销该 Hook。新增测试诊断：所有诊断行以 `media-diag:` 开头，写入 LSPosed 日志、logcat 标签 `AMPP-media` 及插件数据目录 `media-diag.log`，包括启动时的宿主版本／profile、每个功能的 ACTIVE／UNSUPPORTED／FAILED、启动自检列出的全部无法解析的合约（类.方法(参数) 或 类.字段:类型），以及运行中失败的 Hook 成员。测试者提交日志时请过滤 `media-diag:`。

v1.3.4 / 16 adapts to Apple Music 7.0.0-beta / 1613 and fixes Lyricon status-bar lyrics not starting on 1613 (the lyrics pointer field moved from `g1` to `f1`; the old name still exists as an int); the two-pane tablet-landscape player no longer shows a duplicate drag handle (at ≥ 600dp the host/AM++ provides it). The profile verifier now checks exact types/signatures for the Lyricon, audio-quality dialog and card-sharing named members. Adds the Apple Music 7.0.0-beta / 1613 profile `applemusic-1613.json`, remapped from 1611 against the 1613 DEX (39 class renames, most fields shifted by one letter, card-sharing observer `commerce.activities.m` → `a7.d`, More-menu click back to `z(int, View, item)`), with all symbols and resource names verified against the 1613 APK. Hooks are now strictly fail-open: callbacks run on a staged copy of the call, a throwing callback leaves the host call untouched and no longer makes AM++ stop the whole plugin, and a hook failing 3 times unregisters only itself. Tester diagnostics: every line starts with `media-diag:` (LSPosed log, logcat tag `AMPP-media`, and `media-diag.log` in the plugin data directory) and covers host build/profile at startup, each capability's ACTIVE/UNSUPPORTED/FAILED result, a startup self-check listing every unresolved contract, and any failing hook member at runtime.

v1.3.4 / 16 同时新增 Apple Music 7.0.0 / 1614 profile `applemusic-1614.json`（1614 的 versionName 为 `7.0.0`，不再带 `-beta`；profile 仍按包名 + 版本名 + 版本号精确匹配）。由 1613 逐项按 1614 DEX 重新核对：引用的类均未改名，仅 `utils.Q0$a`→`R0$a`（CJK 判定）；RecyclerView.Adapter 覆写方法整体后移一位：`player.x` 的 `V`→`W`（CJK 逐字动画）、`k`→`l`、`m`→`n`、`n0`→`o0`，「更多」菜单适配器 `Z6.d.m`→`n`、`Z6.b.l`→`m`，`ea.a` 的 `w`→`x`、`k`→`l`；其余字段、方法与资源名不变，并用 1614 APK 校验全部符号与资源名。

v1.3.4 / 16 also adds the Apple Music 7.0.0 / 1614 profile `applemusic-1614.json` (1614 reports versionName `7.0.0` without `-beta`; selection is still exact on package + version name + version code). Re-resolved from 1613 against the 1614 DEX: no referenced class was renamed except `utils.Q0$a` → `R0$a` (CJK predicate); RecyclerView.Adapter overrides shifted by one letter: `player.x` `V`→`W` (CJK karaoke animation), `k`→`l`, `m`→`n`, `n0`→`o0`; More-menu adapter `Z6.d.m`→`n`, `Z6.b.l`→`m`; `ea.a` `w`→`x`, `k`→`l`. All other fields, methods and resource names are unchanged, and every symbol and resource name is verified against the 1614 APK.

## v1.3.3 / 15

v1.3.3 / 15 修复专辑详情页标题带「E」（Explicit）标识时下载按钮消失：标题行把标识与标题放在同一文本节点（内联占位符／附加「E」），旧逻辑要求文本与专辑名严格相等，匹配失败后按钮被隐藏。现改为容忍标题后仅跟随 Explicit／Clean 标识的文本（精确匹配优先），按钮定位到包含标识的行尾；原生 TextView 标题若以复合图标显示标识也会避开。适用于 Apple Music 1606/1607/1609/1611（共用同一实现），并新增单元测试。

v1.3.3 / 15 fixes the album-page download button disappearing when the title carries the "E" (explicit) badge: the title row puts the badge in the same text node (inline placeholder / trailing "E"), while the old lookup required the text to equal the album name exactly, so no anchor was found and the button was hidden. Matching now tolerates a title followed only by an Explicit/Clean badge (exact matches still win) and places the button after the end of the line including the badge; a native TextView title whose badge is a compound drawable is also cleared. Applies to Apple Music 1606/1607/1609/1611 (shared code); unit tests added.

## v1.3.2 / 14

v1.3.2 / 14 新增专辑详情页「专辑封面（静态 JPEG，原始最大尺寸）」下载，与动态封面分开，适用于 Apple Music 1606/1607/1609/1611；另修复在较新 AM++（含 Apple Music 1611 适配构建）上打开播放页「更多」菜单时的崩溃 `NoSuchMethodError: drawBackdrop-QPzRGKA$default`：AM++ 的 `drawBackdrop` 签名（编译器改写的方法名与参数）已变化（新增 `minLayerSize`），旧版插件按旧签名直接调用。现通过反射解析宿主实际签名（兼容旧 14 参数与新 15 参数），并在不可用或失败时退回为普通半透明圆角面板，不再让宿主崩溃。「更多」菜单、音质弹窗、删除确认/睡眠定时弹窗均已改用该兼容层；校验脚本禁止直接引用该改写方法名。同时修复弹窗回归：兼容层的 Kotlin `$default` 掩码曾把扩展接收者计入位序，导致高光与着色表面丢失（磨砂变透明）；现按声明参数位序计算并加单元测试。音质弹窗、删除确认等弹窗改用与 AM++ 底栏一致的液态玻璃材质（vibrancy + 4dp 模糊、24dp 透镜、0xFF121212 40% 着色面、宿主默认高光描边，无蓝色边框）；删除确认弹窗文字为白色，主要按钮=白色胶囊（「从资料库删除」为红字），次要=半透明玻璃胶囊。新增「弹窗液态玻璃样式」下拉设置（同底栏 / 同更多菜单 / 无磨砂（早期效果），默认同底栏）；「无磨砂」补回与更多菜单相同的中性白渐变描边（仍无着色面、不启用宿主默认高光）；「同底栏」裁剪到 32dp 圆角避免方角描边；睡眠定时／删除确认／音质弹窗统一实时取样背景（与更多菜单相同的 live PixelCopy）。

v1.3.2 / 14 adds a separate "Album cover (still JPEG, full resolution)" download on the album page (distinct from the animated cover; Apple Music 1606/1607/1609/1611) and fixes a crash when opening the player More menu on newer AM++ builds (including the Apple Music 1611 build): `NoSuchMethodError: drawBackdrop-QPzRGKA\$default`. AM++'s `drawBackdrop` signature (compiler-mangled name and parameters) changed (`minLayerSize` was added) while the plugin called the old one directly. The plugin now resolves the host's real signature reflectively (older 14-parameter and newer 15-parameter forms) and falls back to a plain translucent rounded panel if unavailable or failing, so the host cannot crash. The More menu, audio-quality card and delete/sleep-timer dialogs all use this layer; the verifier forbids direct references to the mangled method. Also fixes a popup regression: the compat layer's Kotlin `$default` mask counted the extension receiver as a bit, dropping the highlight and tint surface (frosted → transparent); it now indexes declared parameters, with unit tests. The audio-quality card, delete confirmation and similar popups now use the same liquid glass as the AM++ bottom bar (vibrancy + 4dp blur, 24dp lens, 0xFF121212 @ 40% tint, host-default highlight rim, no blue rim); delete-dialog text is white, primary = white pill ("从资料库删除" in red), secondary = translucent glass pill. Adds a "Popup Liquid Glass style" dropdown (Same as bottom bar / Same as More menu / No frost (early look); default bottom bar); "No frost" restores the same neutral white gradient rim as the More menu (still no surface tint; host-default highlight stays off); "Same as bottom bar" clips to 32dp rounded corners (no square rim peek); sleep timer / delete confirmation / audio quality popups all use live PixelCopy backdrop sampling like the More menu.

## v1.3.1 / 13

v1.3.1 / 13 修复 Apple Music 1611 的歌曲／歌词卡片图片分享与保存：1611 把分享进度页的 LiveData 观察者由 `commerce.activities.l` 改为 `commerce.activities.m`，旧 profile 沿用了 1609 的类名，导致 hook 未命中、原生流程接管（与商店地区无关）。1606/1607/1609 不变。同时加强 APK 校验：除符号存在外，还检查分享进度页确实实例化该观察者类，观察者持有／转换进度页与结果封装类型，且结构候选唯一（1606 的类型化字段与 1607+ 的 check-cast 均接受）。新增 DexKit 辅助的后台结构检查（试点，仅卡片分享）：JSON profile 优先；profile 观察者通过反射校验即立即安装全部 hook（含分享按钮），DexKit 仅在后台核对，确认「profile 观察者并非由进度页实例化」且候选唯一时才缓存（按 Apple Music `versionCode`+`lastUpdateTime`，SharedPreferences）并于下次启动生效；DexKit 或原生库不可用只记录 `card_sharing:` 日志，不影响按钮。仅当 profile 观察者本身不可用且 DexKit 无法唯一解析时才不安装。插件包只含 DexKit Java API，`libdexkit.so` 从 AM++ 模块加载。

v1.3.1 / 13 fixes image sharing/saving of song and lyrics cards on Apple Music 1611: 1611 renamed the share-progress LiveData observer from `commerce.activities.l` to `commerce.activities.m`, but the profile kept the 1609 name, so the hook never matched. 1606/1607/1609 are unchanged. The APK verifier is stricter: besides symbol existence it checks that the progress fragment instantiates the observer, the observer holds/casts the progress and result types, and the structural candidate is unique. Adds a DexKit-assisted background structure check (pilot, card sharing only): JSON profile first; if the profile observer passes reflection checks, all hooks (including the share buttons) install immediately, and DexKit only verifies in the background — a confirmed unique mismatch is cached (Apple Music `versionCode`+`lastUpdateTime`, SharedPreferences) and applied on the next launch. DexKit/native problems only log `card_sharing:` and never affect the buttons. Hooks are skipped only if the profile observer itself is unusable and DexKit cannot resolve a unique one. The plugin ZIP ships the DexKit Java API only; `libdexkit.so` loads from the AM++ module.

## v1.3.0 / 12（含 1.2.0 / 11）

v1.3.0 / 12 同时支持 Apple Music 7.0.0-beta / 1606、1607、1609、1611：`assets/profiles.json` 列出全部 profile，插件启动时按宿主的包名 + 版本名 + 版本号精确选择 `applemusic-<版本号>.json`（不做范围或最近版本回退）；新增一个宿主版本只需增加一个 profile JSON 及索引条目，并运行 `scripts/verify-all-profiles.py`。音质弹窗的玻璃现与「更多」菜单一样持续实时取样背景。1611 的宿主 profile（`applemusic-1611.json`，类名、方法名和字段名按 1609 → 1611 重新映射；「更多」菜单点击方法的参数顺序由 `(int, View, item)` 变为 `(item, View, int)`，插件按参数类型传参）。以下为 1.2.0 / 11（1609）适配说明：重新适配 Apple Music 7.0.0-beta / 1609（宿主 profile `applemusic-1611.json`）；移除了平板播放界面的全部修改（平板双栏/紧凑控件/歌词收起等），并移除对封面尺寸设置方法 `fragment.l.E1(boolean, Size)`（1609 中为 `fragment.m`）的 hook，以消除与其他注册的冲突。同时在欧加真（ColorOS / realme UI / OxygenOS）系统上自动禁用播放页音量条：按 `Build.MANUFACTURER`/`Build.BRAND` 及系统属性识别，即使已保存为开启也不安装，设置开关显示为关闭且不可切换。此版本还新增「艺术家特色字标」下载：读取原生 `editorialArtwork["musicContentColorLogoTrimmed"]`（1609 `EditorialImageType.ARTIST_LOGO`），按原始尺寸请求透明 PNG 并保存到 `Download/AM++`，仅在艺术家有字标时显示；CI 改为从 `media-plugin/plugin.json` 读取版本号校验 ZIP。

## v1.1.0 / 10

v1.1.0 将四个玻璃功能合并为「播放页界面启用液态玻璃」。升级时，旧版任一玻璃开关已开启则迁移为开启；明确保存的新开关值优先。把手与系统圆角仍各有独立开关，旧版合并的播放页外观设置会迁移到两项。此版本同时修复媒体按钮冷启动和蓝牙连接后隐藏、全屏封面把手、玻璃动画切角、菜单背景跟手及关闭崩溃，并加入蓝牙设备名称与图标、播放页音量条。

以下为 `1.1.0 / 10` 的历史说明：音量条适应平板、横竖屏及不同 DPI；平板使用较小的歌曲信息和紧凑的底部控件，双栏按 iPad 风格排列，歌词可收起后居中。下方区域按播放时完整封面宽度对齐，暂停时保持宽度，轻微拖动和回弹后保留布局。手机切页保留三个原生动作按钮。静态封面尺寸跟随原生容器，旋转和后台返回时修复失效的背景缓存；保留原生播放／暂停缩放。更多玻璃菜单固定在窗口右上安全区。详情页提供内容下载，兼容动态视频时间轴和 Apple HLS 扩展属性；支持特殊 Logo 字标艺人的下载入口。构建及设备验证范围见 [验证记录](VALIDATION.md) 和 [布局验证](LAYOUT_VALIDATION.md)。

内部10进一步缩小平板播放按钮图形，保留触摸范围；双栏歌词可点击收起，封面和播放控件整列居中，再点重新展开。平板下面的区域按播放时完整封面宽度对齐，暂停时保持宽度，音量条两端的 Flamingo 喇叭图标也收在该范围内；切页临时画面保留原生封面和队列胶囊的圆角，音质标签采用更小的尺寸和适当留白。手机切页保留三个原生动作按钮。音量条的空间预留只使用完成原生布局的实际尺寸，避免高 DPI 下的错误预测量和切页后残留的约束。特殊 Logo 字标艺人页的下载入口位于原生顶部栏的安全空位。验证范围见 [布局修正](LAYOUT_VALIDATION.md)。
