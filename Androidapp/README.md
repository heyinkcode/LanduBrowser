# 历史 WebView 原型（非当前发行 APK）

当前完整内核版源码位于 [AndroidNative](../AndroidNative/README.md)，下载与实测范围见 [安卓指南](../docs/ANDROID.md)。本目录仅保留早期原型供参考，不作为最新 APK 构建入口。

---

# 岚渡浏览器 Android 本地原型

状态：`0.2.0-local-alpha` 环境设置开发版，不是正式安卓发行版。目标验收设备：用户的小米手机，Android 15 和 Android 16；尚未完成这些手机上的实测。

本次编译、单元测试、权限和签名检查的结果见 [构建核验记录](docs/BUILD_VERIFICATION.md)。记录明确区分本地自动检查与尚未执行的真机测试。

## 运行方式

- 手机本地执行网页，使用设备当前 Wi-Fi 或移动网络；不提供 VPN、公共代理、云手机或远程桌面。
- 不需要服务器、电脑开机、岚渡账号或付费激活。代码没有管理后台、自动云同步或遥测上传逻辑。
- 官网与 GitHub 不是运行依赖；应用不会向仓库上传环境资料。网站自身及系统组件的联网行为不属于岚渡后台。
- 本地多环境使用 AndroidX WebKit 的命名 Profile。启动前检测 `MULTI_PROFILE`，不支持就阻止浏览，不回退共享默认 Profile。
- 各环境可以使用相同的公网 IP。数据隔离不代表代理换 IP、完全匿名或保证通过网站检测。

## 已实现的原型范围

1. 本地环境创建、编辑、分组文字、启动网址、备注、复制配置和删除确认；没有预置假账号或示例环境。复制配置生成新 Profile，不复制网站数据。
2. 每个环境使用独立、稳定的 Profile 名称；修改显示名称不改变 Profile。
3. 一次运行一个前台环境，支持 HTTPS 访问、前进、后退、刷新及系统返回键；退出时销毁视图并结束独立浏览进程，保留环境数据。
4. 私有目录保存配置、原子写入；配置损坏时停止写入而不是重置；删除失败保留待清理标记。
5. 禁止网站访问本地文件、不注入高权限 JavaScript 桥、不绕过证书错误；只申请互联网权限。
6. 关闭应用自动备份，并配置 Android 12+ 云备份和设备迁移排除规则；不同厂商系统仍需验证。

7. 四分区设置页，保存到对应环境，下次打开时实际应用；浏览窗口的“配置”可查看 WebView 版本和当前配置值。
8. 手动文件上传通过 Android 系统文件选择器；可按环境关闭。不申请整个存储的读取权限，不允许网页任意浏览本地文件。

### 环境设置与实际作用

| 分区 | 可以调整 | 作用与限制 |
| --- | --- | --- |
| 基本信息 | 名称、分组、启动网址、备注 | 本机配置；不在配置中保存网站密码 |
| 网络代理 | 跟随手机系统网络；无认证 HTTP / HTTPS / SOCKS5 代理 | 必须显式填写协议和端口；不提供代理线路，不支持账号密码认证 |
| 浏览设置 | 自定义 UA、宽屏布局、文字缩放 75–200%、默认字号 12–32、三种通用字体 | 接入 WebSettings；宽屏不是分辨率伪装，默认字体不是字体隔离，网页 CSS 可覆盖默认字号/字体 |
| 隐私权限 | JavaScript、DOM Storage、自动图片、第三方 Cookie、手动文件上传 | 按环境应用；默认阻止第三方 Cookie；关闭开关不删除已存数据，DOM Storage 开关不是全部网页存储的总开关 |

代理作用于 WebView 网页流量，**不是 VPN，不保证 DNS、WebRTC 或系统组件全部经过代理**。选手动代理必须确认这项限制。代理规则不添加直连后备或默认本机地址绕过，等待应用回调后才创建浏览视图；代理能力缺失或配置失败时阻止加载，不主动回退到系统网络。“代理规则已应用”不是代理连通性或出口 IP 验证结果。

管理页和配置编辑页运行在不允许使用 WebView 的 `:manager` 进程；浏览视图和私有 IPC 服务运行在默认进程。代理 API 是进程级，因此不允许两个环境同时占用浏览进程；退出旧环境后结束该进程，再启动其他环境。该 IPC 服务不对外导出，没有网络监听或远程服务器。保留默认进程的 WebView 数据路径以兼容 0.1 的 Profile。

未实现：文件下载、摄像头/麦克风/定位授权、认证代理及代理库、自动出口检测、扩展、多窗口并发、语言/时区/地理位置伪装、WebRTC 防泄露、中文字体白名单、Canvas/WebGL/Audio 噪声、CPU/内存指纹、桌面数据导入、自动更新及云同步。未提供虚假的支持开关。自定义 UA 不会改变真实操作系统或保证 Client Hints 一致。

安卓为新的原生适配工程，未改写桌面引擎。当前配置契约位于 `../shared/contracts/local-environments-v2.schema.json`，保留 v1 契约供旧版识别。实际写入由 `EnvironmentStore` 验证；该契约没有接入桌面存量配置，不能直接用安卓文件覆盖桌面数据。网址合法性、代理端口范围、规范化与 ID 唯一性也必须经过业务层验证，不能只检查 JSON Schema。

### 从 0.1 升级

- 使用同一签名的 0.2 测试 APK 覆盖安装，不要先卸载或清除应用数据。
- v1 配置只读载入时不改文件；下次成功保存后写入 v2。原环境 ID、创建时间和 Profile 名不变，新选项采用默认值。
- 配置损坏、未知版本或写入失败仍保留原数据，不会为了显示列表而自动清空。
- v2 保存后不要降级回 0.1；不同开发者自行构建的 APK 可能签名不同。数据结构迁移已做单元测试，手机上的覆盖安装和登录保留仍需实测。

