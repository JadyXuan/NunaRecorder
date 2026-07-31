package com.example.nunarecorder.sync

import com.example.nunarecorder.data.UserSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 保存设置后立刻对服务器做一次握手自检。
 *
 * 参与者要在入组现场就能确认配置对不对，而不是采完一整天才发现传不上去。
 * 2026-07-31 佩戴者反馈"保存设置没有任何反馈，也不知道服务器通不通"。
 */
object ServerHandshakeCheck {

    enum class Level { OK, WARN, FAIL }

    data class Line(val level: Level, val text: String)

    data class Result(val lines: List<Line>) {
        val ok: Boolean get() = lines.none { it.level == Level.FAIL }
        val hasWarning: Boolean get() = lines.any { it.level == Level.WARN }
    }

    fun run(client: OkHttpClient, settings: UserSettings): Result {
        val lines = mutableListOf<Line>()
        val baseUrl = "http://${settings.serverHost}:${settings.serverPort}"

        // 1) 用户 ID：留空会让所有数据落到 mock-user-001 名下，且 Web 注册也对不上
        if (settings.userId.isBlank()) {
            lines.add(Line(Level.FAIL, "用户 ID 为空：上传会被记到 mock-user-001 名下"))
        } else {
            lines.add(Line(Level.OK, "用户 ID：${settings.userId}"))
        }

        // 2) 服务器可达性
        val healthCode = statusOf(client, Request.Builder().url("$baseUrl/health").get().build())
        when {
            healthCode == 200 -> lines.add(Line(Level.OK, "服务器可达（$baseUrl）"))
            healthCode == null -> {
                lines.add(Line(Level.FAIL, "连不上服务器 $baseUrl，请检查地址、端口和网络"))
                return Result(lines)
            }
            else -> lines.add(Line(Level.WARN, "/health 返回 HTTP $healthCode"))
        }

        // 3) 上传令牌。故意发一个空 body：鉴权在 handler 之前执行，所以
        //    400 = 令牌通过、只是内容不合法。这样探测不会在服务端留下任何 upload 记录。
        val authCode = statusOf(
            client,
            Request.Builder()
                .url("$baseUrl/v1/session/sync/init")
                .apply {
                    if (settings.uploadToken.isNotBlank()) {
                        header("X-Upload-Token", settings.uploadToken)
                    }
                }
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
        )
        lines.add(
            when (authCode) {
                400 -> Line(Level.OK, "上传令牌有效，会话同步接口就绪")
                401 -> Line(Level.FAIL, "上传令牌无效（401），请核对设置里的令牌")
                503 -> Line(Level.FAIL, "服务端未配置 UPLOAD_TOKEN（503），需要先在服务器上设置")
                404 -> Line(Level.FAIL, "服务端没有 v1 会话同步接口（404），请升级 Receiver")
                null -> Line(Level.FAIL, "同步接口无法连接")
                else -> Line(Level.WARN, "同步接口返回 HTTP $authCode（预期 400 表示令牌通过）")
            }
        )
        return Result(lines)
    }

    private fun statusOf(client: OkHttpClient, request: Request): Int? = try {
        client.newCall(request).execute().use { it.code }
    } catch (_: Exception) {
        null
    }
}
