package com.example.nunarecorder.lifelog

import com.example.nunarecorder.network.withServerBasicAuth
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class LifelogApiClient(
    private val httpClient: OkHttpClient,
    baseUrl: String,
    private val userId: String,
    private val basicAuthUsername: String = "",
    private val basicAuthPassword: String = ""
) {
    private val baseUrl = baseUrl.trimEnd('/')

    fun timeline(date: String): TimelinePayload {
        val body = getWithLegacyFallback(
            "/api/v1/timeline?date=$date",
            "/api/timeline?date=$date"
        )
        return LifelogJson.parseTimeline(body)
    }

    fun pending(): PendingPayload {
        val body = getWithLegacyFallback(
            "/api/v1/annotations/pending",
            "/api/pending"
        )
        return LifelogJson.parsePending(body)
    }

    fun diary(date: String): DiaryPayload {
        val body = get("/api/v1/diary?date=$date", allowNotFound = false)
            ?: throw IOException("empty diary response")
        return LifelogJson.parseDiary(body)
    }

    fun annotate(eventId: Long, action: String, label: String?): AnnotationResult {
        val json = JSONObject().apply {
            put("event_id", eventId)
            put("action", action)
            if (label != null) put("label", label) else put("label", JSONObject.NULL)
        }.toString()
        val primary = postJson("/api/v1/annotations", json, allowNotFound = true)
        val response = if (primary == null) {
            postJson("/api/annotate", json, allowNotFound = false)
                ?: throw IOException("empty annotation response")
        } else {
            primary
        }
        return LifelogJson.parseAnnotationResult(response)
    }

    private fun getWithLegacyFallback(primary: String, legacy: String): String {
        val first = get(primary, allowNotFound = true)
        return first ?: get(legacy, allowNotFound = false)
        ?: throw IOException("empty response")
    }

    private fun get(path: String, allowNotFound: Boolean): String? {
        val request = requestBuilder(path).get().build()
        return execute(request, allowNotFound)
    }

    private fun postJson(path: String, json: String, allowNotFound: Boolean): String? {
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = requestBuilder(path).post(body).build()
        return execute(request, allowNotFound)
    }

    private fun requestBuilder(path: String): Request.Builder =
        Request.Builder()
            .url("$baseUrl$path")
            .header("Accept", "application/json")
            .header("X-Lifelog-Client", "NunaRecorder-Android")
            .withServerBasicAuth(basicAuthUsername, basicAuthPassword)
            .apply {
                if (userId.isNotBlank()) header("X-User-Id", userId)
            }

    private fun execute(request: Request, allowNotFound: Boolean): String? {
        httpClient.newCall(request).execute().use { response ->
            if (allowNotFound && response.code == 404) return null
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${body.take(240)}")
            }
            return body
        }
    }
}