## 系统要求与能力边界

- 工程最低系统为 Android 9 / API 28；这只是安装门槛，不代表每台 Android 9+ 设备都支持多 Profile。
- 必须安装支持 `MULTI_PROFILE` 的系统 WebView。更新 WebView 后需要完全退出再启动应用；是否可用以运行时检测为准。
- 当前使用系统 WebView，不附带桌面 Chromium 内核，也不伪装成 Windows 浏览器。
- 必须在小米 Android 15、16 分别通过验收，才能标记这些机型为支持；不得用编译成功代替实机隔离验证。

## 构建

依赖：JDK 17/21、Gradle 8.11.1、Android SDK Platform 35、Build Tools 35.0.0。安卓构建插件固定为 8.9.2，Kotlin 固定为 2.1.20，AndroidX WebKit 适配库固定为 1.14.0；网页安全更新来自手机系统 WebView，不能将适配库版本当作浏览器内核版本。

Windows 推荐使用项目脚本，显式传入已有工具路径：

```powershell
.\scripts\Build-Android.ps1 -JavaHome '<JDK目录>' -AndroidSdk '<Android SDK目录>' -CacheRoot '<非系统盘缓存目录>'
```

脚本将 Gradle、Android 工具用户目录、Java 用户目录和临时目录都定向到指定缓存；未指定时使用本工程 `.local/cache`。中文路径会临时映射到未使用的盘符，绕开 Windows 原生工具和 JVM 参数文件的编码限制，退出时解除映射，不移动或删除原文件。不修改 Windows 全局环境变量。调试密钥生成在本工程 `.local/keys`，已被忽略，不可提交或用于正式发布。

默认执行单元测试、Lint、调试 APK 和设备测试 APK 编译。输出：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

仅安装第一个 APK 即可体验；第二个是自动化测试包，不是另一个客户端。调试包使用独立应用 ID `com.landu.browser.local.debug`，不是正式发行包，不建议放入重要业务账号。正式发布前需要独立保管发布签名、补齐隐私说明并完成实机验收。

macOS/Linux 可在配置好对应 SDK/JDK 后使用 Gradle Wrapper；需要同时显式配置 `GRADLE_USER_HOME`、`ANDROID_USER_HOME`、Java `user.home`、`java.io.tmpdir` 与调试签名文件。Windows 脚本不会自动下载工具或代替用户接受 SDK 许可。

## 手机上怎么试用

1. 将 `app-debug.apk` 传到小米手机，仅为可信的安装来源临时授权安装；不需要关闭手机安全防护。
2. 创建环境后点击“打开”，启动时会检查 WebView 能力。若显示不支持隔离，不会使用默认共享环境替代；本地配置仍可编辑。
3. 新建“测试 A”和“测试 B”，都填写同一个 HTTPS 测试网站。先使用测试账号，不使用正式店铺/支付账号。
4. 在 A 登录或保存测试数据，返回环境列表再打开 B；B 不应看到 A 的会话。切回 A 数据应仍存在。
5. 重启应用、重启手机、切换 Wi-Fi/移动数据后分别复查；完全断网时本地增删改仍应可用。
6. 删除会二次确认。如果内核仍占用 Profile，界面显示“待清理”。关闭浏览环境后点击“重试清理待删除环境”；必要时从系统设置强行停止此应用、重新启动后重试。不要清除全部应用数据，这会影响所有环境。
7. 调整一个环境的文字缩放、UA、图片或 JavaScript 后重新打开，并从“配置”和测试网页分别核验实际行为。不要只看保存成功提示。

## 自动测试与真机验收

`EnvironmentStoreTest`、`BrowserOptionsTest`、`SessionLeaseTest` 和 `UrlPolicyTest` 覆盖配置持久化、旧版迁移、复制配置、新选项校验、失败写入、重复 ID、未知版本、删除重试、非法网址、单环境进程占用和退出竞态。设备测试 `ProfileIsolationTest` 用内存响应拦截提供同源 HTTPS 测试文档，不需要部署测试服务器，不联系外部站点。

已连接并授权专用测试设备后，可执行 `connectedDebugAndroidTest`。该测试仅在测试包内创建随机 Profile，验证 Cookie、LocalStorage、IndexedDB、Cache Storage 跨环境隔离和视图重新打开后的保持；不会宣称它验证了进程重启、HTTP 缓存或 Service Worker 隔离。设备不具备 `MULTI_PROFILE` 时测试失败，而不是跳过后当作通过。

新增 `BrowserSettingsTest` 读取实际 WebSettings、Profile 和第三方 Cookie 策略；它只覆盖设置值接入，不证明代理出口、真实文件上传或完整网页交互正确。两个设备测试均已编译，未在设备运行。

完整手工检查见 [小米真机验收表](docs/DEVICE_ACCEPTANCE.md)。不得跳过真实设备验收后对外发布“完全隔离”的宣传。

## 来源与许可

岚渡桌面项目基于 [sheying2013/OpenBrowser](https://github.com/sheying2013/OpenBrowser)；保留根目录 `LICENSE` 和原作者致谢。安卓工程为本地运行目标新增的原生适配，不能把新增实现冒称为上游原有移动端。

AndroidX 组件遵循各自许可（通常为 Apache-2.0）；系统 WebView 由设备提供。正式发布需要核对解析后的依赖清单及许可，并随源码提供相应说明。
