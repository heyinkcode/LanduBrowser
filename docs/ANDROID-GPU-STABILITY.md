# Android GPU 稳定性修复（2026-11-05）

## 已确认的问题

Redmi Note 12 Turbo / Android 15 的真实设备日志显示，Adreno Vulkan 驱动将 `VKDBGUTILWARN003` 性能提示报告为校验错误，消息标识为空。这会触发 Dawn 的 Vulkan 调试回调断言，导致 GPU 子进程以 `SIGABRT` 退出。独立 WebGPU 测试页在旧版上复现了该退出和设备丢失。

截图中的“崩溃啦”本身不能区分 GPU、渲染进程或内存问题。本次修复对应上述捕获到的 GPU 错误，不代表所有页面崩溃都只有这一个原因。

## 源码修改

`AndroidNative/scripts/overlay-plan.mjs` 为固定版本的 `third_party/dawn/src/dawn/native/vulkan/BackendVk.cpp` 生成补丁。在现有消息筛选函数内，仅忽略已捕获性能提示的完整字符串。其他提示、带附加错误内容的消息和真实 Vulkan 校验错误继续按原逻辑处理。

补丁没有关闭 WebGPU、GPU 加速或沙箱。`upstream-lock.json` 固定原始文件哈希；阶段校验和补丁重复应用检查仍生效。

可在 Linux Chromium 构建环境运行行为回归检查：

```sh
python3 AndroidNative/scripts/test-vulkan-debug-warning.py \
  --source "$CHROMIUM_SRC" \
  --output-parent "$LANDU_TEST_OUTPUT"
```

`CHROMIUM_SRC` 指向固定 Chromium 源码，`LANDU_TEST_OUTPUT` 指向允许写入的测试目录。该检查编译实际生成的消息筛选函数，覆盖 11 个行为场景；它不能代替 APK 和真机验证。

## 发行版本

岚渡应用版本为 `1.0.26.20261105.1`，Android 版本码为 `807804805`，真实内核版本保持 `156.0.8078.48`。应用版本与 UA 自动推荐分开，“关于岚渡浏览器”同时显示发行版本、版本码和内核版本。

## 本次验证

- Chromium `156.0.8078.48` ARM64 内核完成构建。完整 APK 与已验证基线相比，GPU 修复阶段仅 `lib/arm64-v8a/libchrome.so` 内容改变，其余 4,222 个非签名条目一致；之后另行更新发行元信息、关于界面及 UA 推荐版本来源，全部原生库保持该已验证版本。
- APK 签名、签名证书一致性、ZIP 完整性、ZIP 与原生库 16 KiB 对齐检查通过。
- Android 15 真机完成同签名覆盖更新；首次启动前回读确认环境目录配置文件及应用身份未变。
- 两轮独立监测的 WebGPU 测试均通过。每轮包含 8 组采样数、加载方式和存储方式组合；无校验错误或设备丢失，三角形像素读回符合预期。
- 在同一真实环境内，Example、Google 搜索、Wikipedia、Chrome 开发文档、About Google 等共 7 个地址循环访问 3 轮，完成 21 次导航。无页面崩溃、错误文档或调试命令失败；安装后没有新增 Android 崩溃缓冲区记录。

本次 GPU 修复的真机范围为 Redmi Note 12 Turbo / Android 15。Android 16、其他 GPU 和机型、长时间后台运行及全部网站兼容性尚未据此验收。此前 Android 16 的 UA-CH、RAF 检查结果也不能替代本次 GPU 验证。

原始日志、崩溃转储、代理凭证和用户环境数据不随公开源码或安装包发布。
