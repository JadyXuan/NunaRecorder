package com.example.nunarecorder.util

import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val atomicWriteLocks = ConcurrentHashMap<String, Any>()

/**
 * 先写临时文件再 rename，避免进程被杀时 JSON 半截损坏。
 */
fun File.writeTextAtomic(text: String) {
    val lock = atomicWriteLocks.computeIfAbsent(absolutePath) { Any() }
    synchronized(lock) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "${name}.tmp")
        tmp.writeText(text)
        if (exists()) delete()
        if (!tmp.renameTo(this)) {
            tmp.copyTo(this, overwrite = true)
            tmp.delete()
        }
    }
}
