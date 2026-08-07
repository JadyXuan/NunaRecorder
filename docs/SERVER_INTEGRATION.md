# NunaRecorder 自建服务器对接指南

本文说明如何让 NunaRecorder Android App 对接用户自己的服务器。App **不内置默认服务器**，新安装时 Base URL、用户 ID 为空，生活记录、后台轮询和音频自动上传均关闭；完成显式配置前不会向业务服务器发送请求。

更完整的会话目录、校验和与三阶段同步协议见 [SESSION_SYNC_PROTOCOL.md](SESSION_SYNC_PROTOCOL.md)。

## 1. App 配置

在 App 的 **设置** 页面填写：

1. **用户 ID**：服务器用于隔离用户数据的稳定标识。
2. **Base URL**：包含协议和可选部署路径，例如 `https://recorder.example.com` 或 `https://example.com/nuna`。
3. **Basic Auth 用户名/密码**：可选；仅当两个字段都不为空时，App 才发送 `Authorization: Basic ...`。
4. 根据需要开启 **显示活动时间轴与标注**、**后台检查待标注事件**或**自动上传已完成的录音切片**。
5. 点击 **保存设置**。

Base URL 只接受 `http://` 或 `https://`，不能包含查询参数、片段或内嵌用户名密码。生产环境必须使用 HTTPS；HTTP 只适合可信局域网调试。

App 会把下文中的固定路径直接拼接到去掉末尾 `/` 的 Base URL。例如 Base URL 为 `https://example.com/nuna/` 时，音频上传地址为 `https://example.com/nuna/thingx/api/file/upload/audio`。

## 2. 通用认证与用户隔离

| 机制 | App 行为 |
|---|---|
| HTTPS | 由部署者提供有效证书；App 使用 Android 系统信任库 |
| HTTP Basic Auth | 可选；用户名和密码都填写时发送 |
| `X-User-Id` | 时间轴、日记和标注接口发送，值来自设置中的用户 ID |
| `X-Lifelog-Client` | 生活记录接口固定发送 `NunaRecorder-Android` |
| 上传用户标识 | 旧音频接口放在 `metadata.userId`；v1 会话接口放在 `user_id` |

`X-User-Id` 只是当前研究协议中的租户标识，不能替代生产级身份认证。多用户部署应在反向代理或服务端增加登录令牌，并校验令牌主体与用户 ID 的对应关系。

## 3. 最小音频上传接口

只需要接收录音切片时，实现下面这个接口即可。自动上传固定使用此接口；手动完整会话同步在 v1 接口不可用时也会回退到它。

### `POST /thingx/api/file/upload/audio`

请求类型：`multipart/form-data`

| Part | 类型 | 说明 |
|---|---|---|
| `file` | 二进制文件 | 已封口的裸 Opus 数据；自动上传文件名在不同会话间唯一 |
| `metadata` | `application/json` 文件 | 下面六个固定字段，不应要求额外字段 |

`metadata` 示例：

```json
{
  "userId": "user-001",
  "name": "session-123_seg_000.opus",
  "startTime": 1786000000000,
  "endTime": 1786000060000,
  "mac": "AA:BB:CC:DD:EE:FF",
  "size": 240000
}
```

字段时间单位为 Unix epoch 毫秒。服务端返回任意 `2xx` 即视为接收成功；建议返回 JSON，并在持久化完成后才响应成功。

自动上传还会发送：

```http
Idempotency-Key: <session-id>:<segment-index>:<sha256>
```

服务端应使用该值或“用户 + 内容哈希 + 时间范围”去重，使客户端重试保持幂等。

### 音频格式

`.opus` 文件不是 Ogg/Opus 容器，而是按顺序直接拼接的 Opus packet：

- 采样率：16 kHz
- 逻辑输出：单声道
- 每 packet：20 ms
- 当前 packet 固定长度：80 bytes
- 文件时长：`文件字节数 / 80 × 20 ms`

服务端不能直接把它当作标准 `.ogg` 文件读取。应每 80 bytes 取出一个 packet 送入 Opus decoder，或先转换成 WAV。会话的 `manifest.json` 也包含 `sample_rate_hz`、`frame_duration_ms` 和 `frame_size_bytes`，服务端应优先校验这些字段。

## 4. 推荐的完整会话同步

手动同步完整会话时，App 优先调用：

1. `POST /thingx/api/v1/session/sync/init`
2. `POST /thingx/api/v1/session/sync/file`
3. `POST /thingx/api/v1/session/sync/commit`

