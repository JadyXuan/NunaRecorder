package com.example.nunarecorder.diagnostics

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Low-volume, structured, persistent diagnostics for long-running BLE recordings.
 * Writes are serialized off the Bluetooth callback thread.
 */
class DiagnosticLogger internal constructor(
    private val rootDir: File,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val elapsedClockMs: () -> Long = SystemClock::elapsedRealtime,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NunaDiagnosticWriter").apply { isDaemon = true }
    }
) {
    companion object {
        private const val RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

        fun create(context: Context): DiagnosticLogger {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return DiagnosticLogger(File(base, "diagnostics"))
        }
    }

    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HH", Locale.US)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        executor.execute { cleanup(wallClockMs()) }
    }

    fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        val wallMs = wallClockMs()
        val elapsedMs = elapsedClockMs()
        val safeFields = LinkedHashMap(fields)
        executor.execute {
            rootDir.mkdirs()
            val json = JSONObject().apply {
                put("wall_time_ms", wallMs)
                put("wall_time_utc", isoFormat.format(Date(wallMs)))
                put("elapsed_realtime_ms", elapsedMs)
                put("event", event)
                safeFields.forEach { (key, value) -> putJsonValue(key, value) }
            }
            currentFile(wallMs).appendText(json.toString() + "\n", Charsets.UTF_8)
            cleanup(wallMs)
        }
    }

    /** Runs after all queued writes and returns files from oldest to newest. */
    fun snapshotFiles(onReady: (List<File>) -> Unit) {
        executor.execute {
            cleanup(wallClockMs())
            onReady(listFilesInternal())
        }
    }

    internal fun listFiles(): List<File> = listFilesInternal()

    private fun currentFile(wallMs: Long): File =
        File(rootDir, "nuna-diagnostics-${fileNameFormat.format(Date(wallMs))}.jsonl")

    private fun listFilesInternal(): List<File> =
        rootDir.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    private fun cleanup(nowMs: Long) {
        val retained = listFilesInternal().toMutableList()
        retained.filter { nowMs - it.lastModified() > RETENTION_MS }.forEach {
            if (it.delete()) retained.remove(it)
        }
        var totalBytes = retained.sumOf { it.length() }
        for (file in retained.sortedBy { it.lastModified() }) {
            if (totalBytes <= MAX_TOTAL_BYTES) break
            val bytes = file.length()
            if (file.delete()) totalBytes -= bytes
        }
    }

    private fun JSONObject.putJsonValue(key: String, value: Any?) {
        when (value) {
            null -> put(key, JSONObject.NULL)
            is String, is Number, is Boolean, is JSONObject -> put(key, value)
            else -> put(key, value.toString())
        }
    }
}
