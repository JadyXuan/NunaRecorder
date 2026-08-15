package com.example.nunarecorder.web

import com.example.nunarecorder.enroll.EnrollmentCode
import com.example.nunarecorder.enroll.LoginHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置浏览器的自动填充。
 *
 * **这里锁的是安全边界**：一次填错就是把网关口令交给别人的站点，
 * 而参与者在浏览器里点到站外是随时会发生的。
 */
class LoginAutofillTest {

    private val enrollment = EnrollmentCode(
        serverUrl = "https://nuna-audio-lab-data.cuhkaiot.com",
        participantId = "test-P01",
        token = "tok",
        webUrl = "https://nuna-audio-lab.cuhkaiot.com",
        login = LoginHint(
            username = "test-P01", password = "pw-participant",
            ssoUsername = "guest", ssoPassword = "pw-sso"
        )
    )

    @Test
    fun `标注站填参与者账号，SSO 门户填网关账号`() {
        assertEquals(
            LoginAutofill.Target.PARTICIPANT,
            LoginAutofill.targetFor("nuna-audio-lab.cuhkaiot.com", "nuna-audio-lab.cuhkaiot.com")
        )
        assertEquals(
            LoginAutofill.Target.SSO,
            LoginAutofill.targetFor("sso-portal-hk51.cuhkaiot.com", "nuna-audio-lab.cuhkaiot.com")
        )
    }

    @Test
    fun `站外一律不填`() {
        val web = "nuna-audio-lab.cuhkaiot.com"
        listOf(
            "example.com",
            "google.com",
            // 上传站不是标注站，它也没有 SSO——不该拿到任何一套
            "nuna-audio-lab-data.cuhkaiot.com",
            "",
            null
        ).forEach {
            assertEquals("主机 $it 不该被填充", LoginAutofill.Target.NONE, LoginAutofill.targetFor(it, web))
        }
    }

    @Test
    fun `仿冒主机不能通过`() {
        val web = "nuna-audio-lab.cuhkaiot.com"
        // 只做后缀匹配的话，攻击者注册这个域名就能拿到网关口令
        assertEquals(
            LoginAutofill.Target.NONE,
            LoginAutofill.targetFor("sso-portal-hk51.cuhkaiot.com.evil.example", web)
        )
        // 只做前缀匹配同理
        assertEquals(
            LoginAutofill.Target.NONE,
            LoginAutofill.targetFor("sso-portal-hk51.evil.example", web)
        )
        // 子域不是标注站本身
        assertEquals(
            LoginAutofill.Target.NONE,
            LoginAutofill.targetFor("evil.nuna-audio-lab.cuhkaiot.com", web)
        )
    }

    @Test
    fun `主机名大小写不敏感`() {
        assertEquals(
            LoginAutofill.Target.PARTICIPANT,
            LoginAutofill.targetFor("NUNA-Audio-Lab.CUHKAIOT.com", "nuna-audio-lab.cuhkaiot.com")
        )
    }

    @Test
    fun `入组码没有 SSO 账号时不编一个出来`() {
        // 内网直连不过网关的入组码是合法的
        val noSso = enrollment.copy(
            login = LoginHint(username = "u", password = "p")
        )
        assertNull(LoginAutofill.credentialsFor(noSso, LoginAutofill.Target.SSO))
        assertEquals("u" to "p", LoginAutofill.credentialsFor(noSso, LoginAutofill.Target.PARTICIPANT))
    }

    @Test
    fun `入组码没带密码时不要填一个空串`() {
        // 服务端只存哈希，密码可能取不回来（印在入组卡上）。
        // 填空串会让参与者以为"填过了但没生效"，比不填更糟。
        // 用户 2026-08-16 实测："UserID 自动填好了，但是密码还是没有"。
        val noPw = enrollment.copy(login = LoginHint(username = "u", password = ""))
        assertNull(LoginAutofill.credentialsFor(noPw, LoginAutofill.Target.PARTICIPANT))
    }

    @Test
    fun `分步登录页要靠观察 DOM 继续填，不能只跑一次`() {
        // Authentik 先账号后密码，而且不整页跳转，onPageFinished 只触发一次。
        // 只跑一次的话密码框永远等不到人来填。
        val js = LoginAutofill.fillScript("u", "p", submit = true)
        assertTrue("要有 MutationObserver", js.contains("MutationObserver"))
        assertTrue("要能穿 shadow DOM", js.contains("shadowRoot"))
        assertTrue("重复注入要复用而不是重新武装", js.contains("__egoFill"))
    }

    @Test
    fun `NONE 永远拿不到凭据`() {
        assertNull(LoginAutofill.credentialsFor(enrollment, LoginAutofill.Target.NONE))
    }

    @Test
    fun `同一主机只自动提交一次`() {
        // 凭据错了还一直自动提交，就是一个无限重试循环，而网关对连续失败是锁账号的——
        // 30 个参与者共用同一个访客账号，锁一次所有人都进不去。
        val tried = mutableSetOf<String>()
        assertTrue(LoginAutofill.shouldAutoSubmit("sso.example", tried))
        tried.add("sso.example")
        assertFalse(LoginAutofill.shouldAutoSubmit("sso.example", tried))
        assertTrue("换个主机还是可以自动提交一次", LoginAutofill.shouldAutoSubmit("other.example", tried))
    }

    @Test
    fun `注入脚本要转义，密码里的引号不能把脚本弄断`() {
        val js = LoginAutofill.fillScript("u\"x", "p'\\\"y</script>", submit = false)
        assertTrue(js.contains("\\\""))
        assertFalse("原样拼进去会造成注入", js.contains("var u = \"u\"x\""))
    }

    @Test
    fun `不碰一次性验证码字段`() {
        // name=code 是 TOTP，我们没有；往里填任何东西只会让登录失败
        val js = LoginAutofill.fillScript("u", "p", submit = true)
        assertFalse(js.contains("name=code"))
    }
}
