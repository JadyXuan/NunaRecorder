package com.example.nunarecorder.enroll

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败原因的措辞本身就是功能：入组现场研究员只看得到这一句话，
 * 它决定他是当场重发一张入组码，还是让参与者带着一台配不全的机器走。
 */
class EnrollmentConfigClientTest {

    @Test
    fun `403 要指向共享令牌并给出可执行的下一步`() {
        val msg = EnrollmentConfigClient.describeFailure(403)
        assertTrue("要点明是令牌被拒", msg.contains("令牌"))
        assertTrue("要提到共享令牌这个最可能的原因", msg.contains("共享令牌"))
        assertTrue("要给出下一步动作", msg.contains("重新生成"))
        assertTrue("要带状态码，方便排障时对日志", msg.contains("403"))
    }

    @Test
    fun `401 与 403 同一类，都归因到令牌而不是服务端没配`() {
        val msg = EnrollmentConfigClient.describeFailure(401)
        assertTrue(msg.contains("令牌"))
        // 这是 2026-08-09 那次误判的核心：把令牌问题说成服务端没配置，
        // 于是我去催 platform 填三个其实已经有值的环境变量。
        assertFalse("不得暗示是服务端没配置", msg.contains("未配置"))
    }

    @Test
    fun `404 指向服务端版本而不是令牌`() {
        val msg = EnrollmentConfigClient.describeFailure(404)
        assertTrue(msg.contains("接口"))
        assertFalse("404 与令牌无关，别误导研究员去重发入组码", msg.contains("令牌"))
    }

    @Test
    fun `5xx 说明可重试，不要让人以为要重新入组`() {
        val msg = EnrollmentConfigClient.describeFailure(503)
        assertTrue(msg.contains("503"))
        assertTrue("要告诉用户可以重试", msg.contains("重试"))
        assertFalse(msg.contains("令牌"))
    }

    @Test
    fun `未预料的状态码也要带上码值而不是一句拉取失败`() {
        val msg = EnrollmentConfigClient.describeFailure(418)
        assertTrue(msg.contains("418"))
    }
}
