# 下载、安装与更新

[项目首页](../README.md) · [Android 指南](ANDROID.md) · [测试范围](TESTING.md)

## Windows v1.0.28

| 文件 | 用途 |
| --- | --- |
| [Windows x64 完整版 EXE](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.28/LanduBrowser-Windows-x86_64-with-kernel.exe) | 推荐版本，包含独立 Chromium 内核和字体资源。 |
| [Windows x64 完整版 ZIP](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.28/LanduBrowser-Windows-x86_64-with-kernel.zip) | 完整解压后运行 START.cmd 或 runtime/LanduBrowser.exe。 |
| [Windows x64 轻量版 EXE](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.28/LanduBrowser-Windows-x86_64-without-kernel.exe) | 需要下载或自行配置兼容内核。 |
| [Windows x64 轻量版 ZIP](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.28/LanduBrowser-Windows-x86_64-without-kernel.zip) | 轻量便携目录。 |
| [对应应用源码 ZIP](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.28/LanduBrowser-1.0.28-source.zip) | Windows 应用与 Android 覆盖源码、构建脚本和许可。 |
| [SHA-256 清单](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.28/SHA256SUMS.txt) | 核对下载文件是否完整。 |
| [发布说明](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.28) | 本次修复与验证范围。 |

本次修复更新详情文案和下载按钮显隐，包含此前认证代理检测修复。Windows 10/11 x64 普通用户无需 Node.js。安装前保存编辑并关闭所有环境，再运行安装程序。既有默认数据目录兼容 %APPDATA%/openbrowser，自定义数据路径以客户端为准。

## 软件内更新

客户端联网启动后自动检查 GitHub 最新正式版，持续运行时每6小时复查，也可在“本地设置 → 软件更新”手动检查。建议 v1.0.26 用户先通过本页安装 Windows v1.0.28；旧版检查可能被安卓预览发布影响。v1.0.27 起增加“查看更新 / 稍后”提示窗口。

更新按完整安装包下载，完成后运行安装程序。关闭应用期间不会即时收到通知。GitHub 网络不可达时，可访问[官网](https://heyinkcode.github.io/LanduBrowser/)或[发布列表](https://github.com/heyinkcode/LanduBrowser/releases)手动下载。

## Android 最新预览

- [Android ARM64 APK](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-android.20261106.1/LanduBrowser-Android-arm64-latest.apk)
- [对应安卓源码 ZIP](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26-android.20261106.1/LanduBrowser-1.0.26-android-20261106-1-source.zip)
- [安卓发布说明和校验清单](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261106.1)

发行版本 `1.0.26.20261106.1`，Android 版本码 `807804807`，APK约518 MB，真实内核仍为 `156.0.8078.48`。本次优化 V8 执行配置，包含既有的选区与 Adreno GPU 修复，保留 Google 网页登录、本地原页翻译及侧边状态按钮。首次翻译通过手机网络下载语言模型。当前为开发签名预览包，更新前保存编辑并关闭环境，再同签名覆盖安装。真机范围见[测试记录](TESTING.md)。

Android 当前通过本页或官网手动下载 APK 覆盖更新，未宣称具有与 Windows 相同的自动更新提示。

## 源码与校验

应用源码在[source 分支](https://github.com/heyinkcode/LanduBrowser/tree/source)，main 分支维护官网。下载文件的 SHA-256 应与同一发布页的 SHA256SUMS.txt 一致。安装程序和便携目录按平台分发，完整解压 ZIP 后运行。

Windows 当前无代码签名，Android 为开发签名预览包，安装时按系统提示核对来源。macOS/Linux 尚未发布本次岚渡安装包，iOS 暂无客户端。

基于[OpenBrowser](https://github.com/sheying2013/OpenBrowser)二次开发，感谢原作者及贡献者。软件免费。[岚渡云（推广）](https://app.landucloud.com)是可选的独立网络服务，费用另计。
