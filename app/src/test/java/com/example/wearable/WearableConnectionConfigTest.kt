package com.example.wearable

import org.junit.Assert.assertNull
import org.junit.Test

class WearableConnectionConfigTest {

    @Test
    fun doesNotEmbedAThirdPartyTranscriptionCredential() {
        val config = WearableConnectionConfig(deviceAddress = "AA:BB:CC:DD:EE:FF")

        assertNull(config.deepgramApiKey)
    }
}
