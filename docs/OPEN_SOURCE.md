# 开源说明与原作者致谢

[返回项目首页](../README.md) · [使用指南](GETTING_STARTED.md)

## 项目从哪里来

岚渡浏览器（Landu Browser）以 **[sheying2013/OpenBrowser](https://github.com/sheying2013/OpenBrowser)** 为基础进行二次开发，不是从零编写的浏览器内核，也不是原作者的官方发行版。

原项目提供了独立 Chromium 环境、代理、指纹参数、扩展管理、窗口同步、RPA、本地 API 与 MCP 等核心功能。岚渡在保留原有业务逻辑的前提下，主要调整品牌、简约主题、控件排版、高分辨率显示和中文使用体验。

感谢 **[sheying2013](https://github.com/sheying2013)** 和所有 OpenBrowser 贡献者。上游的代码、设计与维护工作，是本项目能够继续开发的基础。

## 三种角色，不要混淆

| 名称 | 角色 |
| --- | --- |
| OpenBrowser | 原始开源项目；原作者及其贡献者不因此为岚渡的改动或推广服务背书。 |
| Landu Browser / 岚渡浏览器 | 基于原项目继续调整的免费本地浏览器环境管理工具。 |
| 岚渡云 | 本项目文档推荐的独立网络服务，非客户端必需组件，费用及条款由该服务另行说明。 |

## 免费的范围

岚渡浏览器软件免费使用，不设置付费激活或订阅门槛。本说明不代表代理、VPN、服务器、第三方扩展或目标网站服务也免费。

“免费使用”是岚渡的软件提供方式；MIT 许可证本身也允许符合条件的商业使用，两者不是同一个概念。

## MIT 许可证与署名

本次建站依据的原项目源码中，`LICENSE` 为 MIT，署名为：

> Copyright (c) 2026 OpenBrowser contributors

本仓库保留 [完整许可证](../LICENSE)。使用、修改或分发相关代码及其重要部分时，应按许可证保留版权与许可声明。官网代码也按 MIT 提供。Chromium、第三方依赖、扩展和服务仍遵循各自的许可证与使用条款。

## 当前公开内容

`main` 分支公开官网静态源码、Logo、使用示例截图和说明文档。[v1.0.26 Releases](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26) 提供 Windows x64 安装程序，以及明确命名的 `LanduBrowser-1.0.26-source.zip` 对应应用源码包。

该源码包包含岚渡修改后的主进程、界面、自动化模块、原生 C# 组件、构建脚本、许可证及 `BUILD_LANDU.md`；上游参考提交为 `0b57e39595b02ff9662da3d807b413f3393ce166`。不包含个人环境、依赖目录及浏览器内核二进制。它不是 GitHub 自动生成的官网 `Source code (zip)`。

## 第三方组件并非统一 MIT

- Windows 后台输入组件 `native-input-mirror.cs` 包含基于 [chrome-power-app](https://github.com/zmzimpl/chrome-power-app) 的实现，来源许可为 AGPL-3.0。发行版同时提供 C# 源码、构建脚本、`LICENSE.chrome-power.txt` 与 `THIRD-PARTY-NOTICES.md`。
- 桌面运行时的许可和 Chromium 通告分别保留于安装目录的 `runtime/LICENSE` 和 `runtime/LICENSES.chromium.html`。
- 独立浏览器内核、字体和其他资源适用各自许可；应用源码包不包含这些第三方二进制的完整源代码，也不将其宣称为岚渡原创。

修改或再分发时，请分别检查各组件许可与源码提供要求，不要仅凭根目录 MIT 文件覆盖第三方许可。

## 使用边界

软件按许可证“现状”提供。环境隔离、代理与指纹配置不保证完全匿名、账号安全、检测通过率或特定网站兼容性。请在具有权限的账号和系统中使用，遵守适用规则，妥善保管本地环境及账号资料。

## 本次 Android 与源码同步

应用完整源码现公开在 [`source` 分支](https://github.com/heyinkcode/LanduBrowser/tree/source)；[Android 修复预览标签](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261104.1) 指向应用源码，main 继续维护官网。安装包、对应源码 ZIP 和校验清单由本地构建后上传 Releases。

Android 以 Chromium 固定提交 `d0c02bfad08ec92a6ffcf989e53cd995e6381a4c`（156.0.8078.48）为底座，公开 `AndroidNative/` 的管理、原生实现、覆盖生成与构建脚本；不宣称 Chromium 内核为岚渡原创。Chromium 原许可证随资源和 APK 提供，固定版本及源文件哈希见源码版本锁。原项目桌面参考提交为 `0b57e39595b02ff9662da3d807b413f3393ce166`。

原始开发记录、个人环境、密钥、真实代理与设备日志不属于公开源码。公开验证仅保留 [脱敏汇总](TESTING.md)；无法从当前两台手机的局部结果推断所有功能或第三方网站兼容性。

## 端侧翻译依赖

手机本地翻译使用 Google ML Kit Translate 17.0.3，依赖文件由固定官方 URL 与 SHA-256 锁定。源码包保留下载脚本、依赖锁和第三方许可，SDK 文件通过构建前下载获得。翻译结果标明 Google Translate，相关条款见 [ML Kit](https://developers.google.com/ml-kit/language/translation/translation-terms)及 [Google Translate 署名要求](https://cloud.google.com/translate/attribution)。
