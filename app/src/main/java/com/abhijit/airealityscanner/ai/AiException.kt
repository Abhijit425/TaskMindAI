package com.abhijit.airealityscanner.ai

/** Errors surfaced by the AI layer, each with a message that can be shown to the user directly. */
sealed class AiException(message: String) : Exception(message) {
    class ConsentRequired :
        AiException("Cloud image processing needs your permission before any image is uploaded.")

    class MissingApiKey(provider: String) :
        AiException("No API key is configured for $provider. Add one in Settings → AI provider.")

    class Offline :
        AiException("No internet connection. Try again when online, or switch to the on-device provider in Settings.")

    class Network(detail: String?) :
        AiException("Network error while contacting the AI service${detail?.let { ": $it" } ?: "."}")

    class Http(val code: Int, provider: String, detail: String) :
        AiException(describe(code, provider, detail))

    class InvalidResponse(detail: String) : AiException(detail)

    class Unsupported(detail: String) : AiException(detail)

    companion object {
        fun describe(code: Int, provider: String, detail: String): String {
            val hint = when (code) {
                400 -> "The request was rejected. Check the model name in Settings."
                401 -> "The API key was rejected. Check it in Settings."
                403 -> "This API key isn't allowed to use that model or feature."
                404 -> "Model or endpoint not found. Check the model name / base URL in Settings."
                413 -> "The image is too large. Lower 'Max upload size' in Settings."
                429 -> "Rate limit or quota reached. Wait a moment and try again."
                500, 502, 503, 529 -> "$provider is temporarily unavailable or overloaded. Try again shortly."
                else -> "$provider returned an error."
            }
            val extra = detail.trim().take(240)
            return if (extra.isNotEmpty()) "$hint (HTTP $code: $extra)" else "$hint (HTTP $code)"
        }
    }
}
