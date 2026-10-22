<div align="center">

<img src="assets/logo.svg" alt="岚渡浏览器 Logo" width="96">

# Landu Browser｜岚渡浏览器

**让每个浏览器环境，各有自己的空间。**

基于 [OpenBrowser](https://github.com/sheying2013/OpenBrowser) 二次开发的免费桌面浏览器环境管理工具。

[访问官网](https://kissow.github.io/LanduBrowser/) · [使用指南](docs/GETTING_STARTED.md) · [开源与致谢](docs/OPEN_SOURCE.md) · [版本发布](https://github.com/kissow/LanduBrowser/releases)

</div>

---

## 先说清楚：基于原作，免费使用

岚渡浏览器不是从零开发的浏览器内核。项目以 **OpenBrowser 原项目源码为底座**，保留原有的环境管理与业务逻辑，围绕中文使用体验、岚渡简约主题、界面排版和高分辨率适配等方面进行调整。

感谢原作者 **[sheying2013](https://github.com/sheying2013)** 及所有 OpenBrowser 贡献者。环境隔离、代理配置、指纹设置、自动化等核心能力来自原项目的工作，我们尊重并保留上游署名和许可证。

**岚渡浏览器软件免费使用，不以订阅或付费激活作为使用门槛。** 自行选择的代理、VPN、服务器等第三方服务费用另计，不包含在软件中，也不是使用软件的必选条件。

> **当前仓库范围**：本仓库目前发布岚渡浏览器的官网、品牌资源和说明文档，尚未上传完整的岚渡桌面端源码或安装包。桌面端发布以 [Releases](https://github.com/kissow/LanduBrowser/releases) 的实际内容为准；现在需要研究核心实现，可查看 [上游 OpenBrowser 源码](https://github.com/sheying2013/OpenBrowser)。不要将本仓库克隆后当作桌面客户端启动。

## 界面预览

![岚渡浏览器环境管理界面](assets/workspace.png)

*岚渡简约主题，使用示例环境数据展示，不包含真实用户账号。*

## 能做什么

| 功能 | 说明 |
| --- | --- |
| 独立浏览器环境 | 为不同工作空间使用独立 Chromium Profile，分别管理 Cookie、缓存与本地存储。 |
| 环境管理 | 使用分组、标签、批量操作管理多个环境，减少重复配置。 |
| 代理与区域配置 | 为环境配置 HTTP / HTTPS / SOCKS 代理、语言、时区等参数。代理服务需自行准备。 |
| 指纹参数 | 沿用原项目的 User-Agent、Canvas、WebGL、WebRTC 等配置能力。 |
| 应用与扩展 | 管理浏览器扩展，按工作场景组织使用。 |
| 自动化工具 | 沿用原项目的窗口同步、RPA、本地 API 与 MCP 接入能力。 |
| 岚渡简约主题 | 蓝白配色、统一控件、可调整表格列宽与更清晰的界面层级。 |

环境隔离用于管理工作空间，**不代表完全匿名，也不保证通过任何第三方检测、避免账号限制或适配所有网站**。具体能力与兼容性以实际版本为准。

## 客户端与获取方式

| 平台 | 当前说明 |
| --- | --- |
| Windows | 岚渡版本当前的主要本地适配与验证平台；安装包尚未在本仓库发布。 |
| macOS / Ubuntu | 上游提供支持与打包路径；本仓库暂未提供岚渡版本安装包或完成独立验证。 |
| Android / iOS | 本仓库不提供手机客户端，也不提供手机远程管理端。 |

1. 先阅读 [使用指南](docs/GETTING_STARTED.md)。
2. 在 [Releases](https://github.com/kissow/LanduBrowser/releases) 查看是否已有适合设备的正式安装包；没有发布资产时，请等待发布，不使用来历不明的下载链接。
3. 需要从源码了解原项目，可查阅 [OpenBrowser 中文说明](https://github.com/sheying2013/OpenBrowser/blob/main/README_CN.md)。

## 文档导航

- [使用指南](docs/GETTING_STARTED.md)：环境、代理、扩展、自动化、数据和常见问题。
- [开源说明与原作者致谢](docs/OPEN_SOURCE.md)：项目关系、MIT 许可证、免费范围与发布边界。
- [推荐网络服务（推广）](docs/RECOMMENDED_SERVICES.md)：岚渡云的入口及服务说明。
- [官网开发与维护](docs/WEBSITE.md)：本地预览、目录结构、GitHub Pages 发布与维护检查。

## 推荐网络服务（推广）

**需要单独的 VPN / 网络服务，可以了解 [岚渡云](https://app.landucloud.com)。**

入口：**[app.landucloud.com](https://app.landucloud.com)**

这是本项目维护者的推广推荐，并非 OpenBrowser 原作者的背书。岚渡云是可选的独立服务，套餐、价格与适用条件以服务页面为准；**不购买也不影响岚渡浏览器免费使用**。VPN 客户端与浏览器代理不是同一种配置方式，如何搭配请查看 [推荐服务说明](docs/RECOMMENDED_SERVICES.md)。

## 本地预览官网

需要 Node.js 18 或更新版本，无需安装依赖：

```bash
git clone https://github.com/kissow/LanduBrowser.git
cd LanduBrowser
npm start
```

然后打开 `http://127.0.0.1:4173`。这只启动官网预览，**不启动桌面浏览器**。

## 反馈与贡献

欢迎在 [Issues](https://github.com/kissow/LanduBrowser/issues) 提交排版、文档和使用问题。请描述系统、版本、复现步骤，并附上脱敏截图；不要上传账号密码、代理凭据、Cookie、验证码或完整环境数据。

## 许可证

上游 OpenBrowser 使用 MIT 许可证。本仓库保留其 [LICENSE](LICENSE) 与贡献者署名，官网代码亦按 MIT 提供。第三方组件、Chromium、扩展及外部服务仍适用各自的条款。

**再次感谢 [OpenBrowser](https://github.com/sheying2013/OpenBrowser) 原作者及贡献者。岚渡基于原作继续改进，而非取代原作的贡献。**
