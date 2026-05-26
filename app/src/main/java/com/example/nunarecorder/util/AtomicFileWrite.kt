package com.example.nunarecorder.util

import java.io.File

/**
 * 先写临时文件再 rename，避免进程被杀时 JSON 半截损坏。
 */
fun File.writeTextAtomic(text: String) {
    parentFile?.mkdirs()
    val tmp = File(parentFile, "${name}.tmp")
    tmp.writeText(text)
    if (exists()) delete()
    if (!tmp.renameTo(this)) {
        tmp.copyTo(this, overwrite = true)
        tmp.delete()
    }
}
