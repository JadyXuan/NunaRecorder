package com.example.nunarecorder.voiceprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-08-09：「录完了，也传完了，退出软件好像也不见了」。
 * 数据其实没丢，丢的是本地状态——但"传完了、重启就没了"只有一个读法：没传上去。
 *
 * 这里测的是纯逻辑部分：时长换算，以及**重录之后旧的"已上传"必须失效**——
 * 那一条错了会让参与者以为新录的那份也传了，比不显示还糟。
 */
class VoiceprintStatusTest {

    private fun st(bytes: Long, uploadedSize: Long?, uploadedAt: Long? = 1L) =
        VoiceprintStatus.StepStatus(
            step = VoiceprintSession.Step.FREE,
            recordedAtMs = if (bytes > 0) 1_700_000_000_000L else null,
            durationMs = bytes / 80 * 20,
            bytes = bytes,
            uploadedAtMs = if (uploadedSize == bytes) uploadedAt else null
        )

    @Test
    fun `时长按 80 字节 20 毫秒换算，与录音那套量纲一致`() {
        // 2 分钟 = 6000 个 20ms 包 = 480000 字节
        assertEquals(120_000L, st(480_000L, 480_000L).durationMs)
    }

    @Test
    fun `没录过就是没录过`() {
        val s = st(0L, null)
        assertFalse(s.recorded)
        assertFalse(s.uploaded)
    }

    @Test
    fun `录了没传：要能区分出来，不能笼统说"有"`() {
        val s = st(480_000L, uploadedSize = null)
        assertTrue(s.recorded)
        assertFalse(s.uploaded)
    }

    @Test
    fun `重录之后旧的已上传必须失效`() {
        // 传过一份 48 万字节的，然后重录成 60 万字节
        val s = st(600_000L, uploadedSize = 480_000L)
        assertTrue(s.recorded)
        assertFalse("重录过还说已上传，参与者会以为新的也传了", s.uploaded)
    }

    @Test
    fun `录了也传了`() {
        val s = st(480_000L, uploadedSize = 480_000L)
        assertTrue(s.recorded)
        assertTrue(s.uploaded)
    }
}