这套协议可以同时上传 `manifest.json`、Opus 切片、context JSONL 和 VAD 标签，并通过 SHA-256 与最终 commit 确认完整性。完整请求/响应结构见 [SESSION_SYNC_PROTOCOL.md](SESSION_SYNC_PROTOCOL.md#2-同步流程推荐服务端实现)。

如果 `init` 返回 `404`，App 会回退到第 3 节的旧接口。若服务端实现了 `init`，则应同时实现 `file` 和 `commit`，并满足：

- `client_upload_id` 可安全重试；
- 每个文件必须校验声明的 SHA-256；
- 重复上传同一 `upload_id + relative_path + sha256` 应幂等；
- 只有全部文件存在且校验通过时，`commit` 才返回 `status: "synced"`；
- 缺失文件返回 `status: "partial"` 和 `missing[]`。

## 5. 生活记录与标注接口

启用生活记录后，App 使用以下接口。所有请求都带 `Accept: application/json`、`X-Lifelog-Client` 和非空的 `X-User-Id`。

### 时间轴

`GET /api/v1/timeline?date=YYYY-MM-DD`

```json
{
  "schema_version": 1,
  "date": "2026-08-07",
  "taxonomy": [
    {"id": "meeting", "display_name": "会议"}
  ],
  "segments": [
    {
      "segment_id": 101,
      "start_time_ms": 1786000000000,
      "end_time_ms": 1786001800000,
      "predicted_label": "meeting",
      "confidence": 0.91,
      "source": "audio_model",
      "reviewed_label": null,
      "review_action": null,
      "asr_text": "讨论项目进度",
      "top_sound_events": [
        {"label": "speech", "confidence": 0.95}
      ]
    }
  ]
}
```

主路由返回 `404` 时，App 会尝试兼容路由 `GET /api/timeline?date=...`。

### 待标注事件

`GET /api/v1/annotations/pending`

```json
{
  "schema_version": 1,
  "items": [
    {
      "event_id": 42,
      "kind": "block",
      "question": "这段时间是在开会吗？",
      "suggested_label": "meeting",
      "suggested_display_name": "会议",
      "start_time_ms": 1786000000000,
      "end_time_ms": 1786001800000,
      "asr_context": "讨论项目进度",
      "created_at_ms": 1786001900000,
      "expires_at_ms": null
    }
  ]
}
```

主路由返回 `404` 时，App 会尝试 `GET /api/pending`。后台轮询由 Android WorkManager 调度，系统允许时大约每 15 分钟执行一次，不保证精确到分钟。

### 提交标注

`POST /api/v1/annotations`

```json
{
  "event_id": 42,
  "action": "confirm",
  "label": null
}
```

`action` 为 `confirm`、`correct` 或 `skip`；`correct` 时 `label` 应为 taxonomy 中的 ID。

```json
{
  "annotation_id": 9,
  "event_id": 42,
  "action": "confirm",
  "effective_label": "meeting",
  "memory_updated": true
}
```

主路由返回 `404` 时，App 会尝试 `POST /api/annotate`。

### 日记

`GET /api/v1/diary?date=YYYY-MM-DD`

```json
{
  "schema_version": 1,
  "date": "2026-08-07",
  "entries": [
    {
      "start_time_ms": 1786000000000,
      "end_time_ms": 1786001800000,
      "label": "meeting",
      "display_name": "会议",
      "summary": "讨论项目进度"
    }
  ]
}
```

日记接口没有旧路由回退。

## 6. 调度、流量和失败语义

- 自动音频上传默认关闭；开启后默认仅使用非计费网络（通常是 Wi-Fi），并要求电量不低。
- 每轮自动上传最多处理 12 个已封口且完整性通过的 Opus 切片；正在写入或有 BLE 丢帧的切片不会上传。
- 后台标注轮询默认关闭；开启后约每 15 分钟请求一次 pending 接口。
- 网络失败和 `5xx` 可能触发 WorkManager 退避重试，因此服务端接口必须幂等。
- 手动上传会在 UI 显示成功或失败；未配置有效 Base URL/用户 ID 时，App 在本地直接拒绝，不发起网络请求。
- 上传成功表示服务器已接收；后续 ASR、声音事件识别、活动推断和日记生成由服务端自行调度。

## 7. 服务端实现检查清单

1. 使用 HTTPS，并设置合理的请求体大小和超时限制。
2. 为用户提供独立身份认证，不把 `X-User-Id` 当作可信登录凭据。
3. 先实现最小音频上传接口，并用非敏感测试录音验证 multipart 解析。
4. 校验 Opus 文件长度是 80 的整数倍，并验证 metadata 时间范围。
5. 对 `Idempotency-Key` 和 v1 `client_upload_id` 实现幂等。
6. 需要全会话/context/VAD 时再实现 v1 三阶段同步。
7. 需要 App 时间轴、日记和主动标注时实现第 5 节四组接口。
8. 制定音频保留、删除、访问审计和用户撤回策略。
