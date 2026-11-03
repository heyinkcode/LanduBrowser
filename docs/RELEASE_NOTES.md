# Android 登录与翻译修复预览

本地构建的 Android ARM64 更新包，内核为 `156.0.8078.48`。软件免费，基于 OpenBrowser 和 Chromium 二次开发，保留原作者与第三方署名。

## 更新内容

- 修复“向设备添加账号”触发未处理异常、浏览器退出的问题。
- 添加账号入口改为明确的 Google 网页登录确认，在当前环境内打开登录网页。
- 点击“翻译”直接把当前页面可显示的文字译为简体中文，保留原网址及表单行为，提供恢复原文操作。
- 接入 Google ML Kit 端侧模型，首次通过手机网络下载语言模型，之后在手机本地处理正文。
- 原生 Chromium 翻译在服务配置可用时保留，当前安装包通过本地原页模式使用翻译。
- 当前处理点击时主文档的文字，输入值、可编辑区、代码块和禁止翻译区会跳过。图片文字、内嵌框架、Shadow DOM 与后续新增内容需要另外处理。

## 验证范围

三个改动 Java 模块的编译、依赖与 Error Prone 检查，以及 9 项覆盖补丁回归检查通过。Android 15 真机同签名覆盖更新后，核对本地模型原页翻译和表单行为。APK 完整性、签名、既有内核库哈希及新增翻译库均已核对。

有效服务配置下的原生翻译尚未完成本次运行验收。浏览器账号同步尚未接入。

## 下载与使用

[APK 与源码](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261104.1) · [安装指南](ANDROID.md) · [测试记录](TESTING.md) · [应用源码](https://github.com/heyinkcode/LanduBrowser/tree/source)

安装前保存编辑并关闭环境，采用同签名覆盖安装。校验下载内容时使用随包的 `SHA256SUMS.txt`。

[先前桌面与 Android 预览记录](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-preview.20261103)
