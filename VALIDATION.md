# 验证记录

日期：2026-10-05。插件版本：`1.0.0`；适配 Apple Music `7.0.0-beta / 1606`。

## 功能验收

用户已确认插件版四项功能测试通过：词幕集成、使用 iOS 媒体控制按钮、解除歌词分享限制、液态玻璃音质弹窗。发布包来自已验收版本，独立工程重建结果与其逐字节一致。

纯嵌入模式的玻璃资源访问尚未单独验收。

## 独立工程验证

- 插件编译和 D8 ZIP 构建通过，不依赖本地 AM++ 工程目录。
- 22 项插件单元测试通过，零失败、零错误、零跳过；覆盖歌词空格、原生解析、副行选择、歌曲状态、设置存储和 SDK 作用域。
- Lint 通过：零错误、21 项警告，主要为依赖版本、宿主资源反射、文本和 KTX 建议。
- 三个固定编译参考 JAR 的 SHA-256 与公共类范围校验通过。
- 真实 ZIP 校验通过：4296 个 DEX 类定义，四项功能及素材齐全，无重复 SDK、宿主、libxposed、Compose 或玻璃参考库类定义；DEX 哈希和校验和有效。
- 自带 Apple Music profile 和玻璃接口共 330 项检查通过。
- 插件源码、测试与素材和原 `AM-plus-plus` 插件分支的 `401af26` 一致；`classes.dex`、manifest、素材及整个 ZIP 与已验收包一致。

原 AM++ 测试分支另通过 1055 项回归测试和 PluginStore 的真实 ZIP 导入、默认关闭、启用、更新保留配置、删除与启动清理测试。

## 发布包校验

```text
文件: ampp-media-integrations-1.0.0.zip
大小: 2087788 bytes
SHA-256: 75cd4349cf2b041a0a9a3789121c782b1afd8eeab8aab4d288f8399694997444
classes.dex SHA-256: fad8f514e676aa17b6dbb2bd7c8a0f13cf734eddb32cf0c2550ff04fb34fff59
```

构建及校验命令见 [README](README.md)。GitHub Actions 运行插件单元测试、Lint、SDK 与 ZIP 校验，并上传构建产物。
