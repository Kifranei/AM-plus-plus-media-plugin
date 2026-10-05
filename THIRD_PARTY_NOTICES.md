# 第三方来源与许可证

- 插件实现与目标适配资料来自 [AM++](https://github.com/Zennmn/AM-plus-plus)，本插件代码按 GPL-3.0 发布。
- SDK v1 和宿主玻璃公共接口来自 AM++ 的 `75dde33e51c6c724c4697d25f5c7788b3e7a8099`，仅供编译与测试使用；对应源码见 [lib/README.md](lib/README.md)。
- 玻璃渲染参考来自 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)，原始参考固定于 `65ab177e90e5c1d8c62e70cf7755841982da65f6`；使用 AM++ 对应版本的公共渲染接口。Apache-2.0 声明在 `media-plugin/src/main/assets/licenses/AndroidLiquidGlass.txt`。
- 词幕接口使用 [LyricProvider](https://github.com/tomakino/LyricProvider) 的 `io.github.proify.lyricon:provider:0.1.70`。Apache-2.0 声明在 `media-plugin/src/main/assets/licenses/LyricProvider.txt`。
- iOS 媒体控制按钮的图标素材由 Kifranei 提供。

插件打包任务将项目 GPL-3.0 和上述第三方许可证一并放入 ZIP 的 `assets/licenses/`。
