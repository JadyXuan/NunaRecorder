# NunaRecorder v0.1.0-beta.9

本版本在 beta.8 的产品版 Nuna 录音兼容基础上，增加毫米波原始数据、雷达省电周期状态和音频传输时间线，供 Audio-RAG/lifelog 多模态研究使用。

## 毫米波采集与省电周期兼容

- 设置中可选择采集产品版 Nuna A001 `0x08` 毫米波原始包；未知 payload 和完整协议包均无损保存。
- App 通过 A002 雷达控制命令尝试开启/关闭产品版雷达；不支持 A001 雷达状态的 XIAO/旧设备继续走原有音频兼容流程。
- 产品固件在省电休眠时会暂时上报 A001 `0x13=false`。App 现在把“当前活跃状态”和“由 App 请求开启的控制所有权”分开，不会因休眠漏发最终 OFF。
- `context/mmwave_state.jsonl` 保存 `active/sleeping/inactive` 状态及统一会话偏移；休眠窗口保持为无观测区间，不会被包序号压缩。

## 多模态时间线

- `context/mmwave.jsonl` 每包包含 BLE 回调时间、单调时钟会话偏移、设备时间戳、原始 payload 和完整包。
- 新增 `audio/timeline.jsonl`，保存每个实际写入流程的 A003 Frame/Chunk、设备时间戳、包内 Opus 帧数和会话偏移。
- BLE 帧号不连续、分块未收齐或长度异常会写入 `audio_integrity_event`，服务端可定位低置信度区间。
- 会话偏移基于 Android 单调时钟；系统时间校准不会导致音频和毫米波时间轴倒退或跳跃。
- 完整 v1 会话同步、分享和导出会包含相应时间线文件；旧版自动上传接口仍只发送完整的 Opus 切片。

## 兼容性与安全边界

- 保留 beta.8 的产品版 START/STOP、完整 A003 分组边界、停止后保持 GATT 连接和残留录音恢复逻辑。
- 保留 XIAO nRF52840 单帧 Opus、缺少雷达状态以及标准/扩展电量协议兼容。
- 毫米波 payload 的物理含义尚未由协议定义，App 不推测心率、呼吸或活动结论。
- 公开 APK 不包含默认服务器、共享 Basic Auth 凭据或第三方 API Key；上传默认关闭。

## 安装

下载 `NunaRecorder-v0.1.0-beta.9.apk`。使用此前正式签名 Beta 的用户可以直接覆盖升级，现有录音和设置会保留。Android 8.0 或更高版本受支持。

请在采集他人声音或环境数据前取得必要同意，并遵守所在地法律。
