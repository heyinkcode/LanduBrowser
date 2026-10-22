# 下载、安装与更新

[返回项目首页](../README.md) · [使用指南](GETTING_STARTED.md) · [所有版本](https://github.com/heyinkcode/LanduBrowser/releases)

## Windows 64 位 v1.0.26

建议使用 Windows 10/11 x64。本次发布仅构建并在 Windows 上进行了本地验证，没有 macOS、Linux、Android 或 iOS 安装包。

| 安装包 | 选择建议 |
| --- | --- |
| [完整版 EXE · 约 1.23 GB](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26/LanduBrowser-Windows-x86_64-with-kernel.exe) | 推荐首次使用。内置独立内核和字体资源，下载较大，但不需要自行准备内核。 |
| [轻量版 EXE · 约 173 MB](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26/LanduBrowser-Windows-x86_64-without-kernel.exe) | 不带独立内核。需要另外下载或在「本地设置」指定兼容 Chromium；不同内核能力不完全相同。 |

这两种都是安装程序，普通用户无需 Node.js。不要把名称中含 `source` 的源码 ZIP 当成安装程序。

## 安装

1. 下载所需 EXE 和同一版本的 [SHA256SUMS.txt](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26/SHA256SUMS.txt)。
2. 在 PowerShell 中计算文件校验值，与清单对应行比较：

   ```powershell
   Get-FileHash -Algorithm SHA256 "$HOME\Downloads\LanduBrowser-Windows-x86_64-with-kernel.exe"
   ```

3. 双击 EXE，按安装向导操作。默认安装到当前用户的 `%LOCALAPPDATA%\Programs\LanduBrowser`。
4. 从桌面或开始菜单的「岚渡浏览器」快捷方式打开。
5. 第一次使用默认是岚渡简约主题；可创建独立环境再启动。已有配置会按原数据目录继续读取。

**本版未进行代码签名。** Windows 可能显示未知发布者或信誉提示。请先确认来源和校验值；校验不一致或出现恶意软件警告时停止操作并反馈，不要关闭杀毒软件。SHA-256 用于核对文件一致性，不代替代码签名或安全审计。

## 更新和卸载

- 更新前关闭浏览器环境和管理程序，备份实际数据目录后安装新版本。
- 为兼容原有 OpenBrowser 环境，默认数据目录仍为 `%APPDATA%\openbrowser`；若已自定义路径，以客户端实际显示为准。
- 不要将旧版 OpenBrowser 和岚渡同时指向同一份运行中的数据。
- 当前发布完整安装包，**未实现增量更新**。较大的完整版包含内核和字体，不只是界面代码。
- 在 Windows「已安装的应用」中卸载岚渡浏览器。卸载程序不主动删除上述环境数据目录；如需彻底清理，请先自行确认备份与账号资料。

## 开发者源码

下载 [LanduBrowser-1.0.26-source.zip](https://github.com/heyinkcode/LanduBrowser/releases/download/v1.0.26/LanduBrowser-1.0.26-source.zip)，阅读其中的 `BUILD_LANDU.md` 和 `README_LANDU.md`。包中有逐文件 SHA-256 清单，不含内核、依赖或用户 Profile。GitHub 自动附带的 `Source code (zip)` 仅对应本仓库官网分支。

## 免费与项目来源

岚渡浏览器基于 [OpenBrowser](https://github.com/sheying2013/OpenBrowser) 二次开发，感谢原作者与贡献者。软件免费，不要求购买 VPN 或其他服务。可选的 [岚渡云（推广）](https://app.landucloud.com) 属于独立网络服务，费用另计。

遇到问题请提交 [Issue](https://github.com/heyinkcode/LanduBrowser/issues)，附系统版本、安装包名称和脱敏截图；不要附密码、Cookie、代理凭据或完整环境数据。
