package com.example.nunarecorder.recording

import java.util.Calendar
import java.util.TimeZone

/**
 * 采集日与小时边界。**必须和服务端 `collection_time.py` 保持一致**，
 * 否则同一段音频在手机上属于这一天、在 Web 上属于另一天。
 *
 * 采集日不是日历日：默认时区 `Asia/Hong_Kong`、切点 04:00，所以 `20260805`
 * 覆盖的是 08-05 04:00 到 08-06 03:59:59 本地时间。切在午夜是错的——
 * 学生经常戴到过午夜，那样会把晚上和它所属的夜里劈开，等于四小时后重演同一个 bug。
 */
object CollectionClock {

    const val TIMEZONE_ID = "Asia/Hong_Kong"
    const val DAY_CUT_HOUR = 4

    private val zone: TimeZone get() = TimeZone.getTimeZone(TIMEZONE_ID)

    /** 采集日编号 `yyyyMMdd`，与服务端 `day_id_for_ts` 同规则。 */
    fun dayId(epochMs: Long): String {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        if (cal.get(Calendar.HOUR_OF_DAY) < DAY_CUT_HOUR) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return "%04d%02d%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * 采集日的**起点**（本地 04:00，epoch 毫秒）。
     *
     * 「04:00 → 当天首次录制」是 T-047 的验收指标，端上和服务端必须用同一个边界，
     * 否则同一段数据两边算出来的"迟了多久"不一样。
     */
    fun dayStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = epochMs
            if (get(Calendar.HOUR_OF_DAY) < DAY_CUT_HOUR) add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, DAY_CUT_HOUR)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * 当前所属小时格的**起点**（本地整点，epoch 毫秒）。
     *
     * 小时格对齐本地整点而不是"开始录制后每满一小时"：两台手机在同一时刻开始
     * 采集，它们的会话边界应当落在同一处，否则排障时无法横向对齐。
     */
    fun hourStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = epochMs
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 下一个小时格的起点。会话应当在这个时刻切换。 */
    fun nextHourStart(epochMs: Long): Long {
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = hourStart(epochMs)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        return cal.timeInMillis
    }

    /** 两个时刻是否属于同一个小时格。 */
    fun sameHour(a: Long, b: Long): Boolean = hourStart(a) == hourStart(b)
}
