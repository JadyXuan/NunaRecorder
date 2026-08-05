package com.example.nunarecorder.enroll

import android.content.Context
import org.json.JSONObject

/**
 * 入组配置的唯一存放处（App 私有 SharedPreferences）。
 *
 * **没有任何默认值。** 没扫过码就是没配置，`current()` 返回 null，采集和上传都不可用。
 * 这是刻意的：以前 `serverHost` 默认 `10.0.2.2`、`userId` 留空默认 `mock-user-001`，
 * 结果是配置错了也能"正常"跑，采完一整天才发现传不上去。
 * 宁可一开始就明确不可用，也不要悄悄退回一个错的地址。
 */
class EnrollmentStore(context: Context) {

    companion object {
        private const val PREF_NAME = "enrollment_prefs"
        private const val KEY_CODE = "enrollment"
        private const val KEY_ENROLLED_AT = "enrolled_at_ms"
        /** 令牌被服务端撤销后置位；不清除配置，方便研究员看到是哪张卡被撤了 */
        private const val KEY_REVOKED = "token_revoked"
    }

    private val sp = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun current(): EnrollmentCode? {
        val json = sp.getString(KEY_CODE, null) ?: return null
        return try {
            val o = JSONObject(json)
            EnrollmentCode(
                serverUrl = o.getString("server"),
                participantId = o.getString("pid"),
                token = o.getString("token"),
                webUrl = o.optString("web").takeIf { it.isNotEmpty() },
                login = o.optJSONObject("login")?.let {
                    LoginHint(
                        username = it.getString("u"),
                        password = it.getString("p"),
                        ssoUsername = it.optString("su").takeIf { v -> v.isNotEmpty() },
                        ssoPassword = it.optString("sp").takeIf { v -> v.isNotEmpty() }
                    )
                }
            )
        } catch (_: Exception) {
            null
        }
    }

    val isEnrolled: Boolean get() = current() != null

    fun save(code: EnrollmentCode) {
        sp.edit()
            .putString(KEY_CODE, code.toJson().toString())
            .putLong(KEY_ENROLLED_AT, System.currentTimeMillis())
            .putBoolean(KEY_REVOKED, false)
            .apply()
    }

    fun enrolledAtMs(): Long? = sp.getLong(KEY_ENROLLED_AT, 0L).takeIf { it > 0L }

    /**
     * 令牌被撤销（服务端 401）。**只置标记，不删本地数据也不清配置**——
     * 参与者手机上可能还有没传上去的会话，清掉配置等于让那些数据再也传不出去。
     */
    fun markRevoked() {
        sp.edit().putBoolean(KEY_REVOKED, true).apply()
    }

    fun isRevoked(): Boolean = sp.getBoolean(KEY_REVOKED, false)

    fun clearRevoked() {
        sp.edit().putBoolean(KEY_REVOKED, false).apply()
    }

    /** 换手机或重新入组时用。会话数据在公共 Downloads 下，不受影响。 */
    fun clear() {
        sp.edit().clear().apply()
    }
}
