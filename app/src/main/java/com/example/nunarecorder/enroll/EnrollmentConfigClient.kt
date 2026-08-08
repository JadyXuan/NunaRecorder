package com.example.nunarecorder.enroll

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 入组后用设备令牌向服务端拉取二维码装不下的配置。
 *
 * 二维码载荷上限 213 字节：最小四项 197 能进，加上标注网址变 256、再加网关凭据 357，
 * 都生成不出二维码，只剩几百字符要人手抄。所以这些字段改为在这里取。
 *
 * 附带的好处是换域名或轮换网关凭据时**连入组卡都不用重发**——
 * 而这正是那张工单自己写的目标。
 *
 * 参与者自己的密码**不在**这里：服务端只存 bcrypt 哈希，取不回来，
 * 而且那个密码本来就该留在纸质入组卡上，不该进 App 存储。
 */
class EnrollmentConfigClient(private val client: OkHttpClient) {

    data class Config(
        val webUrl: String?,
        val ssoUsername: String?,
        val ssoPassword: String?
    )

    /**
     * 拉取结果。[problem] 非空表示没拿到，且**说得出为什么**。
     *
     * 原来这里只返回 `Config?`，失败一律吞成 null，入组现场不打任何日志——
     * 于是"服务端没配"和"这台机器用的是共享令牌"在界面上长得一模一样，
     * 而后者研究员当场就能改。2026-08-09 platform 实测生产环境三个变量都有值、
     * 共享令牌是硬 403，我这边却报成了"服务端返回空值"，就是被这个 null 骗的。
     */
    data class Outcome(val config: Config?, val problem: String?)

    fun fetch(code: EnrollmentCode): Outcome {
        val request = Request.Builder()
            .url("${code.serverUrl}/v1/enrollment/config")
            .header("X-Upload-Token", code.token)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return Outcome(null, describeFailure(resp.code))
                val body = resp.body?.string()
                    ?: return Outcome(null, "服务端返回了空响应")
                val o = JSONObject(body)
                Outcome(
                    Config(
                        webUrl = o.optString("web_url").takeIf { it.isNotEmpty() },
                        ssoUsername = o.optString("sso_username").takeIf { it.isNotEmpty() },
                        ssoPassword = o.optString("sso_password").takeIf { it.isNotEmpty() }
                    ),
                    null
                )
            }
        } catch (e: Exception) {
            Outcome(null, "连不上服务器（${e.javaClass.simpleName}）")
        }
    }

    companion object {
        /**
         * 把 HTTP 状态码翻成研究员当场看得懂、且**能据此行动**的一句话。
         *
         * 403 是最重要的一条：接口对共享令牌是故意硬拒的（共享令牌谁也不代表，
         * 而这个接口发的是网关凭据）。说清楚它，研究员当场重发一张入组码就好；
         * 说成"拉取失败"，就要等参与者回来才发现登录不了。
         */
        fun describeFailure(httpCode: Int): String = when (httpCode) {
            401, 403 ->
                "服务器拒绝了这个令牌（HTTP $httpCode）。多半是这张入组码带的是共享令牌，" +
                    "而不是这位参与者自己的设备令牌——请研究员在控制台重新生成一张入组码。"
            404 -> "这台服务器上没有这个接口（HTTP 404），可能是服务端版本偏旧。"
            in 500..599 -> "服务器出错（HTTP $httpCode），稍后在设置里点一次自检重试。"
            else -> "拉取配置失败（HTTP $httpCode）。"
        }
    }

    /**
     * 把拉到的配置合并进入组码。
     *
     * 入组码里已有的值优先——它是研究员当场发的，比服务端的环境变量更贴近现场；
     * 服务端只补入组码没有的部分。
     */
    fun merge(code: EnrollmentCode, config: Config?): EnrollmentCode {
        if (config == null) return code
        val login = code.login
        val mergedLogin = when {
            login != null -> login.copy(
                ssoUsername = login.ssoUsername ?: config.ssoUsername,
                ssoPassword = login.ssoPassword ?: config.ssoPassword
            )
            config.ssoUsername != null || config.ssoPassword != null -> LoginHint(
                // 参与者自己的账号就是 participant_id；密码在纸质卡上，服务端不返回
                username = code.participantId,
                password = "",
                ssoUsername = config.ssoUsername,
                ssoPassword = config.ssoPassword
            )
            else -> null
        }
        return code.copy(
            webUrl = code.webUrl ?: config.webUrl,
            login = mergedLogin
        )
    }
}
