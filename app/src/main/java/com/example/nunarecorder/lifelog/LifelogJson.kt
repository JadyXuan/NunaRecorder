package com.example.nunarecorder.lifelog

import org.json.JSONArray
import org.json.JSONObject

internal object LifelogJson {

    fun parseTimeline(json: String): TimelinePayload {
        val root = JSONObject(json)
        val entries = root.optJSONArray("segments").orEmpty().mapObjects { item ->
            TimelineEntry(
                segmentId = item.getLong("segment_id"),
                startTimeMs = item.getLongWithLegacy("start_time_ms", "t_start_ms"),
                endTimeMs = item.getLongWithLegacy("end_time_ms", "t_end_ms"),
                predictedLabel = item.optNullableStringWithLegacy(
                    "predicted_label",
                    "pred_label"
                ),
                predictedConfidence = item.optNullableDoubleWithLegacy(
                    "confidence",
                    "pred_conf"
                ),
                predictedSource = item.optNullableStringWithLegacy("source", "pred_source"),
                confirmedLabel = item.optNullableStringWithLegacy("reviewed_label", "label"),
                reviewAction = item.optNullableString("review_action"),
                asrText = item.optString("asr_text", ""),
                soundEvents = parseSoundEvents(
                    item.optJSONArray("top_sound_events")
                        ?: item.optJSONArray("sound_events")
                )
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
        val prompts = (
            root.optJSONArray("items") ?: root.optJSONArray("pending")
            ).orEmpty().mapObjects { item ->
            AnnotationPrompt(
                eventId = item.getLong("event_id"),
                kind = item.optString("kind", "block"),
                question = item.optString("question"),
                suggestedLabel = item.optNullableString("suggested_label"),
                suggestedName = item.optString(
                    "suggested_display_name",
                    item.optString("suggested_name")
                ),
                startTimeMs = item.optNullableLongWithLegacy("start_time_ms", "t_start_ms"),
                endTimeMs = item.optNullableLongWithLegacy("end_time_ms", "t_end_ms"),
                asrText = item.optString("asr_context", item.optString("asr_text", "")),
                createdTimeMs = item.optLong(
                    "created_at_ms",
                    item.optLong("created_ms", 0L)
                ),
                expiresTimeMs = item.optNullableLongWithLegacy(
                    "expires_at_ms",
                    "expires_ms"
                )
            )
        }
        return PendingPayload(
            prompts = prompts,
            schemaVersion = root.optInt("schema_version", 1)
        )
    }

    fun parseAnnotationResult(json: String): AnnotationResult {
        val root = JSONObject(json)
        if (!root.has("event_id")) {
            return AnnotationResult(
                annotationId = 0L,
                eventId = 0L,
                action = root.optString("status", "answered"),
                effectiveLabel = root.optNullableString("label"),
                memoryUpdated = false
            )
        }
        return AnnotationResult(
            annotationId = root.optLong("annotation_id", 0L),
            eventId = root.getLong("event_id"),
            action = root.getString("action"),
            effectiveLabel = root.optNullableString("effective_label"),
            memoryUpdated = root.optBoolean("memory_updated", false)
        )
    }

    fun parseDiary(json: String): DiaryPayload {
        val root = JSONObject(json)
        return DiaryPayload(
            date = root.getString("date"),
            entries = root.optJSONArray("entries").orEmpty().mapObjects { item ->
                DiaryEntry(
                    startTimeMs = item.getLong("start_time_ms"),
                    endTimeMs = item.getLong("end_time_ms"),
                    label = item.getString("label"),
                    displayName = item.getString("display_name"),
                    summary = item.getString("summary")
                )
            },
            schemaVersion = root.optInt("schema_version", 1)
        )
    }

    private fun parseTaxonomy(array: JSONArray?): List<ActivityLabel> =
        array.orEmpty().mapObjects {
            ActivityLabel(
                id = it.getString("id"),
                name = it.optString(
                    "display_name",
                    it.optString("name", it.getString("id"))
                )
            )
        }

    private fun parseSoundEvents(array: JSONArray?): List<SoundEventSummary> =
        array.orEmpty().mapObjects {
            SoundEventSummary(
                name = it.optString("label", it.optString("name")),
                probability = when {
                    it.has("confidence") -> it.optDouble("confidence")
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

    private fun JSONObject.getLongWithLegacy(primary: String, legacy: String): Long =
        if (has(primary)) getLong(primary) else getLong(legacy)

    private fun JSONObject.optNullableStringWithLegacy(
        primary: String,
        legacy: String
    ): String? = if (has(primary)) optNullableString(primary) else optNullableString(legacy)

    private fun JSONObject.optNullableDoubleWithLegacy(
        primary: String,
        legacy: String
    ): Double? = if (has(primary)) optNullableDouble(primary) else optNullableDouble(legacy)

    private fun JSONObject.optNullableLongWithLegacy(
        primary: String,
        legacy: String
    ): Long? = if (has(primary)) optNullableLong(primary) else optNullableLong(legacy)
}
