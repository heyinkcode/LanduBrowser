# 源码与本地构建

应用代码位于 [`source` 分支](https://github.com/heyinkcode/LanduBrowser/tree/source)；`main` 为官网。当前公开预览为 v1.0.26-preview.20261103。桌面基于 OpenBrowser 提交 `0b57e39595b02ff9662da3d807b413f3393ce166`；Android 固定 Chromium `156.0.8078.48` / `d0c02bfad08ec92a6ffcf989e53cd995e6381a4c`。

## 目录

- `Browserapp/`：桌面界面、业务逻辑、自动化、C# 辅助组件及构建脚本。
- `AndroidNative/`：完整 Chromium Android 覆盖源码、品牌资源、版本锁、原生/Java 检查。
- `Androidapp/`：历史系统 WebView 原型，当前 APK 不由它构建。
- `shared/`：本地环境契约参考；桌面与安卓文件格式尚不互通。
- `SOURCE_MANIFEST.sha256`：应用源码 ZIP 的逐文件清单。

源码不包含依赖目录、浏览器内核二进制、用户 Profile、账号数据、密钥或原始调试日志。构建签名密钥需自行准备；换签名的 APK 不能作为已安装版本的保留数据更新包。

## Windows

准备 Node.js 22+、npm、Windows .NET Framework C# 编译器和 NSIS 3。在 `Browserapp` 执行：

```powershell
npm ci --include=dev
npm start
# 本地构建轻量候选，避免覆盖其他产物
$env:OPENBROWSER_PACKAGE_OUTPUT_SUBDIR = 'my-candidate'
$env:OPENBROWSER_PACKAGE_VARIANT = 'without-kernel'
npm run package:portable
```

输出位于 `Browserapp/dist/my-candidate/`。NSIS 缺失时确认 EXE 是否实际生成；ZIP 不能冒充 EXE。完整版须先在 `Browserapp/kernels/windows-x64/` 准备兼容内核及其许可，再将变体设为 `with-kernel`。内核不从本源码仓库下载。

开发启动默认兼容 `%APPDATA%\openbrowser`，不要把它当作隔离测试。尊重已有数据、自定义目录和备份；大包按整包分发，没有增量更新。

## Android

参见 [AndroidNative/README.md](AndroidNative/README.md)。使用独立 Linux/WSL 编译空间，按固定 Chromium 提交同步依赖，验证并备份覆盖补丁后构建 `chrome_public_apk`。需要真正原生编译，不是 Gradle 对桌面程序换壳。

## 本地核验与源码包

```powershell
cd Browserapp
npm run selftest:uaversion
node automation/directory-sync-selftest.js
$env:OPENBROWSER_SOURCE_SUFFIX = 'my-preview'
node scripts/package-source.js
```

源码打包排除设备证据、私密文件、构建输出、内核和自动发布工作流，并生成逐文件 SHA-256。Windows 运行核验、APK 签名/16 KiB 对齐、真机与 UI 验收仍需分别执行；本地单元测试不能替代设备验证。

发布使用本地成品和 GitHub Releases，不自动触发 GitHub Actions 编译。保留根 MIT、Windows 输入组件 AGPL-3.0 通告及 Chromium 各自许可；不要把所有第三方组件统称为 MIT。
