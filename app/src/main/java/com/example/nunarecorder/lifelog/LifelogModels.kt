package com.example.nunarecorder.lifelog

data class ActivityLabel(
    val id: String,
    val name: String
)

data class SoundEventSummary(
    val name: String,
    val probability: Double
)

data class TimelineEntry(
    val segmentId: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val predictedLabel: String?,
    val predictedConfidence: Double?,
    val predictedSource: String?,
    val confirmedLabel: String?,
    val reviewAction: String?,
    val asrText: String,
    val soundEvents: List<SoundEventSummary>
) {
    val effectiveLabel: String?
        get() = confirmedLabel ?: predictedLabel
}

data class AnnotationPrompt(
    val eventId: Long,
    val kind: String,
    val question: String,
    val suggestedLabel: String?,
    val suggestedName: String,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val asrText: String,
    val createdTimeMs: Long,
    val expiresTimeMs: Long?
)

data class TimelinePayload(
    val date: String,
    val entries: List<TimelineEntry>,
    val taxonomy: List<ActivityLabel>,
    val schemaVersion: Int
)

data class PendingPayload(
    val prompts: List<AnnotationPrompt>,
    val schemaVersion: Int
)

data class AnnotationResult(
    val annotationId: Long,
    val eventId: Long,
    val action: String,
    val effectiveLabel: String?,
    val memoryUpdated: Boolean
)

data class DiaryEntry(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val label: String,
    val displayName: String,
    val summary: String
)

data class DiaryPayload(
    val date: String,
    val entries: List<DiaryEntry>,
    val schemaVersion: Int
)

data class LifelogUiState(
    val date: String,
    val loading: Boolean = false,
    val timeline: List<TimelineEntry> = emptyList(),
    val pending: List<AnnotationPrompt> = emptyList(),
    val diary: List<DiaryEntry> = emptyList(),
    val taxonomy: List<ActivityLabel> = emptyList(),
    val schemaVersion: Int = 1,
    val error: String? = null,
    val lastUpdatedMs: Long? = null
)
