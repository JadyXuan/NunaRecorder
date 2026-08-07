package com.example.nunarecorder

import android.app.Application
import com.example.nunarecorder.service.ScreenOffKeepAlive

class NunaApplication : Application() {

    /**
     * 删掉旧的通知渠道。
     *
     * VAD 渠道原来是 `IMPORTANCE_DEFAULT`——**会出声**，而 VAD 服务在每个分段封口时
     * 启动一次，也就是采集期间**每 60 秒响一下**。佩戴者报告的"间歇性提示音"就是它。
     *
     * Android 不允许 App 在渠道创建后修改 importance（只有用户能改），所以只能换新 ID；
     * 旧 ID 不删的话会一直躺在系统的通知设置里，看起来像有两套设置。
     */
    private fun dropLegacyNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val mgr = getSystemService(android.app.NotificationManager::class.java) ?: return
        listOf("nuna_vad_channel", "nuna_migration_channel", "nuna_context_channel").forEach {
            runCatching { mgr.deleteNotificationChannel(it) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        dropLegacyNotificationChannels()
        ScreenOffKeepAlive.register(this)
    }
}
