package com.example.nunarecorder.sync

import com.example.nunarecorder.util.writeTextAtomic
import org.json.JSONObject
import java.io.File

internal class SessionAutoUploadState(private val sessionDir: File) {
    private val stateFile = File(sessionDir, "labels/lifelog_auto_upload.json")
    private val uploaded = load()

    fun isUploaded(relativePath: String, sha256: String): Boolean =
        uploaded.optString(relativePath) == sha256

    fun markUploaded(relativePath: String, sha256: String) {
        uploaded.put(relativePath, sha256)
        stateFile.parentFile?.mkdirs()
        stateFile.writeTextAtomic(
            JSONObject()
                .put("version", 1)
                .put("uploaded", uploaded)
                .put("updated_ms", System.currentTimeMillis())
                .toString(2)
        )
    }

    private fun load(): JSONObject = try {
        JSONObject(stateFile.readText()).optJSONObject("uploaded") ?: JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }
}
