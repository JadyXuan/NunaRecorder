package com.example.nunarecorder.voiceprint

import com.example.nunarecorder.enroll.EnrollmentCode
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * 上传声纹。`POST /v1/enrollment/voiceprint`，认证与会话同步完全相同（设备令牌）。
 *
 * **文件名由服务端生成**，客户端只给 `kind`。重录不覆盖，服务端递增序号——
 * 一次不好的重录不该悄悄毁掉之前那次好的。
 */
class VoiceprintUploader(private val client: OkHttpClient) {

    enum class Kind(val wire: String) { READ("read"), FREE("free") }

    data class Result(val ok: Boolean, val message: String)

    fun upload(enrollment: EnrollmentCode, kind: Kind, file: File): Result {
        if (!file.isFile || file.length() == 0L) {
            return Result(false, "声纹文件为空，请重录")
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("kind", kind.wire)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/opus".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("${enrollment.serverUrl}/v1/enrollment/voiceprint")
            .header("X-Upload-Token", enrollment.token)
            .post(body)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result(true, "声纹已上传（${kind.wire}）")
                    // 这个接口拒绝共享令牌：它必须知道声纹属于谁
                    resp.code == 403 -> Result(
                        false,
                        "这台设备用的是共享令牌，声纹必须用参与者自己的入组码上传"
                    )
                    resp.code == 401 -> Result(false, "令牌无效或已撤销，请联系研究员")
                    else -> Result(false, "上传失败（HTTP ${resp.code}）")
                }
            }
        } catch (e: Exception) {
            // 本地文件保留：重传一次比让参与者重录一遍便宜得多
            Result(false, "上传失败：${e.message ?: "网络异常"}，文件已保留可重试")
        }
    }
}
