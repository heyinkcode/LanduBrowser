# 岚渡浏览器 v1.0.27

本次为 Windows x64 更新，修复认证代理检测误报，并完善软件内更新提示。软件免费，基于 OpenBrowser 和 Chromium 二次开发。

## 本次改动

- 修复海外认证 SOCKS5 等健康线路被2.2秒检测时限提前取消的问题，单次检测最多8秒，总检测预算24秒，成功后立即返回。
- 保留用户自定义检测时限、账号密码认证、多源查询及连接失败时阻止启动的行为。
- 电脑版按 GitHub 指定的正式最新版检查更新，避免安卓预览发布干扰电脑版版本和安装包选择。
- 启动后自动检查更新，持续运行时每6小时复查。发现可下载的新版本时显示“查看更新 / 稍后”，同一运行期间同一版本只提示一次。
- “查看更新”进入本地设置的更新卡片，用户可下载新版安装包并运行安装。

## 验证范围

- 已从当前桌面环境编辑页点击代理检测并显示成功，保存后回读出口、语言和时区。
- 三次真实认证代理检测成功，约4.1秒返回。启动实际独立 Chromium 后，HTTPS 查询出口与代理检测结果一致，网页语言和时区与检测结果对应。
- 五组代理回归通过，覆盖延迟响应、认证、缓存、并发去重和失败时禁止直连。
- 更新检查、备用查询、预览标签过滤及提示去重回归通过，版本比较21项检查通过。
- 安装包按整包发布。本次执行文件内容、归档完整性和安装程序解包核验，自动覆盖安装验收范围见测试记录。

## 下载和安装

[Windows 完整版 EXE](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.27/LanduBrowser-Windows-x86_64-with-kernel.exe) · [Windows 轻量版 EXE](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.27/LanduBrowser-Windows-x86_64-without-kernel.exe) · [对应源码](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.27/LanduBrowser-1.0.27-source.zip) · [SHA-256](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.27/SHA256SUMS.txt)

安装前保存编辑并正常关闭运行环境。Windows 10/11 x64 可运行 EXE，也可完整解压 ZIP 后打开 START.cmd。完整版自带独立内核，轻量版需要下载或自行配置兼容内核。

现有 v1.0.26 客户端联网启动或点击左下角版本区域后，可发现本次 v1.0.27 更新，并显示红色更新标记。新版增加主动提示窗口。关闭应用期间不会即时弹出通知。

Android 最新预览仍使用[侧边翻译按钮版本](https://github.com/heyinkcode/LanduBrowser/releases/tag/v1.0.26-android.20261104.2)，本次 Windows 版本号与 Android Chromium 内核版本分别管理。

## 原作者与许可

基于 [sheying2013/OpenBrowser](https://github.com/sheying2013/OpenBrowser) 和 Chromium 二次开发，感谢原作者及贡献者。软件免费，保留对应许可证及第三方署名。

需要独立网络服务可以了解[岚渡云（推广）](https://app.landucloud.com)。服务可选、费用另计，不影响软件免费使用。
