# AGENTS.md — EgoAudio-Mobile_Collector

本仓库是 EgoAudio 的 Android 采集端。**共享约束、隐私边界和网络红线只维护在工作区根目录的
`../AGENTS.md`，本文件不复制也不覆盖它们**，只补充这个仓库自己的事实、边界和验证方式。

Claude Code 会通过根 `CLAUDE.md` 导入根 `AGENTS.md`；Codex 自动读取根 `AGENTS.md`。
进入本仓库工作前，先读根 `AGENTS.md`、`../doc/status/2026-07-27-pipeline-status.md`
和本仓库的 `docs/SESSION_SYNC_PROTOCOL.md`。

## 1. 仓库身份与分支纪律

- 远端仍是 [`JadyXuan/NunaRecorder`](https://github.com/JadyXuan/NunaRecorder)，**不属于本项目所有**。
- EgoAudio 的开发**只在 `feature/egoaudio-data-collection`**，不直接向上游 `main` 提交，
  不 force-push，不重写上游历史。
- 包名、目录名和 UI 文案仍大量使用 `nunarecorder` / `Nuna`。这是历史名称，
  在文档和提交信息里第一次出现时应同时写出当前组件名 `EgoAudio-Mobile_Collector`。
- 设备侧敏感配置（verification code、固件参数、第三方密钥）不进 Git。

## 2. 当前实现事实（2026-07-27 核实）

### 2.1 会话与音频格式

> 2026-08-01 起 `BleAudioReassembler` 已拆成 `ble/OpusStreamAssembler.kt`（纯 Kotlin，
> 负责重组和丢帧记账）+ `ble/SegmentOpusWriter.kt`（只写文件）。
> 旧类把两件事揉在一起，必须随分段重建，跨段的半条消息每分钟丢一次。


- 会话目录：`Downloads/nuna_{device}_{started_at_ms}/`，结构见 `docs/SESSION_SYNC_PROTOCOL.md` §1。
- 音频是**裸 Opus 帧流**，`SessionManifest.toJson()` 里显式声明：
  `codec=opus_raw`、`sample_rate_hz=16000`、`channels=2`、`frame_duration_ms=20`、`frame_size_bytes=80`。
  **没有 Ogg/WebM 容器**，所以任何不自带解码器的消费方（浏览器、通用 ASR API）都放不了。
- 本地解码器：`audio/OpusToWavConverter.kt`（→ WAV，本地播放）、
  `audio/OpusToPcmMono.kt`（→ 16 kHz mono float，喂 Silero VAD）。
  服务端解码必须与这两个文件的参数逐项一致，并做 PCM 逐样本比对。
- `SessionPaths.SEGMENT_DURATION_MS = 60_000`。2026-08-01 起分段时长**锁死 60 秒**，
  设置项已移除（`RecordingOptions.from` 不再读用户设置）。要支持其他时长必须先引入
  显式 schema/version 兼容，而不是放开一个输入框。
- manifest 新增三处，都是为了让时间轴上的空洞可解释：
  `audio.segments[].frames`（期望/实到/序号丢失/空洞位置）、
  `audio.missing_segments`（完全没有音频的分段序号）、
  `audio.deleted_segments`（参与者主动删除，与前者是两回事）、
  `link`（断连区间 + 重组器诊断）。

### 2.2 同步客户端

- `sync/SessionSyncCoordinator.kt` → `sync/SessionSyncUploader.kt` 实现
  `init -> file -> commit`，清单和逐文件 SHA-256 由 `sync/SessionSyncInventory.kt` 生成。
- Base URL 拼装是 `"http://${settings.serverHost}:${settings.serverPort}"`——
  **只有 HTTP，没有 HTTPS**，正式域名前必须改成完整 HTTPS Base URL。
- 当前请求路径是 `/thingx/api/v1/session/sync/*`。EgoAudio 冻结自有 namespace 后要改。

### 2.3 已知会损坏数据的代码路径

`SessionSyncCoordinator.syncSessionFallback()` 在 v1 `init` 返回 404 时，
把整个会话逐文件丢给 legacy `POST /thingx/api/file/upload/audio`。实测后果：

- `AudioMetaUtil.parseStartTimeFromFileName("seg_000.opus")` 返回 **`0`**（把 `"000"` 当成 Long），
  不是 `null`，所以不会走 `lastModified` 兜底 → 所有段的 `startTime` 变成 segment 序号；
- metadata 的 `name` 只取 basename → 不同会话的 `seg_000.opus` 在服务端**互相覆盖**；
- `manifest.json` / `context/context.jsonl` / `labels/vad_prelabel.json` 也走同一个上传，
  被服务端当成 60 秒音频入库。

完整证据和服务端落库结果见 `../doc/status/2026-07-27-pipeline-status.md` §3。

**在 Data Platform 的 Receiver 实现 session v1 之前，不得用本 App 上传真实采集数据。**
对齐新契约时应删除或禁用这条会话级 legacy 回退（`RecordingEntry.LegacyOpus` 的单文件路径
是另一回事，可保留）。

### 2.4 网络配置会直接阻断连接

`app/src/main/res/xml/network_security_config.xml` 逐个放行明文 HTTP，当前列表：
RTX5060 的 Tailscale (`100.107.60.40`) 与 IE 内网 (`192.168.85.238`)、模拟器回环
(`10.0.2.2`)，以及两个历史调试地址。

Android 9+ 默认禁止明文，**不在白名单里的服务器地址会被系统直接拒绝**——
表现为"连不上服务器"，不是任何应用层错误。换服务器必须同步改这个文件并重新构建 APK。
正式采集走 HTTPS 上传域名时不需要任何明文例外。

### 2.5 采集生命周期与长时可用性（2026-08-01 重写）

- 采集由 `service/RecordingService.kt` 前台服务拥有（`connectedDevice|dataSync`），
  持 `PARTIAL_WAKE_LOCK`。Activity 只读 `recording/RecordingController.kt` 的
  两个 StateFlow，不再持有 GATT 或录制管线。
- BLE 链路在 `ble/NunaBleLink.kt`：断开后按 `ble/ReconnectPolicy.kt` 指数退避重连
  （上限 30 秒、**无次数上限**），只有用户主动停止才结束。覆盖走出范围、设备关机、
  手机蓝牙被关（监听 `ACTION_STATE_CHANGED`），以及「链路连着但设备不推流」
  ——最后这种只能靠看门狗超时抓。
- 重连**继续写同一个会话**；中断区间写进 manifest 的 `link.events[]`（绝对 epoch 毫秒）。
- 分段轮转由 `SessionRecorder.tick()` 按秒驱动，不再依赖 `feed()`。
  BLE 断开时轮转照常推进，空段记进 `audio.missing_segments`。
- **16 小时连续采集仍是未验证状态。** 上述改动都还没跑过真机 soak，
  通过标准是「连续 N 小时无断连，或断连后自动恢复且 manifest 有记录」，
  不是「跑完没崩」。发设备前必须做。

### 2.6 其他容易踩的点

- `UserSettings.userId` 留空时，上传会用 `"mock-user-001"`（`SessionSyncCoordinator` 里的
  `settings.userId.ifBlank { ... }`）。逐台设备核对，别把参与者数据写到同一个假账号下。
- 默认 `serverHost=10.0.2.2`、`serverPort=9000`（模拟器回环），装机后必须改。
- VAD 结果写在 `labels/vad_prelabel.json`，含 `has_speech` / `speech_ratio` / `speech_ms`，
  是**采集期机器证据**，不是权威人工标签，不得用来删音频或跳过 ASC/SED。

## 3. 测试现状（2026-08-01）

```bash
export ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew test
./gradlew assembleDebug
```

59 项 JVM 单测，覆盖：

| 测试 | 覆盖 |
| --- | --- |
| `ble/OpusStreamAssemblerTest` | 跨 notification 拼包、序号回绕/回退/重复、假帧头重同步、未凑齐帧淘汰 |
| `recording/SessionRecorderTest` | 墙钟分段轮转、空段索引推进、帧账目、断连区间记录 |
| `recording/RecordingStateMachineTest` | 停止后迟到回调不得复活会话；重连计数 |
| `recording/SegmentDeleterTest` | 按片段删除与悬空引用清理 |
| `recording/SessionRecorderConcurrencyTest` | feed/tick 并发下字节守恒；**是冒烟测试不是竞态检测器** |
| `session/SessionManifestTest` | manifest 往返，含 `frames` / `link` / `missing_segments` |
| `sync/SessionSyncPlannerTest` | `client_upload_id` 复用与重开条件、服务端状态校正 |

JVM 单测需要真的 `org.json`（`testImplementation("org.json:json:...")`），
Android SDK 里的是桩，round-trip 测试会假失败。

**仍然没有覆盖的**：`NunaBleLink`、`RecordingService` 的 Android 侧（需要真机/instrumented），
`OpusToWavConverter` / `OpusToPcmMono` 的解码输出，`SessionSyncUploader` 的 HTTP 行为。

改动同步、manifest、VAD、解码或会话生命周期时，**必须自带 JVM 单元测试**。
真机验证不可省的部分：BLE 断连重连、熄屏、切网、长时 soak、权限拒绝路径、
系统深色模式下的配色。

## 4. 修改本仓库时的完成标准

1. 改动落在 `feature/egoaudio-data-collection`，不动上游 `main`。
2. 纯逻辑改动带 JVM 测试；涉及采集生命周期的改动附真机验证记录（时长、机型、Android 版本）。
3. 不把服务器地址、令牌、verification code 或参与者标识写死进源码或提交信息。
4. 协议相关改动与 `EgoAudio-Data_Platform` 的 Receiver 分别提交，并在交接记录里同时列出两个 SHA
   和对应的 contract version。
5. 不宣称端到端打通，除非服务端也通过了对应契约测试并有真机证据。
