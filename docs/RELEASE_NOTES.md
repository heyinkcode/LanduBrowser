# Android 侧边翻译按钮预览

`v1.0.26-android.20261104.2` 为本地构建的 Android ARM64 预览包，约548 MB，内核为 `156.0.8078.48`。

## 本次更新

- 原页翻译状态收为44dp侧边“译”按钮。
- 点击按钮展开翻译状态、Google Translate 署名和“原文”操作。
- 展开面板5秒后自动收起，并提供拖动与贴边交互。
- 导航、切换环境及页面销毁时清理翻译控件。

## 验证范围

- Java 编译、依赖与 Error Prone 检查通过，10项源码回归检查通过。
- APK 签名、ZIP 完整性及16 KB对齐检查通过，9个既有 ARM64 原生库逐项哈希一致。
- Redmi Note 12 Turbo / Android 15 已完成同签名保留数据更新，设备安装包哈希与分发文件一致。
- 真机确认本地模型译文、原网址、输入值及按钮元素身份，侧边按钮显示与展开已确认，展开后已观察到收起状态。

拖动贴边和展开后“原文”操作的完整真机验收待完成。Android 16 上的新控件验收待完成，先前 UA-CH 和 RAF 结果见测试记录。

## 下载与安装

[APK、对应源码与校验清单](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261104.2) · [安卓指南](ANDROID.md) · [测试记录](TESTING.md)

安装前保存编辑并关闭环境，使用同签名 APK 覆盖安装。核对随包的 `SHA256SUMS.txt`。

APK SHA-256 为 `0eadc4024156492d354d1c63b0d58259a28eab129e3c258d39a67df5378646e2`。

## 原作者与许可

岚渡浏览器基于 [sheying2013/OpenBrowser](https://github.com/sheying2013/OpenBrowser) 和 Chromium 二次开发，软件免费使用。感谢原作者及贡献者，源码保留相应许可证和第三方署名。

[前一版原页翻译预览](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261104.1)
