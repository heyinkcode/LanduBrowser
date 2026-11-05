<div align="center">

<img src="Browserapp/assets/logo-landu.svg" alt="岚渡浏览器 Logo" width="88">

# Landu Browser｜岚渡浏览器

**基于原作，免费使用，让每个环境有自己的空间。**

[官网](https://heyinkcode.github.io/LanduBrowser/) · [下载](docs/DOWNLOADS.md) · [安卓指南](docs/ANDROID.md) · [应用源码](https://github.com/heyinkcode/LanduBrowser/tree/source) · [原作者与许可](docs/OPEN_SOURCE.md)

</div>

岚渡浏览器基于 **[sheying2013/OpenBrowser](https://github.com/sheying2013/OpenBrowser)** 二次开发。桌面端保留原项目的环境管理与业务逻辑，改进岚渡简约主题、排版和高分辨率体验；Android 端在固定 Chromium 源码上接入本地环境管理与原生指纹配置。感谢原作者 sheying2013、OpenBrowser 与 Chromium 的所有贡献者。

**软件免费，不设付费激活或订阅门槛。** 安卓在手机本地运行，使用用户自己的网络，不需要岚渡账号、自建服务器、云手机或流量中转。

## 下载与支持平台

| 平台 | 当前状态 |
| --- | --- |
| Windows 10/11 x64 | [v1.0.28](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.28) 提供完整版和轻量版，修复认证代理检测误报，支持联网自动检查和新版提示。验证范围见测试记录。 |
| Android ARM64 | [随机身份与稳定性修复预览](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-android.20261105.2/LanduBrowser-Android-arm64-latest.apk)，约548 MB；发行版本 `1.0.26.20261105.2`，增加随机 UA 与安卓型号，修复指定 Adreno GPU 崩溃。真机范围见测试记录。 |
| macOS / Linux | 保留上游源码路径，暂未发布本次岚渡版本安装包或完成平台验收。 |
| iOS | 暂无客户端；不提供云手机替代本地客户端。 |

安装包、对应源码和 SHA-256 见 [下载指南](docs/DOWNLOADS.md)，真机范围见 [测试记录](docs/TESTING.md)。Android 当前修复预览见 [发布页](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261105.2)。

## 可以做什么

- 桌面端：沿用原项目的独立 Chromium Profile、分组、标签、代理、扩展、窗口同步、RPA、本地 API/MCP 等业务能力，使用岚渡简约主题。
- 安卓端：本地环境列表、创建/编辑/启停、独立数据路径、分组/标签、配置导入导出、代理认证、区域策略和已接入的原生指纹选项；与桌面尚未完全对齐。
- UA-CH：自动跟随真实内核，或自定义完整版本、平台、型号和品牌等字段；网页及请求头使用同一份配置。
- RAF：独立选择跟随设备或 1–240 FPS 上限，保留真实回调时间戳；负载会影响实际帧率。
- 网络：系统 VPN 或用户自备 HTTP/HTTPS/SOCKS5 代理。选择固定语言或时区不会自动改变公网出口。

环境隔离不等于完全匿名。自定义身份、字体名称过滤不保证通过检测、改变 Emoji 渲染或避免账号限制；完整平台范围与未完成部分写在文档中。

## 两个分支

- [`main`](https://github.com/heyinkcode/LanduBrowser/tree/main)：官网静态页面、用户指南和公开测试汇总；根目录 `npm start` 仅预览官网。
- [`source`](https://github.com/heyinkcode/LanduBrowser/tree/source)：桌面应用及 Chromium Android 覆盖源码，含构建说明与许可证；进入 `Browserapp` 才是桌面开发启动。

大安装包放 Releases，不提交到 Git；个人环境、密码、Cookie、密钥、缓存和原始设备日志不上传。当前安卓配置备份不兼容桌面 JSON，也不是完整浏览数据备份。

## 文档

- [下载与更新](docs/DOWNLOADS.md)
- [桌面入门](docs/GETTING_STARTED.md)
- [Android 本地内核版](docs/ANDROID.md)
- [测试结果与剩余范围](docs/TESTING.md)
- [本次更新说明](docs/RELEASE_NOTES.md)
- [更新日志](docs/CHANGELOG.md)
- [多端范围与后续计划](docs/MULTIPLATFORM_PLAN.md)
- [原作者致谢与第三方许可](docs/OPEN_SOURCE.md)
- [官网维护](docs/WEBSITE.md)

## 推荐网络服务（推广）

需要独立的 VPN / 网络服务，可以了解 **[岚渡云 · app.landucloud.com](https://app.landucloud.com)**。这是维护者的推广推荐，不代表 OpenBrowser 原作者背书；服务可选、费用另计，**不购买也能免费使用岚渡浏览器**。详见 [推荐服务说明](docs/RECOMMENDED_SERVICES.md)。

## 反馈与开源

在 [Issues](https://github.com/heyinkcode/LanduBrowser/issues) 提交系统、版本、复现步骤和脱敏截图；不要上传密码、Cookie、代理凭据或完整环境目录。

保留 OpenBrowser 的 MIT 根许可证与署名。Windows 后台输入组件含 chrome-power 的 AGPL-3.0 来源实现，随包提供对应 C# 源码与许可；Chromium、字体和其他组件分别适用其许可，不将所有组件统称为 MIT。详见 [开源说明](docs/OPEN_SOURCE.md)。

## 从应用源码启动

```bash
git clone --branch source https://github.com/heyinkcode/LanduBrowser.git
cd LanduBrowser/Browserapp
npm ci --include=dev
npm start
```

Node.js 与兼容独立内核需自行准备。开发启动会使用实际本地数据目录，测试请另用隔离目录。Windows 构建见 [BUILD_LANDU.md](BUILD_LANDU.md)，Android 覆盖、版本锁和 Linux 构建见 [AndroidNative/README.md](AndroidNative/README.md)。

`Androidapp/` 是历史 WebView 原型，不是当前完整内核 APK 的源码入口。原始上游说明保留于 [README_UPSTREAM.md](README_UPSTREAM.md) 和 [README_UPSTREAM_CN.md](README_UPSTREAM_CN.md)。
