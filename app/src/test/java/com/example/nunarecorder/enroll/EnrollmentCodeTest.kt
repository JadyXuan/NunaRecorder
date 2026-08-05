package com.example.nunarecorder.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入组码是参与者配置的唯一入口，坏码必须**在扫码那一刻**被拒绝并说清原因——
 * 一旦放过去，要到上传时才报 401，而那时参与者已经离开现场了。
 */
class EnrollmentCodeTest {

    private val server = "https://nuna-audio-lab-data.cuhkaiot.com"
    private val pid = "p001"
    private val token = "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdo"

    private fun sample(
        webUrl: String? = null,
        login: LoginHint? = null
    ) = EnrollmentCode(server, pid, token, webUrl, login)

    private fun ok(raw: String): EnrollmentCode {
        val r = EnrollmentCodec.parse(raw)
        assertTrue("期望解析成功，实际：$r", r is EnrollmentParseResult.Ok)
        return (r as EnrollmentParseResult.Ok).code
    }

    private fun err(raw: String): String {
        val r = EnrollmentCodec.parse(raw)
        assertTrue("期望解析失败，实际成功了", r is EnrollmentParseResult.Error)
        return (r as EnrollmentParseResult.Error).reason
    }

    @Test
    fun `最小字段往返`() {
        val code = ok(EnrollmentCodec.encode(sample()))
        assertEquals(server, code.serverUrl)
        assertEquals(pid, code.participantId)
        assertEquals(token, code.token)
        assertNull(code.webUrl)
        assertNull(code.login)
    }

    @Test
    fun `带网站和登录指引的往返`() {
        val login = LoginHint("p001", "Xk7mQ2pR", "guest-egoaudio", "GuestPass1")
        val code = ok(
            EnrollmentCodec.encode(
                sample(webUrl = "https://nuna-audio-lab.cuhkaiot.com", login = login)
            )
        )
        assertEquals("https://nuna-audio-lab.cuhkaiot.com", code.webUrl)
        assertEquals(login, code.login)
    }

    /** QR 载荷有大小上限，装不下时这两项会缺；缺了也要能正常采集上传。 */
    @Test
    fun `缺少可选字段时降级但仍可用`() {
        val code = ok(EnrollmentCodec.encode(sample()))
        assertNotNull(code.serverUrl)
        assertNull("没有网站地址不应导致解析失败", code.webUrl)
        assertNull(code.login)
    }

    @Test
    fun `login 缺账号或密码时整体丢弃而不是给出半个`() {
        val raw = EnrollmentCodec.encode(sample())
        val json = org.json.JSONObject(
            String(EnrollmentCodec.base64UrlDecode(raw.removePrefix(EnrollmentCodec.PREFIX)))
        )
        json.put("login", org.json.JSONObject().apply { put("u", "p001") }) // 只有账号
        val patched = EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())

