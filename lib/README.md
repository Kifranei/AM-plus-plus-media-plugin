# 编译用宿主参考

三个 JAR 固定于 AM++ 插件系统 SDK v1 的 `75dde33e51c6c724c4697d25f5c7788b3e7a8099`。它们在插件中为 compileOnly，在单元测试中提供运行接口，不作为 D8 程序输入。

| 文件 | 内容 | 对应源码 |
| --- | --- | --- |
| ampp-plugin-api-v1.jar | 公开插件 SDK v1 | [plugin-api](https://github.com/Zennmn/AM-plus-plus/tree/75dde33e51c6c724c4697d25f5c7788b3e7a8099/plugin-api) |
| ampp-backdrop-host.jar | Backdrop 公共渲染接口 | [backdrop](https://github.com/Zennmn/AM-plus-plus/tree/75dde33e51c6c724c4697d25f5c7788b3e7a8099/backdrop) |
| ampp-liquid-controls-host.jar | LiquidButton 等公共组件 | [glass catalog](https://github.com/Zennmn/AM-plus-plus/tree/75dde33e51c6c724c4697d25f5c7788b3e7a8099/glass/src/main/kotlin/com/kyant/backdrop/catalog) |

SDK 和 AM++ 的修改适用项目 GPL-3.0；原始玻璃参考的 Apache-2.0 声明见 [第三方说明](../THIRD_PARTY_NOTICES.md)。组件参考 JAR 保留 Kotlin 模块索引，类定义仅包含 `com.kyant.backdrop.catalog`。

`sdk-manifest.json` 记录每个 JAR 的 SHA-256 和允许的类前缀。执行 `python -B scripts/verify-sdk.py` 检查固定参考；ZIP 校验同时拒绝重复 SDK、玻璃和 Compose 类定义。

从对应 AM++ 源码导出时，执行：

```text
./gradlew :plugin-api:exportSdk :backdrop:bundleAndroidMainClassesToCompileJar :glass:bundleLibCompileToJarRelease
```

SDK 来源于 `plugin-api/build/sdk/ampp-plugin-api-v1.jar`；Backdrop 来源于 `backdrop/build/intermediates/compile_library_classes_jar/androidMain/bundleAndroidMainClassesToCompileJar/classes.jar`。组件来源于 `glass/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar`，仅导出 `com/kyant/backdrop/catalog/` 下的类和 Kotlin 模块索引。
