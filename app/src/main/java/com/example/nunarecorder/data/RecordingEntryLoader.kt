package com.example.nunarecorder.data

import com.example.nunarecorder.session.SessionManifest
import com.example.nunarecorder.session.SessionPaths
import com.example.nunarecorder.sync.SessionSyncStatus
import java.io.File

/**
 * 录音列表的加载器：**带缓存**，只重新解析真正变过的会话。
 *
 * 2026-08-09 用户实测：35 条会话时"进入录音会很卡"。明天一天会带回 700–900 条。
 *
 * 原来 `refreshList()` 在主线程上把每个会话的 `manifest.json` 整份 JSON 解析一遍，
 * 而且录制期间每秒刷一次。一个小时的会话 manifest 里有 60 个 segment 对象，
 * 35 条就是每秒解析两千多个对象——再加上 [RecordingEntry.Session] 那几个
 * `get()` 属性每次访问都读盘（`sync_status.json` 又是一次 JSON 解析），
 * 列表卡死是必然的。
 *
 * 这里做两件事：
 * 1. 用 `manifest.json` 的 `lastModified` + `length` 作为缓存键。会话一旦写完就不再变，
 *    所以除了正在录的那一个，其余的第二次刷新起都是纯内存命中。
 * 2. 目录消失时把缓存项丢掉，不让已删除的会话靠缓存复活。
 *
 * **调用方必须在 IO 线程上用它**——构造 [RecordingEntry.Session] 本身就要读盘。
 */
class RecordingEntryLoader(
    /** 会话目录来源；可注入是为了能在 JVM 单元测试里用临时目录跑 */
    private val sessionDirs: () -> List<File> = { SessionPaths.listSessionDirs() },
    private val legacyFiles: () -> List<File> = { SessionPaths.listLegacyOpusFiles() }
) {

    /**
     * 缓存键必须同时覆盖 `manifest.json` **和** `labels/sync_status.json`。
     *
     * 2026-08-09 用户实测：「全部上传完，全部上传还是显示为 10，同时清理已同步也是 10，
     * 每个 seg 一上来也没有显示已同步，切换界面刷新后才显示正常」。
     *
     * 成因是我引入的：[RecordingEntry.Session.syncStatus] 从 `get()` 改成构造时读一次
     * （为了不在每帧读盘），但缓存键只看 manifest——而**上传完成只改
     * `sync_status.json`，不动 manifest**，于是缓存一直命中，界面拿到的是上传前的状态。
     * 切换标签页会重建列表，所以"刷新一下就好了"。
     */
    private data class CacheKey(
        val manifestModified: Long,
        val manifestLength: Long,
        val syncModified: Long,
        val syncLength: Long
    )

    private val cache = HashMap<String, Pair<CacheKey, RecordingEntry.Session>>()

    /** 缓存命中数，仅用于测试和排障 */
    var hits = 0L
        private set

    /** 实际解析次数，仅用于测试和排障 */
    var misses = 0L
        private set

    fun load(): List<RecordingEntry> {
        val dirs = sessionDirs()
        val alive = HashSet<String>(dirs.size)
        val sessions = ArrayList<RecordingEntry>(dirs.size)

        for (dir in dirs) {
            val path = dir.absolutePath
            alive.add(path)
            val mf = SessionPaths.manifestFile(dir)
            val sf = SessionSyncStatus.syncFile(dir)
            val key = CacheKey(mf.lastModified(), mf.length(), sf.lastModified(), sf.length())
            val cached = cache[path]
            if (cached != null && cached.first == key) {
                hits++
                sessions.add(cached.second)
                continue
            }
            misses++
            val manifest = SessionManifest.load(mf) ?: continue
            val entry = RecordingEntry.Session(dir, manifest)
            cache[path] = key to entry
            sessions.add(entry)
        }

        // 删掉的会话不能靠缓存留在列表里
        cache.keys.retainAll(alive)

        val legacy = legacyFiles().map { RecordingEntry.LegacyOpus(it) }
        return (sessions + legacy).sortedByDescending { it.sortKey }
    }

    /** 明确作废某个会话的缓存（例如刚按片段删过东西） */
    fun invalidate(dir: File) {
        cache.remove(dir.absolutePath)
    }

    fun invalidateAll() {
        cache.clear()
    }
}
