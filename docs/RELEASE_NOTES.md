# v1.0.26-preview.20261103 更新说明

本地构建的公开预览，同步 Windows 候选、完整 Chromium Android APK 和对应应用源码。软件免费，基于 OpenBrowser 二次开发，保留原作者及第三方署名。

## 本次内容

- Android 新增 UA/UA-CH 自动与自定义完整字段，默认跟随真实内核 `156.0.8078.48`，覆盖网页与 HTTP Client Hints。
- RAF 独立设置设备默认或 1–240 FPS 上限，不伪造时间戳；修改后保存并重启环境。
- 小米 Android 15/16 的自定义/自动、Window/Worker 30/60 FPS 两组各 23 项运行检查通过；原环境配置回读保持不变。
- 安卓已接入协议独立选择、无需手填前缀的代理地址、账号密码分栏、系统 VPN 跟随与区域策略；手机认证代理错误密码失败分支仍待完整验收。
- Windows 自动 UA 完整版本对齐实际内核，保留手动配置；修复目录同步异常时的句柄关闭。32 项配置检查、7 项实际 Windows 内核运行检查及包内源码核验通过。
- 公开完整应用源码分支、构建指南、原作者说明、安装指南、测试边界和 SHA-256。

## 使用前了解

这是预览版。Windows 新候选没有完成本次安装升级和完整 UI 验收；Android 未完成全部表单、后台、所有指纹/代理分支、所有机型和所有网站验收。原 Windows v1.0.26 稳定版继续保留。

安卓为开发签名包，仅 ARM64，约 531 MB；内置完整内核，不是早期 WebView 原型。更新前先保存并关闭环境，同签名覆盖安装，不要先卸载原应用。

Windows 两种包内部应用版本仍是 1.0.26。以本预览标签、文件名和 SHA-256 区分本次候选。完整版约 1.23 GB，轻量版约 173 MB；没有增量更新。

## 下载与源码

[预览版下载](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-preview.20261103) · [使用与安装](DOWNLOADS.md) · [测试记录](TESTING.md) · [应用源码](https://github.com/heyinkcode/LanduBrowser/tree/source)

文件均由本地成品上传 Releases，不使用 GitHub Actions 编译或 Artifact 保留大包。源码不含个人数据、凭据、私钥、浏览器内核二进制或原始设备报告。

## 致谢与推广

感谢 [OpenBrowser](https://github.com/sheying2013/OpenBrowser) 原作者 sheying2013 和贡献者，以及 Chromium 项目。软件免费，可选 **[岚渡云（推广）](https://app.landucloud.com)** 是维护者独立推荐，不代表上游背书，不与免费使用绑定。各第三方组件保留自己的许可和源码说明。