        assertNull("半个登录信息比没有更容易误导", ok(patched).login)
    }

    // ── 畸形输入 ──────────────────────────────────────────────────────────

    @Test
    fun `空串和空白`() {
        assertTrue(err("").contains("为空"))
        assertTrue(err("   ").contains("为空"))
    }

    @Test
    fun `扫到别的二维码时说清楚不是我们的码`() {
        val reason = err("https://example.com/some-other-qr")
        assertTrue(reason, reason.contains("不是 EgoAudio"))
    }

    @Test
    fun `base64 损坏`() {
        assertTrue(err("EGOAUDIO1:!!!!not-base64!!!!").contains("损坏"))
    }

    @Test
    fun `不是 JSON`() {
        val junk = EnrollmentCodec.PREFIX + EnrollmentCodec.base64UrlEncode("hello".toByteArray())
        assertTrue(err(junk).contains("损坏"))
    }

    /** 版本不认识要提示更新 App，而不是让研究员反复重扫同一张卡。 */
    @Test
    fun `未来版本提示更新 App`() {
        val json = sample().toJson().put("v", 99)
        val raw = EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())
        val reason = err(raw)
        assertTrue(reason, reason.contains("更新 App"))
    }

    /** 核心用例：抄错一个字符必须当场被拒，而不是到上传时才 401。 */
    @Test
    fun `令牌被改动会被校验位拦下`() {
        val json = sample().toJson()
        json.put("token", token.dropLast(1) + "X") // sum 还是旧的
        val raw = EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())
        val reason = err(raw)
        assertTrue(reason, reason.contains("校验失败"))
    }

    @Test
    fun `服务器地址被改动也会被拦下`() {
        val json = sample().toJson()
        json.put("server", "https://evil.example.com")
        val raw = EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())
        assertTrue(err(raw).contains("校验失败"))
    }

    @Test
    fun `缺字段分别报出缺了哪一项`() {
        for ((key, expect) in listOf("server" to "服务器地址", "pid" to "参与者编号", "token" to "上传令牌")) {
            val json = sample().toJson()
            json.remove(key)
            val raw = EnrollmentCodec.PREFIX +
                EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())
            val reason = err(raw)
            assertTrue("缺 $key 时应提示「$expect」，实际：$reason", reason.contains(expect))
        }
    }

    @Test
    fun `非 http 协议被拒`() {
        val bad = EnrollmentCode("ftp://x.example.com", pid, token)
        val json = bad.toJson()
            .put("sum", EnrollmentCodec.checksum("ftp://x.example.com", pid, token))
        val raw = EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())
        assertTrue(err(raw).contains("http"))
    }

    @Test
    fun `参与者编号含非法字符被拒`() {
        val badPid = "p001/../etc"
        val json = EnrollmentCode(server, badPid, token).toJson()
            .put("sum", EnrollmentCodec.checksum(server, badPid, token))
        val raw = EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray())
        assertTrue(err(raw).contains("不允许的字符"))
    }

    @Test
    fun `尾部斜杠和前后空白被规范化`() {
        val json = sample().toJson().put("server", "$server/")
        json.put("sum", EnrollmentCodec.checksum("$server/", pid, token))
        val raw = "  " + EnrollmentCodec.PREFIX +
            EnrollmentCodec.base64UrlEncode(json.toString().toByteArray()) + "\n"
        assertEquals(server, ok(raw).serverUrl)
    }

    /** 研究员可能从别处复制到标准 base64（带 +/ 和 =），也要能用。 */
    @Test
    fun `容忍标准 base64 字符集与 padding`() {
        val raw = EnrollmentCodec.encode(sample())
        val payload = raw.removePrefix(EnrollmentCodec.PREFIX)
        val standard = payload.replace('-', '+').replace('_', '/') + "=="
        assertEquals(pid, ok(EnrollmentCodec.PREFIX + standard).participantId)
    }

    @Test
    fun `令牌指纹只暴露前四位`() {
        assertEquals(4, sample().tokenFingerprint.length)
        assertTrue(token.startsWith(sample().tokenFingerprint))
    }

    /**
     * platform 的二维码编码器上限是 213 字节载荷。把实际长度算出来，
     * 超了就必须在工单里提出，而不是等现场扫不出来。
     */
    @Test
    fun `记录各种字段组合下的入组码长度`() {
        val minimal = EnrollmentCodec.encode(sample()).length
        val withWeb = EnrollmentCodec.encode(
            sample(webUrl = "https://nuna-audio-lab.cuhkaiot.com")
        ).length
        val full = EnrollmentCodec.encode(
            sample(
                webUrl = "https://nuna-audio-lab.cuhkaiot.com",
                login = LoginHint("p001", "Xk7mQ2pR", "guest-egoaudio", "GuestPass1")
            )
        ).length

        println("入组码长度：最小 $minimal / 带网站 $withWeb / 全字段 $full （QR 上限 213）")
        assertTrue("最小形态必须能进二维码，实际 $minimal", minimal <= 213)
    }
}
