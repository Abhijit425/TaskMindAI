package com.abhijit.airealityscanner.ai.model

enum class VisionRole { USER, ASSISTANT }

/** A provider-neutral conversation turn. Images are Base64-encoded JPEGs. */
data class VisionMessage(
    val role: VisionRole,
    val text: String,
    val imagesBase64Jpeg: List<String> = emptyList(),
)

data class VisionRequest(
    val systemPrompt: String,
    val messages: List<VisionMessage>,
    val maxTokens: Int = 1500,
)
