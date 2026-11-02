# Android 真机验证

## UA-CH 与 RAF 独立验证

使用自己的非 C 盘输出目录，手机正常启动对应 QA 环境并保持页面前台。下列工具不切换应用、不模拟输入，不伪造时间戳；预期值来自已保存配置，不能根据结果反填。

```powershell
adb -s <手机序列号> reverse tcp:18768 tcp:18768
adb -s <手机序列号> forward tcp:18766 localabstract:chrome_devtools_remote
node AndroidNative/verification/ua-raf-probe.mjs --port 18768 --output D:/LanduResults/live-results.json
# 在手机打开 http://127.0.0.1:18768/ua-raf-check/ 后，另一个终端读取
node AndroidNative/scripts/run-ua-raf-verification.mjs --phase automatic --version 156.0.8078.48 --fps 60 --output D:/LanduResults/automatic.json
```

自定义时使用 `--phase custom`、实际保存的 `--version` 和 `--fps`。输出目录与文件名按自己的环境修改。HTTP 认证代理夹具与观察器均要求显式 `--serial <自己的测试手机>`；夹具使用临时生成的合成凭据，不能用于真实代理账号。

## 其他检查

这些工具只用于开发验收，不需要公开服务器。Python 服务只监听电脑回环地址，手机通过 USB 的 `adb reverse` 访问；浏览器日常运行不依赖它。结果通过本机转发的调试接口读取并保存到明确指定的非 C 盘路径，不上传到外部服务。

先在手机开启并授权 USB 调试，启动自己的岚渡测试环境。当前开发 APK 提供 `chrome_devtools_remote`；正式发行包的调试策略需另行验收。不得据此修改其他浏览器或非测试标签页。

## 收集结果

在项目根目录运行。先加载本机项目缓存环境，确保临时目录在已授权的 G/F 盘；下面的输出目录必须真实可写，不回退到 C 盘。

```powershell
$env:LANDU_DEVICE_RESULTS = 'D:\LanduResults' # 改为自己的非 C 盘目录
New-Item -ItemType Directory -Path $env:LANDU_DEVICE_RESULTS -Force
adb -s <手机序列号> reverse tcp:18765 tcp:18765
adb -s <手机序列号> forward tcp:18766 localabstract:chrome_devtools_remote
python -B AndroidNative/scripts/serve-verification.py --port 18765
```

保持服务运行，另开已加载相同缓存环境的终端：

```powershell
node AndroidNative/scripts/run-device-verification.mjs --navigate --label A-on --cpu 2 --memory 4 --languages en-US --timezone America/Los_Angeles --sections hardware,identity,gpu,canvas,fonts,textCanvas --output "$env:LANDU_DEVICE_RESULTS/A-on.json"
```

预期值应来自环境设置，不能用刚测到的结果反填。工具仅接受空白页或 `http://127.0.0.1:18765/` 测试标签，必要时可用 `--target` 指定 `/json/list` 中的测试标签 ID。它会把选中的测试标签带到前台；不会导航用户的外部网站标签。发现接口暂未就绪时最多等待 20 秒，不重启浏览器进程。

`complete=true` 仅表示本次收集完成；`observationOnlySections` 的原始观察值不等于验收通过。明确失败或执行环境错误会记录并返回非零退出码。结果文件和安装包 SHA256、设备版本、设置及操作顺序应一起保留。

`hardware` 核对页面、iframe、Dedicated Worker、Service Worker 的 CPU、内存与区域，以及页面/导航请求头。`identity` 核对 JS 身份和实际请求；固定上游的 worker 请求原生不发送整套 UA Client Hints 时，会明确记录完整缺省，不强行发送或绕过权限策略。部分缺失、错误字段、丢失的响应数据及错误 JS 身份仍失败，页面与导航仍严格要求匹配的提示头。

`gpu` 仅核对公开 GPU 标识与执行环境的一致性，不代表图形能力画像、像素噪声或全部 GPU 兼容。`canvas` 收集 RGBA8 固定图案读回与 PNG 原始像素。`fonts` 包括 DOM（页面/iframe）、Canvas、OffscreenCanvas 和 `local()`，Service Worker 的响应由 `waitUntil` 保持。`textCanvas` 区分默认/软件画布、拉丁/中文/混合文字、字体就绪前后；底层字形渲染不应与噪声算法稳定性混为一谈。

`dnt` 必须显式提供 `--dnt enabled` 或 `--dnt disabled`，预期来自保存并重启后的环境设置。例如 `--sections dnt --dnt enabled` 会核对页面和 iframe 的 `navigator.doNotTrack`，以及页面、iframe、Dedicated Worker、Service Worker 和导航发出的实际 DNT 请求头。关闭后重启并以 `--dnt disabled` 复查；不要把单一开启结果当作开关对照，也不要把网站是否遵守 DNT 算作本工具可验证的能力。

`audio,rects` 收集主页面与 iframe 的原始信号。音频只使用离线图，不播放声音或请求媒体权限；覆盖两通道离线缓冲、完成事件、复制/重复读取、四类分析读数、静音数据、目标数组尾部，以及 AudioWorklet 收集到的图输入。几何探针检查 Element/Range、折叠选区、隐藏元素、滚动容器与 CSS zoom，不等同于手势缩放或所有几何 API。

