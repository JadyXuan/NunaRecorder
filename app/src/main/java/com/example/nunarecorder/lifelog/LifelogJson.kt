package com.example.nunarecorder.lifelog

import org.json.JSONArray
import org.json.JSONObject

internal object LifelogJson {

    fun parseTimeline(json: String): TimelinePayload {
        val root = JSONObject(json)
        val entries = root.optJSONArray("segments").orEmpty().mapObjects { item ->
            TimelineEntry(
                segmentId = item.getLong("segment_id"),
                startTimeMs = item.getLong("t_start_ms"),
                endTimeMs = item.getLong("t_end_ms"),
                predictedLabel = item.optNullableString("pred_label"),
                predictedConfidence = item.optNullableDouble("pred_conf"),
                predictedSource = item.optNullableString("pred_source"),
                confirmedLabel = item.optNullableString("label"),
                asrText = item.optString("asr_text", ""),
                soundEvents = parseSoundEvents(item.optJSONArray("sound_events"))
            )
        }
        return TimelinePayload(
            date = root.optString("date"),
            entries = entries,
            taxonomy = parseTaxonomy(root.optJSONArray("taxonomy")),
            schemaVersion = root.optInt("schema_version", 1)
        )
    }

    fun parsePending(json: String): PendingPayload {
        val root = JSONObject(json)
        val prompts = root.optJSONArray("pending").orEmpty().mapObjects { item ->
            AnnotationPrompt(
                eventId = item.getLong("event_id"),
                kind = item.optString("kind", "block"),
                question = item.optString("question"),
                suggestedLabel = item.optNullableString("suggested_label"),
                suggestedName = item.optString("suggested_name"),
                startTimeMs = item.optNullableLong("t_start_ms"),
                endTimeMs = item.optNullableLong("t_end_ms"),
                asrText = item.optString("asr_text", ""),
                createdTimeMs = item.optLong("created_ms", 0L),
                expiresTimeMs = item.optNullableLong("expires_ms")
            )
        }
        return PendingPayload(
            prompts = prompts,
            taxonomy = parseTaxonomy(root.optJSONArray("taxonomy")),
            schemaVersion = root.optInt("schema_version", 1)
        )
    }

    fun parseAnnotationResult(json: String): AnnotationResult {
        val root = JSONObject(json)
        return AnnotationResult(
            status = root.optString("status", "answered"),
            label = root.optNullableString("label"),
            memorySize = if (root.has("memory_size") && !root.isNull("memory_size")) {
                root.optInt("memory_size")
            } else {
                null
            }
        )
    }

    private fun parseTaxonomy(array: JSONArray?): List<ActivityLabel> =
        array.orEmpty().mapObjects {
            ActivityLabel(
                id = it.getString("id"),
                name = it.optString("name", it.getString("id"))
            )
        }

    private fun parseSoundEvents(array: JSONArray?): List<SoundEventSummary> =
        array.orEmpty().mapObjects {
            SoundEventSummary(
                name = it.optString("name"),
                probability = when {
                    it.has("probability") -> it.optDouble("probability")
                    else -> it.optDouble("prob")
                }
            )
        }

    private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

    private inline fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
        buildList {
            for (index in 0 until length()) {
                add(mapper(getJSONObject(index)))
            }
        }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key)

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)
}
