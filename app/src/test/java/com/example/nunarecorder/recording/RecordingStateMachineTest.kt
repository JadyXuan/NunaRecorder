package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateMachineTest {

    private fun started(): RecordingStateMachine = RecordingStateMachine().apply {
        onStartRequested("nuna_device_073C", "4C:FF:01:A0:07:3C")
        onGattConnected()
        onAudioSubscribed()
    }

    @Test
    fun `正常流程走到 RECORDING`() {
        val m = started()
        assertEquals(LinkPhase.RECORDING, m.status.phase)
        assertTrue(m.status.isStreaming)
        assertTrue(m.status.isSessionActive)
    }

    /** 回归：佩戴者按下停止后 UI 一直显示"录制中"。 */
    @Test
    fun `停止后不再是录制中`() {
        val m = started()
        m.onStopRequested()

        assertEquals(LinkPhase.IDLE, m.status.phase)
        assertFalse(m.status.isStreaming)
        assertFalse(m.status.isSessionActive)
    }

    /**
     * 停止时会主动 `gatt.disconnect()`，随后 GATT 回调才异步到达。
     * 如果这个迟到的回调被当成"意外断连"，状态就会被拉回 RECONNECTING，
     * 服务开始重连，UI 又变回录制中——正是原来那个卡死状态的成因。
     */
    @Test
    fun `停止之后迟到的断连回调不会重新拉起会话`() {
        val m = started()
        m.onStopRequested()

        m.onDisconnected("gatt_disconnected(status=0)")
        assertEquals(LinkPhase.IDLE, m.status.phase)

        m.onReconnectScheduled(attempt = 1, delayMs = 1000)
        assertEquals(LinkPhase.IDLE, m.status.phase)

        // 更极端：连接甚至订阅回调也迟到了
        m.onGattConnected()
        m.onAudioSubscribed()
        assertEquals(LinkPhase.IDLE, m.status.phase)
        assertFalse(m.status.isStreaming)
    }

    @Test
    fun `断连进入重连而不是结束会话`() {
        val m = started()
        m.onDisconnected("out_of_range")

        assertEquals(LinkPhase.RECONNECTING, m.status.phase)
        assertTrue("会话必须保持打开，重连后续写同一个会话", m.status.isSessionActive)
        assertFalse("重连期间不能显示成正在收音频", m.status.isStreaming)
        assertEquals("out_of_range", m.status.reason)
    }

    @Test
    fun `重连成功后计数归零并回到录制`() {
        val m = started()
        m.onDisconnected("bluetooth_off")
        m.onReconnectScheduled(attempt = 3, delayMs = 4000)
        assertEquals(3, m.status.reconnectAttempt)

        m.onReconnecting()
        assertEquals(LinkPhase.CONNECTING, m.status.phase)

        m.onGattConnected()
        m.onAudioSubscribed()
        assertEquals(LinkPhase.RECORDING, m.status.phase)
        assertEquals(0, m.status.reconnectAttempt)
        assertEquals(0L, m.status.nextRetryInMs)
    }

    @Test
    fun `没开始录制时的回调不会凭空进入录制状态`() {
        val m = RecordingStateMachine()
        m.onGattConnected()
        m.onAudioSubscribed()
        m.onDisconnected("noise")

        assertEquals(LinkPhase.IDLE, m.status.phase)
    }

    @Test
    fun `重新开始会清掉上一次的停止标记`() {
        val m = started()
        m.onStopRequested()
        m.onStartRequested("nuna_device_073C", "4C:FF:01:A0:07:3C")

        assertFalse(m.status.stopRequested)
        assertEquals(LinkPhase.CONNECTING, m.status.phase)

        m.onGattConnected()
        m.onAudioSubscribed()
        assertEquals(LinkPhase.RECORDING, m.status.phase)
    }
}

class ReconnectPolicyTest {

    @Test
    fun `退避指数增长并封顶`() {
        val p = ReconnectPolicy(initialDelayMs = 1_000, maxDelayMs = 30_000, multiplier = 2.0)

        assertEquals(1_000L, p.delayForAttempt(1))
        assertEquals(2_000L, p.delayForAttempt(2))
        assertEquals(4_000L, p.delayForAttempt(3))
        assertEquals(8_000L, p.delayForAttempt(4))
        assertEquals(16_000L, p.delayForAttempt(5))
        assertEquals(30_000L, p.delayForAttempt(6))
    }

    /** 16 小时佩戴：设备关机一整夜也不能让退避溢出或放弃。 */
    @Test
    fun `第一千次重试仍然是上限值`() {
        val p = ReconnectPolicy()
        assertEquals(30_000L, p.delayForAttempt(1_000))
        assertEquals(30_000L, p.delayForAttempt(Int.MAX_VALUE))
    }

    @Test
    fun `非法的次数按首次处理`() {
        val p = ReconnectPolicy(initialDelayMs = 1_500)
        assertEquals(1_500L, p.delayForAttempt(0))
        assertEquals(1_500L, p.delayForAttempt(-5))
    }
}
