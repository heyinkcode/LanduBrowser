# 下载、安装与更新

[项目首页](../README.md) · [安卓使用指南](ANDROID.md) · [测试范围](TESTING.md)

## Android 登录与翻译修复预览

- [Android ARM64 APK](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-android.20261104.1/LanduBrowser-Android-arm64-latest.apk)
- [对应应用源码 ZIP](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-android.20261104.1/LanduBrowser-1.0.26-android-20261104-1-source.zip)
- [SHA-256 校验清单](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-android.20261104.1/SHA256SUMS.txt)
- [发布说明](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261104.1)

修复添加账号闪退，提供 Google 网页登录和手机本地原页翻译。点击“翻译”在原页面显示中文，首次使用通过手机网络下载语言模型。原生 Chromium 翻译在存在可用服务配置时保留。更新前保存编辑、关闭环境，再同签名覆盖安装。当前为开发签名预览包。

## Windows 预览：v1.0.26-preview.20261103

本预览包由本地构建并上传 GitHub Releases，没有使用 Actions 编译或 Artifact 保存安装包。Android 15/16 已完成指定 UA-CH / RAF 真机检查；Windows 候选包含自动 UA 完整版本和目录同步修复，完整安装升级与 UI 验收尚未完成。

| 文件 | 用途 |
| --- | --- |
| [Windows x64 完整版 EXE · 约 1.23 GB](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-preview.20261103/LanduBrowser-Windows-x86_64-with-kernel.exe) | 包含独立内核及字体资源的本地候选安装程序。 |
| [Windows x64 轻量版 EXE · 约 173 MB](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-preview.20261103/LanduBrowser-Windows-x86_64-without-kernel.exe) | 不包含独立内核，需要自行配置兼容 Chromium。 |
| [对应应用源码 ZIP](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-preview.20261103/LanduBrowser-1.0.26-preview-20261103-source.zip) | 桌面、Chromium Android 覆盖源码、构建脚本、许可和逐文件清单，不含内核、个人数据和原始设备日志。 |
| [SHA-256 校验清单](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-preview.20261103/SHA256SUMS.txt) | 逐文件核对下载内容。 |

源码也可在 [`source` 分支](https://github.com/heyinkcode/LanduBrowser/tree/source) 阅读。`main` 是官网；新预览标签指向应用源码，旧 v1.0.26 标签的 GitHub 自动源码 ZIP 则是当时的官网树。开发者优先使用明确命名的应用源码 ZIP 或检出 `source`。

## 原 Windows 稳定版

[v1.0.26 稳定版](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26) 继续保留。新预览中的 Windows 应用内部版本仍为 1.0.26，按预览标签、文件校验值和说明区分，不把同版本号视为同一二进制。

## 核对与安装

下载同一发布页的安装文件和校验清单，计算 SHA-256 并逐行比对：

```powershell
Get-FileHash -Algorithm SHA256 .\LanduBrowser-Android-arm64-latest.apk
# Windows 安装包同样替换为对应文件名计算
```

Windows 10/11 x64 用户运行 EXE；完整内置版体积较大，轻量版需另备兼容内核。普通用户无需安装 Node.js。Windows 默认数据目录兼容 `%APPDATA%\openbrowser`，自定义路径以客户端为准。

安卓用户从文件管理器打开 APK，按系统提示授权当前安装来源。升级前保存编辑并正常关闭当前环境，再使用同签名包保留数据安装；不要卸载旧应用来解决签名冲突。详细步骤见 [安卓指南](ANDROID.md)。

Windows 包尚无代码签名，Android 当前为开发签名预览包。请核对来源、校验值与系统提示；不要关闭系统安全防护。SHA-256 核对文件一致性，不代替安全审计。当前按整包更新，没有增量补丁更新。

## 平台与费用

Windows 提供原稳定版及新候选；Android ARM64 提供完整内核预览；macOS/Linux 尚未发布本次岚渡包；iOS 没有客户端。软件免费，用户自行选择的网络、代理或 VPN 可能另外计费。

基于 [OpenBrowser](https://github.com/sheying2013/OpenBrowser) 二次开发，感谢原作者及贡献者。可选 **[岚渡云（推广）](https://app.landucloud.com)** 与软件免费使用不绑定。
