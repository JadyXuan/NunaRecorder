package com.example.nunarecorder.enroll

import org.json.JSONObject
import java.security.MessageDigest

/**
 * 参与者登录网站需要的凭据。**只用于在 App 里显示给参与者看**，不参与任何认证。
 *
 * App 不做 SSO——上传站按设计没有 SSO，SSO 只有参与者用浏览器去标注时才会遇到。
 */
data class LoginHint(
    /** 参与者自己的账号（= participant_id） */
    val username: String,
    /** 参与者自己的一次性密码 */
    val password: String,
    /** 网关 SSO 账号，全体共用 */
    val ssoUsername: String? = null,
    val ssoPassword: String? = null
)

/**
 * 入组码：参与者扫一次二维码（或粘贴文本码）就配置好一切。
 *
 * 格式 `EGOAUDIO1:<base64url(紧凑 JSON，无 padding)>`。
 *
 * 手填 `userId` 和手填令牌在 2026-07-31 和 08-03 两次实测都出过问题——配置错了要采完
 * 一整天才发现。删掉输入框比在旁边加提示有效，所以 App 里**不保留任何服务器默认值**：
 * 没扫过码就没有可用配置，而不是悄悄退回某个内置地址。
 */
data class EnrollmentCode(
    /** 上传站完整 URL，含 scheme，无尾斜杠。上了 HTTPS 域名之后没有"端口"这个概念 */
    val serverUrl: String,
    val participantId: String,
    val token: String,
    /** 标注网站完整 URL；可缺省 */
    val webUrl: String? = null,
    /** 登录指引；可缺省 */
    val login: LoginHint? = null
) {
    /** 日志和界面里只暴露前 4 位，足够定位是哪张卡，又不泄露令牌 */
    val tokenFingerprint: String get() = token.take(4)

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", EnrollmentCodec.VERSION)
        put("server", serverUrl)
        put("pid", participantId)
        put("token", token)
        put("sum", EnrollmentCodec.checksum(serverUrl, participantId, token))
        webUrl?.let { put("web", it) }
        login?.let {
            put("login", JSONObject().apply {
                put("u", it.username)
                put("p", it.password)
                it.ssoUsername?.let { v -> put("su", v) }
                it.ssoPassword?.let { v -> put("sp", v) }
            })
        }
    }
}

sealed class EnrollmentParseResult {
    data class Ok(val code: EnrollmentCode) : EnrollmentParseResult()

    /** [reason] 是给研究员看的具体原因，不是"格式错误"这种没用的话 */
    data class Error(val reason: String) : EnrollmentParseResult()
}

/**
 * 入组码编解码。纯 Kotlin（只依赖 `org.json`），可跑 JVM 单测。
 */
object EnrollmentCodec {

    const val PREFIX = "EGOAUDIO1:"
    const val VERSION = 1

    /** 与服务端 `SAFE_ID_RE` 一致 */
    private val PID_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    /**
     * 校验位不是装饰：二维码扫不出来时研究员会改用手抄/粘贴，抄错一个字符会得到一个
     * **格式合法但令牌错误**的配置，要到上传时才报 401——而那时参与者已经走了。
     * 有校验位就能在扫码那一刻拒绝。
     */
    fun checksum(serverUrl: String, participantId: String, token: String): String {
        val material = "$VERSION|$serverUrl|$participantId|$token"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }

    fun encode(code: EnrollmentCode): String =
        PREFIX + base64UrlEncode(code.toJson().toString().toByteArray(Charsets.UTF_8))

    fun parse(raw: String?): EnrollmentParseResult {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return EnrollmentParseResult.Error("入组码为空")
        if (!text.startsWith(PREFIX)) {
            return EnrollmentParseResult.Error("这不是 EgoAudio 的入组码（缺少 $PREFIX 前缀）")
        }

        val payload = text.removePrefix(PREFIX).trim()
        val bytes = try {
            base64UrlDecode(payload)
        } catch (_: Exception) {
            return EnrollmentParseResult.Error("入组码内容损坏，可能复制时缺了字符")
        }

        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            return EnrollmentParseResult.Error("入组码内容损坏，无法解析")
        }

        val version = json.optInt("v", -1)
        if (version != VERSION) {
            // 明确区分"码坏了"和"App 太旧"，否则研究员会一直重扫同一张卡
            return EnrollmentParseResult.Error("入组码版本是 $version，这个 App 只认识 $VERSION，请更新 App")
        }

        // 校验位必须按 JSON 里的**原样**算：服务端是对它发出的字符串做的哈希，
        // 先规范化再校验就会和服务端对不上（尾斜杠是最容易踩的一种）。
        val rawServer = json.optString("server").trim()
        val serverUrl = rawServer.trimEnd('/')
        val pid = json.optString("pid").trim()
        val token = json.optString("token").trim()
        val sum = json.optString("sum").trim().lowercase()

        if (rawServer.isEmpty()) return EnrollmentParseResult.Error("入组码里没有服务器地址")
        if (pid.isEmpty()) return EnrollmentParseResult.Error("入组码里没有参与者编号")
        if (token.isEmpty()) return EnrollmentParseResult.Error("入组码里没有上传令牌")
        if (sum.isEmpty()) return EnrollmentParseResult.Error("入组码里没有校验位")

        if (sum != checksum(rawServer, pid, token)) {
            return EnrollmentParseResult.Error("入组码校验失败，内容可能被改动或抄错了，请重新扫码")
        }
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            return EnrollmentParseResult.Error("服务器地址必须以 http:// 或 https:// 开头")
        }
        if (!PID_RE.matches(pid)) {
            return EnrollmentParseResult.Error("参与者编号含有不允许的字符：$pid")
        }

        // web / login 是可选的：QR 载荷有大小上限，装不下时这两项会缺，
        // 此时 App 仍然可以正常采集上传，只是"怎么登录网站"页面显示不全。
        val webUrl = json.optString("web").trim().trimEnd('/').takeIf { it.isNotEmpty() }
        val login = json.optJSONObject("login")?.let { o ->
            val u = o.optString("u").trim()
            val p = o.optString("p").trim()
            if (u.isEmpty() || p.isEmpty()) null
            else LoginHint(
                username = u,
                password = p,
                ssoUsername = o.optString("su").trim().takeIf { it.isNotEmpty() },
                ssoPassword = o.optString("sp").trim().takeIf { it.isNotEmpty() }
            )
        }

        return EnrollmentParseResult.Ok(
            EnrollmentCode(
                serverUrl = serverUrl,
                participantId = pid,
                token = token,
                webUrl = webUrl,
                login = login
            )
        )
    }

    // 不用 android.util.Base64，保持这个文件可跑 JVM 单测
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun base64UrlEncode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                .append(ALPHABET[(n shr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n shr 18) and 63]).append(ALPHABET[(n shr 12) and 63])
                    .append(ALPHABET[(n shr 6) and 63])
            }
        }
        return out.toString()
    }

    fun base64UrlDecode(text: String): ByteArray {
        // 容忍标准 base64 的 +/ 和 padding：研究员可能从别处复制
        val cleaned = text.replace('+', '-').replace('/', '_').trimEnd('=')
        val out = java.io.ByteArrayOutputStream(cleaned.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val v = ALPHABET.indexOf(ch)
            require(v >= 0) { "非法字符 '$ch'" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
