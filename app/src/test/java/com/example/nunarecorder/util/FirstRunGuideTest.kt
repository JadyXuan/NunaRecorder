package com.example.nunarecorder.util

import com.example.nunarecorder.data.PairedDevice
import com.example.nunarecorder.enroll.EnrollmentCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 顺序不能错：没入组就先入组（否则采了也传不上去），没设备就先配设备——
 * 声纹走的是同一条 BLE 音频流，没连上设备一个字节都收不到，
 * 先提示录声纹只会让人对着一个永远"太短"的界面发呆。
 */
class FirstRunGuideTest {

    private val code = EnrollmentCode(
        serverUrl = "https://example.invalid",
        participantId = "p001",
        token = "t"
    )
    private val device = PairedDevice(name = "Nuna", address = "AA:BB:CC:DD:EE:FF", lastConnectedTime = 0L)

    @Test
    fun `没入组先入组`() {
        assertEquals(
            FirstRunGuide.Step.ENROLL,
            FirstRunGuide.nextStep(null, listOf(device), hasVoiceprint = true)
        )
    }

    @Test
    fun `入组了但没设备，先配设备`() {
        assertEquals(
            FirstRunGuide.Step.PAIR_DEVICE,
            FirstRunGuide.nextStep(code, emptyList(), hasVoiceprint = false)
        )
    }

    @Test
    fun `设备有了才提示录声纹`() {
        assertEquals(
            FirstRunGuide.Step.VOICEPRINT,
            FirstRunGuide.nextStep(code, listOf(device), hasVoiceprint = false)
        )
    }

    @Test
    fun `都齐了就不再打扰`() {
        assertEquals(
            FirstRunGuide.Step.READY,
            FirstRunGuide.nextStep(code, listOf(device), hasVoiceprint = true)
        )
        assertNull(FirstRunGuide.adviceFor(FirstRunGuide.Step.READY))
    }

    @Test
    fun `每一步都要说清楚为什么和去哪做`() {
        for (step in FirstRunGuide.Step.entries) {
            if (step == FirstRunGuide.Step.READY) continue
            val a = FirstRunGuide.adviceFor(step)
            assertTrue("$step 缺引导", a != null)
            assertEquals(step, a!!.step)
            assertTrue(a.title.isNotBlank())
            assertTrue("$step 只说做什么不说为什么", a.body.length > 15)
            assertTrue(a.action.isNotBlank())
        }
    }
}
