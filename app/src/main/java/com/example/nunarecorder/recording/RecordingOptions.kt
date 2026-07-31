package com.example.nunarecorder.recording

import com.example.nunarecorder.data.UserSettings
import com.example.nunarecorder.session.SessionPaths

data class RecordingOptions(
    val segmentEnabled: Boolean,
    val segmentDurationMs: Long,
    val autoVadOnRecord: Boolean
) {
    companion object {
        /**
         * Nuna profile 下分段时长**锁死 60 秒**，不再由设置项决定。
         *
         * Web 端以连续 `audio_id` segment 为最小标注单位，服务端入库也假定 60 秒
         * （见根仓库 AGENTS.md §3）。原来设置项允许 10–600 秒，任何非 60 秒的采集
         * 都会产出无法进入现有标注模型的数据，而参与者不会知道自己改坏了什么。
         * 要支持其他时长，必须先引入显式的 schema/version 兼容，而不是放开一个输入框。
         */
        fun from(settings: UserSettings) = RecordingOptions(
            segmentEnabled = true,
            segmentDurationMs = SessionPaths.SEGMENT_DURATION_MS,
            autoVadOnRecord = settings.autoVadOnRecord
        )
    }
}
