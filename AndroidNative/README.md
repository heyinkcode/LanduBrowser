# Chromium Android 本地内核版

岚渡浏览器的当前 Android APK 从本目录覆盖固定 Chromium 源码构建，不使用旁边的 `Androidapp` WebView 原型。

## 当前状态

完整 ARM64 APK 已构建，内核 `156.0.8078.48`，包名 `com.landu.browser`，配置格式 V18。小米 Android 15/16 的两组 UA-CH、真实 RAF 30/60 FPS 上限及配置保留检查通过；公开脱敏结果见 [测试记录](../docs/TESTING.md)。这是开发签名预览，完整应用及所有机型尚未验收。

环境管理在独立 `:landu_manager` 进程中，UUID 对应稳定数据目录。已接入分组、标签、搜索、配置合并导入导出、回收站、本地代理认证、区域策略、UA-CH 和部分原生指纹能力。运行中的环境使用启动快照，编辑设置在正常关闭后重启生效。一次运行一个浏览器环境；没有团队服务器、云手机或流量中转。

配置导出不包含完整浏览数据和手机保存的密码，不能当作整个 Profile 备份。没有宣称与桌面扩展、RPA、窗口同步或 API/MCP 完全一致。

## 源码入口

| 目录或文件 | 用途 |
| --- | --- |
| `upstream-lock.json` | Chromium 版本、提交与消费入口哈希 |
| `java/` | 环境配置、持久化、代理和启动契约 |
| `android/` | Chromium 接入、管理 Activity、Android 生命周期 |
| `native/` | UA 元数据、RAF 调度、区域与图形等原生辅助实现 |
| `scripts/overlay-plan.mjs` | 固定版本补丁的生成规则 |
| `scripts/stage-overlay.mjs` | 只读校验并生成独立阶段 |
| `scripts/apply-overlay.py` | 取得构建锁、核对哈希、备份与事务应用/恢复 |
| `branding/` | Logo 派生资源、许可证和品牌清单 |
| `tests/`、`host-tests/`、`verification/` | 组件检查、主机框架及自有网页探针 |

原始设备日志、Profile、凭据、构建阶段与私钥不发布。公开测试汇总以 `docs/TESTING.md` 为准，不把脚本存在或源码字段等同于真机功能通过。

## Linux 构建流程

准备独立 Linux/WSL 非 root 编译账号、大容量编译目录和 Chromium 官方工具链。固定检出 `d0c02bfad08ec92a6ffcf989e53cd995e6381a4c`，按该提交的 `docs/android_build_instructions.md` 安装依赖、同步 Android 工具链。构建空间与应用源码、覆盖阶段和恢复备份分开；全部构建使用同一个文件锁。

先设置自己的绝对路径，以下路径均为示例：

```bash
export CHROMIUM_SRC=/data/landu/chromium/src
export LANDU_SOURCE=/data/LanduBrowser
export LANDU_BUILD=/data/landu-build
export LANDU_TEST_CACHE="$LANDU_BUILD/test-cache"
mkdir -p "$LANDU_BUILD/stages" "$LANDU_BUILD/backups" "$LANDU_BUILD/logs" "$LANDU_BUILD/exports" "$LANDU_TEST_CACHE"
# apply-overlay.py 约定的锁位置：src 的上两级
touch "$(dirname "$(dirname "$CHROMIUM_SRC")")/native-build.lock"
```

在未加岚渡补丁的固定提交上先构建基线，确认工具链正常。GN 参数使用：

```gn
target_os = "android"
target_cpu = "arm64"
is_debug = false
is_component_build = false
symbol_level = 0
blink_symbol_level = 0
v8_symbol_level = 0
use_remoteexec = false
android_static_analysis = "on"
```

不要在构建运行时应用或恢复补丁。生成阶段并使用它实际输出的清单 SHA：

```bash
node "$LANDU_SOURCE/AndroidNative/scripts/stage-overlay.mjs" \
  --source "$CHROMIUM_SRC" --output-parent "$LANDU_BUILD/stages"
# 将实际输出填入变量，不使用文档里的虚构哈希
export STAGE_DIR=/absolute/path/to/generated-stage
export MANIFEST_SHA=actual-manifest-sha256
python3 -B "$LANDU_SOURCE/AndroidNative/scripts/apply-overlay.py" \
  --source "$CHROMIUM_SRC" --stage "$STAGE_DIR" --manifest-sha256 "$MANIFEST_SHA"
python3 -B "$LANDU_SOURCE/AndroidNative/scripts/apply-overlay.py" \
  --source "$CHROMIUM_SRC" --stage "$STAGE_DIR" --manifest-sha256 "$MANIFEST_SHA" \
  --apply --backup-parent "$LANDU_BUILD/backups"
cd "$CHROMIUM_SRC"
flock "$(dirname "$(dirname "$CHROMIUM_SRC")")/native-build.lock" \
  bash -c 'gn gen out/LanduBaseline && autoninja -C out/LanduBaseline -j 2 chrome_public_apk'
```

GN 参数应预先写入 `out/LanduBaseline/args.gn`；阶段生成不等于 APK 已构建。构建时也须取得上述构建锁，例如使用 `flock` 包住 GN/autoninja，防止其他进程同时修改 checkout。输出是 `out/LanduBaseline/apks/ChromePublic.apk`，必须再检查包名、ARM64 库、签名、ZIP 完整性、16 KiB 对齐和真实设备运行。

`scripts/build-native-apk.py` 是带构建回执与基线校验的维护驱动，支持显式 `--source`、`--build-root`、`--output-directory`；不内置维护者磁盘路径。它要求已成功的基线回执或已应用且通过核验的阶段；普通手工构建可按上述 GN 流程进行。

品牌生成器会核对仓库 Logo 与根 LICENSE 的哈希。修改这些输入后需按生成器和版本锁重新审查，不要跳过哈希检查。重新签名和换包名也不是对既有安装的数据兼容承诺。

## 能力边界

字体隔离是已列本地字体名称过滤，不是全字体白名单或跨设备字形/Emoji 统一。UA-CH 自定义不会更换实际内核。RAF 只推迟真实网页回调并保留取消语义，设备负载决定能达到的实际帧率。

源码包含更多已接入的指纹与隔离实现，但完整表单、所有存储屏障、后台、媒体/GPU 后端、代理失败分支和所有网站兼容性仍需持续验收。不要把组件编译、模拟器或主机测试当作整个 Android 产品已完成。
