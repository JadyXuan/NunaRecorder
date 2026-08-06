package com.example.nunarecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UserSettingsTest {

    @Test
    fun defaultsToDeployedHttpsService() {
        val settings = UserSettings()

        assertEquals("pilot-random-id", settings.userId)
        assertEquals("https://lifelog.transfur.tech/", settings.baseUrl)
        assertEquals("https://lifelog.transfur.tech", settings.apiBaseUrl())
    }

    @Test
    fun normalizesWhitespaceAndTrailingSlashesForRequests() {
        val settings = UserSettings(baseUrl = "  https://example.com/lifelog///  ")

        assertEquals("https://example.com/lifelog", settings.apiBaseUrl())
    }
}
