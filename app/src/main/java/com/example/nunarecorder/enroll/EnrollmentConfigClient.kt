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

    /** @return null 表示拿不到（接口不存在、网络不通、令牌无效）；调用方应降级而不是失败 */
    fun fetch(code: EnrollmentCode): Config? {
        val request = Request.Builder()
            .url("${code.serverUrl}/v1/enrollment/config")
            .header("X-Upload-Token", code.token)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val o = JSONObject(resp.body?.string() ?: return null)
                Config(
                    webUrl = o.optString("web_url").takeIf { it.isNotEmpty() },
                    ssoUsername = o.optString("sso_username").takeIf { it.isNotEmpty() },
                    ssoPassword = o.optString("sso_password").takeIf { it.isNotEmpty() }
                )
            }
        } catch (_: Exception) {
            null
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
