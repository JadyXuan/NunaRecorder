package com.example.nunarecorder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserSettingsTest {

    @Test
    fun defaultsToNoServerAndNoBackgroundNetworking() {
        val settings = UserSettings()

        assertEquals("", settings.userId)
        assertEquals("", settings.baseUrl)
        assertEquals("", settings.apiBaseUrl())
        assertFalse(settings.lifelogEnabled)
        assertFalse(settings.annotationPollingEnabled)
        assertFalse(settings.autoUploadEnabled)
        assertFalse(settings.hasValidServerUrl())
        assertEquals("请先在设置中填写服务器 Base URL", settings.serverConfigurationError())
    }

    @Test
    fun normalizesWhitespaceAndTrailingSlashesForRequests() {
        val settings = UserSettings(baseUrl = "  https://example.com/lifelog///  ")

        assertEquals("https://example.com/lifelog", settings.apiBaseUrl())
        assertTrue(settings.hasValidServerUrl())
    }

    @Test
    fun requiresValidHttpServerAndUserId() {
        assertFalse(UserSettings(baseUrl = "example.com").hasValidServerUrl())
        assertEquals(
            "服务器 Base URL 必须是有效的 http:// 或 https:// 地址",
            UserSettings(baseUrl = "example.com").serverConfigurationError()
        )
        assertEquals(
            "请先在设置中填写用户 ID",
            UserSettings(baseUrl = "https://example.com").serverConfigurationError()
        )
        assertNull(
            UserSettings(
                baseUrl = "https://example.com/api/",
                userId = "user-1"
            ).serverConfigurationError()
        )
    }

    @Test
    fun clearsOnlyTheFormerImplicitPilotUrlDuringMigration() {
        assertEquals(
            "",
            UserSettingsStorage.migrateBaseUrl("https://lifelog.transfur.tech/")
        )
        assertEquals(
            "https://self-hosted.example/api",
            UserSettingsStorage.migrateBaseUrl(" https://self-hosted.example/api ")
        )
    }
}
