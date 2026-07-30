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
                .put("version", CURRENT_VERSION)
                .put("uploaded", uploaded)
                .put("updated_ms", System.currentTimeMillis())
                .toString(2)
        )
    }

    private fun load(): JSONObject = try {
        val root = JSONObject(stateFile.readText())
        if (root.optInt("version") != CURRENT_VERSION) {
            JSONObject()
        } else {
            root.optJSONObject("uploaded") ?: JSONObject()
        }
    } catch (_: Exception) {
        JSONObject()
    }

    companion object {
        /*
         * V2 changed segment timestamps from relative offsets to UTC epoch.
         * Ignore V1 receipts so incorrectly timestamped pilot uploads can be
         * retried with the corrected, stable metadata.
         */
        private const val CURRENT_VERSION = 2
    }
}
