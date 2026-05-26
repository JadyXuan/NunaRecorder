package com.example.nunarecorder.context

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * 身体活动识别（走路/静止/乘车等），写入 context.jsonl 的 activity 行。
 */
class ActivityRecognitionCollector(
    private val context: Context,
    private val onActivity: (state: String, confidence: Int, tMs: Long) -> Unit
) {

    companion object {
        private const val TAG = "ActivityRecognition"
        const val ACTION_ACTIVITY_UPDATE = "com.example.nunarecorder.ACTION_ACTIVITY_UPDATE"
        private const val UPDATE_INTERVAL_MS = 8_000L

        @Volatile
        private var activeCollector: ActivityRecognitionCollector? = null

        fun isSupported(context: Context): Boolean = try {
            ActivityRecognition.getClient(context)
            true
        } catch (_: Exception) {
            false
        }
    }

    private var receiverRegistered = false
    private var pendingIntent: PendingIntent? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != ACTION_ACTIVITY_UPDATE) return
            if (!ActivityRecognitionResult.hasResult(intent)) return
            val result = ActivityRecognitionResult.extractResult(intent) ?: return
            val best = result.mostProbableActivity
            val state = mapActivity(best.type)
            onActivity(state, best.confidence, System.currentTimeMillis())
            Log.d(TAG, "activity=$state conf=${best.confidence}")
        }
    }

    fun start(): Boolean {
        if (!isSupported(context)) {
            Log.w(TAG, "Activity Recognition not available")
            return false
        }
        if (receiverRegistered) return true
        activeCollector = this
        val filter = IntentFilter(ACTION_ACTIVITY_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        receiverRegistered = true
        val intent = Intent(ACTION_ACTIVITY_UPDATE).setPackage(context.packageName)
        pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        ActivityRecognition.getClient(context)
            .requestActivityUpdates(UPDATE_INTERVAL_MS, pendingIntent!!)
            .addOnSuccessListener { Log.d(TAG, "Activity updates requested") }
            .addOnFailureListener { e -> Log.e(TAG, "requestActivityUpdates failed", e) }
        return true
    }

    fun stop() {
        if (!receiverRegistered) return
        pendingIntent?.let { pi ->
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(pi)
                .addOnCompleteListener { Log.d(TAG, "Activity updates removed") }
        }
        pendingIntent = null
        runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        if (activeCollector === this) activeCollector = null
    }

    private fun mapActivity(type: Int): String = when (type) {
        DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
        DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
        DetectedActivity.ON_FOOT -> "ON_FOOT"
        DetectedActivity.RUNNING -> "RUNNING"
        DetectedActivity.STILL -> "STILL"
        DetectedActivity.TILTING -> "TILTING"
        DetectedActivity.WALKING -> "WALKING"
        else -> "UNKNOWN"
    }
}
