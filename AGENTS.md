# AGENTS.md — EgoAudio-Mobile_Collector

本仓库是 EgoAudio 的 Android 采集端。**共享约束、隐私边界和网络红线只维护在工作区根目录的
`../AGENTS.md`，本文件不复制也不覆盖它们**，只补充这个仓库自己的事实、边界和验证方式。

Claude Code 会通过根 `CLAUDE.md` 导入根 `AGENTS.md`；Codex 自动读取根 `AGENTS.md`。
进入本仓库工作前，先读根 `AGENTS.md`、`../doc/status/PIPELINE_STATUS_2026-07-27.md`
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

- 会话目录：`Downloads/nuna_{device}_{started_at_ms}/`，结构见 `docs/SESSION_SYNC_PROTOCOL.md` §1。
- 音频是**裸 Opus 帧流**，`SessionManifest.toJson()` 里显式声明：
  `codec=opus_raw`、`sample_rate_hz=16000`、`channels=2`、`frame_duration_ms=20`、`frame_size_bytes=80`。
  **没有 Ogg/WebM 容器**，所以任何不自带解码器的消费方（浏览器、通用 ASR API）都放不了。
- 本地解码器：`audio/OpusToWavConverter.kt`（→ WAV，本地播放）、
  `audio/OpusToPcmMono.kt`（→ 16 kHz mono float，喂 Silero VAD）。
  服务端解码必须与这两个文件的参数逐项一致，并做 PCM 逐样本比对。
- `SessionPaths.SEGMENT_DURATION_MS = 60_000`；设置项允许 10–600 秒，
  但**当前生产 profile 固定 60 秒**，非 60 秒数据不得进入现有 Web 标注模型。

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

完整证据和服务端落库结果见 `../doc/status/PIPELINE_STATUS_2026-07-27.md` §3。

**在 Data Platform 的 Receiver 实现 session v1 之前，不得用本 App 上传真实采集数据。**
对齐新契约时应删除或禁用这条会话级 legacy 回退（`RecordingEntry.LegacyOpus` 的单文件路径
是另一回事，可保留）。

### 2.4 网络配置会直接阻断连接

`app/src/main/res/xml/network_security_config.xml` 只对
`172.20.10.3`、`10.0.2.2`、`120.79.196.171` 放行明文 HTTP。
Android 9+ 默认禁止明文，**不在白名单里的服务器地址会被系统直接拒绝**。
换服务器（例如 RTX5060 的 Tailscale/内网地址）必须同步改这个文件并重新构建 APK。

### 2.5 采集生命周期与长时可用性

- `MainActivity:113` 的 `private val sessionRecorder = SessionRecorder { ... }` 是 **Activity 字段**，
  BLE 采集管线挂在 Activity 实例上，没有独立的录制前台服务。
- 进程存活依赖录制期间启动的 `service/ContextDataService`（前台服务，`location|dataSync`）。
  熄屏通常可撑住，**用户划掉任务卡片会中断采集**。
- `service/ScreenOffKeepAlive.kt` 只在「有后台任务」时重新持 WakeLock 并拉起 VAD 前台服务，
  不覆盖录制本身。
- **12 小时连续采集目前是未验证状态**，不是已知可用。发设备前必须做真机 soak。

### 2.6 其他容易踩的点

- `UserSettings.userId` 留空时，上传会用 `"mock-user-001"`（`SessionSyncCoordinator` 里的
  `settings.userId.ifBlank { ... }`）。逐台设备核对，别把参与者数据写到同一个假账号下。
- 默认 `serverHost=10.0.2.2`、`serverPort=9000`（模拟器回环），装机后必须改。
- VAD 结果写在 `labels/vad_prelabel.json`，含 `has_speech` / `speech_ratio` / `speech_ms`，
  是**采集期机器证据**，不是权威人工标签，不得用来删音频或跳过 ASC/SED。

## 3. 测试现状

`app/src/test` 与 `app/src/androidTest` **只有 IDE 生成的模板测试**
（`ExampleUnitTest`、`ExampleInstrumentedTest`）。所以：

```bash
./gradlew test          # 通过不代表采集/同步/解码逻辑被验证过
./gradlew assembleDebug
```

改动同步、manifest、VAD、解码或会话生命周期时，**必须自带 JVM 单元测试**。
优先给这些纯逻辑加测试（不需要设备）：

- `AudioMetaUtil.parseStartTimeFromFileName`（当前对 `seg_000.opus` 返回 0 就是缺测试的直接后果）
- `SessionManifest` 的 `toJson` / `load` 往返
- `SessionSyncInventory.buildRelative` 的路径与 media type
- `OpusToWavConverter` / `OpusToPcmMono` 对固定 fixture 的解码输出

真机验证不可省的部分：BLE 断连重连、熄屏、切网、长时 soak、权限拒绝路径。

## 4. 修改本仓库时的完成标准

1. 改动落在 `feature/egoaudio-data-collection`，不动上游 `main`。
2. 纯逻辑改动带 JVM 测试；涉及采集生命周期的改动附真机验证记录（时长、机型、Android 版本）。
3. 不把服务器地址、令牌、verification code 或参与者标识写死进源码或提交信息。
4. 协议相关改动与 `EgoAudio-Data_Platform` 的 Receiver 分别提交，并在交接记录里同时列出两个 SHA
   和对应的 contract version。
5. 不宣称端到端打通，除非服务端也通过了对应契约测试并有真机证据。
