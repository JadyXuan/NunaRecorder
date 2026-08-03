package com.example.nunarecorder.recording

import com.example.nunarecorder.ble.OpusStreamAssembler
import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `feed()` 跑在 BLE 回调线程（50 次/秒，刻意不绕主线程），`tick()` 和 `liveStats()`
 * 跑在服务的主线程 Handler 上。两边都会动 `writer` / `currentSegmentIndex` /
 * `accumulator`，所以 [SessionRecorder] 的对外方法都加了锁。
 *
 * **这个用例是并发冒烟测试，不是竞态检测器。** 实测过：把 `@Synchronized` 全部去掉，
 * 它仍然连过三轮——竞争窗口太窄，靠跑测试碰不出来。所以线程安全的依据是加锁本身，
 * 不是这个用例通过了。
 *
 * 它真正保证的是：并发负载下字节守恒、分段序号不重复不回退、不抛异常。
 * 这能挡住「轮转逻辑被改坏」这类粗粒度回归，值得留着，但别把它当成竞态的证明。
 */
class SessionRecorderConcurrencyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun audioMessage(frameId: Int): ByteArray {
        val opus = ByteArray(OpusStreamAssembler.OPUS_FRAME_SIZE) { it.toByte() }
        val payload = ByteArray(14 + opus.size)
        payload[0] = (frameId and 0xFF).toByte()
        payload[1] = ((frameId shr 8) and 0xFF).toByte()
        payload[4] = 0
        payload[5] = 1
        opus.copyInto(payload, 14)
        val msg = ByteArray(7 + payload.size)
        msg[0] = 0xAA.toByte()
        msg[1] = 0x10
        msg[2] = (payload.size and 0xFF).toByte()
        msg[3] = ((payload.size shr 8) and 0xFF).toByte()
        msg[4] = 1
        payload.copyInto(msg, 7)
        return msg
    }

    @Test(timeout = 30_000)
    fun `feed 与 tick 并发时不丢字节也不抛异常`() {
        val dir = temp.newFolder("nuna_TEST_1800000000000")
        // 真墙钟：让 tick 线程有机会真的触发轮转
        val recorder = SessionRecorder(onLog = {})
        val options = RecordingOptions(
            segmentEnabled = true,
            // 20ms 一段：整个测试期间几乎一直在轮转，最大化边界上的竞争
            segmentDurationMs = 20L,
            autoVadOnRecord = false
        )
        recorder.start(dir, "dev", "AA:BB", options)

        val frameCount = 2_000
        val failure = AtomicReference<Throwable?>(null)
        val feeding = java.util.concurrent.atomic.AtomicBoolean(true)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        val feeder = Thread {
            try {
                start.await()
                repeat(frameCount) { recorder.feed(audioMessage(it)) }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                feeding.set(false)
                done.countDown()
            }
        }
        // 不 sleep：全速自旋，把和 feed 的重叠窗口开到最大。
        // 加了 sleep 的版本在没有同步时也能碰巧跑过，那样的用例等于没写。
        val ticker = Thread {
            try {
                start.await()
                while (feeding.get()) {
                    recorder.tick()
                    recorder.liveStats()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }

        feeder.start()
        ticker.start()
        start.countDown()
        assertTrue("线程未在超时内结束", done.await(20, TimeUnit.SECONDS))
        recorder.stop()

        failure.get()?.let { throw AssertionError("并发访问抛异常", it) }

        val manifest = SessionManifest.load(SessionPaths.manifestFile(dir))!!
        val writtenBytes = manifest.segments.sumOf { it.bytes }
        assertEquals(
            "所有帧都必须落到某个分段里，一个字节都不能在轮转边界上蒸发",
            frameCount.toLong() * OpusStreamAssembler.OPUS_FRAME_SIZE,
            writtenBytes
        )

        val received = manifest.segments.sumOf { it.frames?.receivedPackets ?: 0 }
        assertEquals(frameCount, received)

        val indices = manifest.segments.map { it.index }
        assertEquals("分段序号不得重复", indices.size, indices.toSet().size)
        assertEquals("分段序号必须递增", indices.sorted(), indices)
    }
}