先记录真实开关状态，关闭“固定环境音频噪声”和“固定环境 ClientRects 噪声”，保存并正常重启后运行 `--sections audio,rects`，输出 `signals-off.json`；恢复开启并重启输出 `signals-on.json`，再正常重启输出 `signals-repeat.json`。若原状态为关闭，测试结束需另行恢复。比较命令：

```powershell
node AndroidNative/scripts/compare-signal-verification.mjs --before "$env:LANDU_DEVICE_RESULTS/signals-off.json" --after "$env:LANDU_DEVICE_RESULTS/signals-on.json" --repeat "$env:LANDU_DEVICE_RESULTS/signals-repeat.json" --output "$env:LANDU_DEVICE_RESULTS/signal-comparison.json"
```

比较器保留输入和验证源码哈希。音频 `corePassed` 只覆盖所列常规读回；AudioWorklet 仍读到原始信号时，整体 `passed` 必须为 `false` 并返回非零退出码，不能靠忽略这条路径宣称完整通过。音量静音、真实录音、其他设备和 Android 版本仍需另行验证。

`webgl` 收集页面、iframe、Dedicated Worker 的 WebGL1/2 和 HTML/OffscreenCanvas。通过环境中的 Canvas 固定噪声开关保存关闭、开启、再正常重启三份报告，用 `compare-webgl-verification.mjs --before ... --after ... --repeat ... --output ...` 对照。它直接解析 PNG 像素，并核对数组/PBO、PACK、边界、错误保留和重启一致性，不以摘要不同替代这些检查。

`media` 只枚举设备和查询权限。`mediaBlocked` 只用于已保存并重启的“禁止网站使用摄像头和麦克风”环境；若策略错误地允许流，探针立即停止它。`mediaCapture` 会触发真实的网页和系统权限流程，短暂建立音视频流，读取轨道身份和能力后立即停止；不录制、保存画面或播放。超过 60 秒记为失败，晚到的流也会停止。`mediaSelect` 只在已授权状态下，按刚枚举的非默认输入 ID 选择设备，每种最多四个；每次检查后立即停止，超时和失败不能视为通过。

使用自建测试环境，记录并在结束时恢复媒体模式、禁用开关和原系统权限。对照需要未授权、禁止采集、恢复未授权、A 隔离、A 隐藏名称、A 恢复隔离、B 隔离以及设备选择八份报告。运行 `compare-media-verification.mjs` 时，用 `--initial`、`--blocked`、`--permission-restored`、`--isolated`、`--hidden`、`--restored`、`--other`、`--selection` 分别指定这些记录，最后提供 `--output`。未授权的空 ID 不能当作跨环境隔离成功；默认别名与非默认 ID 分开处理，组标识保留原生文档生命周期。

`interaction` 在本机验证页建立临时固定目标，通过浏览器原生 `Input.synthesizeTapGesture` 触摸注入检查可信事件和实际计数，覆盖三种目标、中心和四个近边缘点、两档 CSS zoom，共 30 次。不会以 JS `click()` 或事件派发冒充输入成功。测试后移除临时目标；渲染器断开时记录失败，不自动重启或把旧页面结果当作新结果。它不覆盖所有页面、输入法、长按选择或历史命中测试异常的根因；需同时保留设备时间及对应崩溃缓冲。

## 开关对照

通过实际环境设置关闭字体隔离或 Canvas 噪声，保存并正常关闭、重启环境，再分别保存 `A-fonts-off.json` 与 `A-canvas-off.json`。恢复开关并重启后收集 `A-on.json`，再次完整重启并收集 `A-repeat.json`。测试过程中不得清空应用数据或用 JS 替换浏览器的返回值。

```powershell
node AndroidNative/scripts/compare-device-verification.mjs --fonts-before "$env:LANDU_DEVICE_RESULTS/A-fonts-off.json" --canvas-before "$env:LANDU_DEVICE_RESULTS/A-canvas-off.json" --after "$env:LANDU_DEVICE_RESULTS/A-on.json" --repeat "$env:LANDU_DEVICE_RESULTS/A-repeat.json" --output "$env:LANDU_DEVICE_RESULTS/comparison.json"
```

比较器保留输入文件哈希。设备原本没有某字体只能记为未观察到，不能算隔离通过；字体、运行标识、超时或对照缺失也不能跳过。Canvas 检查包含原始对照、各导出路径、透明/alpha、裁剪、重复读取、写回、跨执行环境及进程重启的一致性。通过结果只覆盖报告写明的设备、设置和样本，不能替代 HDR、其他 GPU、首次安装、Android 16 或完整产品验收。

完成后关闭测试环境，移除本次端口并停止本机服务。保留用户环境、账号、浏览数据及其他已有端口：

```powershell
adb -s <手机序列号> reverse --remove tcp:18765
adb -s <手机序列号> forward --remove tcp:18766
```
