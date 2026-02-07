package com.lensdaemon.director

import kotlinx.coroutines.*
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote LLM Client (Ollama-only)
 *
 * Handles communication with a local Ollama instance for dynamic script interpretation.
 * Only supports localhost connections — no external API keys or cloud endpoints.
 */
class RemoteLlmClient(
    private var config: LlmConfig = LlmConfig()
) {
    companion object {
        private const val TAG = "RemoteLlmClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val DEFAULT_ENDPOINT = "http://localhost:11434"
    }

    /**
     * LLM response result
     */
    data class LlmResponse(
        val success: Boolean,
        val content: String,
        val tokensUsed: Int = 0,
        val latencyMs: Long = 0,
        val error: String? = null
    )

    /**
     * Parsed cue response from LLM
     */
    data class ParsedCueResponse(
        val cues: List<DirectorCue>,
        val rawResponse: String,
        val parseErrors: List<String> = emptyList()
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Update configuration
     */
    fun updateConfig(newConfig: LlmConfig) {
        config = newConfig
    }

    /**
     * Check if client is configured with a valid local endpoint
     */
    fun isConfigured(): Boolean {
        val endpoint = config.endpoint.ifEmpty { DEFAULT_ENDPOINT }
        return isLocalEndpoint(endpoint)
    }

    /**
     * Validate that an endpoint is localhost (the only allowed target).
     */
    fun validateEndpoint(endpoint: String): Result<Unit> {
        return try {
            val url = URL(endpoint)
            val protocol = url.protocol.lowercase()
            if (protocol != "http" && protocol != "https") {
                return Result.failure(IllegalArgumentException("Only HTTP/HTTPS protocols are allowed"))
            }
            if (!isLocalEndpoint(endpoint)) {
                return Result.failure(IllegalArgumentException("Only localhost endpoints are allowed (Ollama)"))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Invalid endpoint URL: ${e.message}"))
        }
    }

    private fun isLocalEndpoint(endpoint: String): Boolean {
        return try {
            val host = URL(endpoint).host.lowercase()
            host == "localhost" || host == "127.0.0.1" || host == "::1"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Interpret a script fragment and return camera cues
     */
    suspend fun interpretScript(scriptFragment: String): ParsedCueResponse {
        if (!isConfigured()) {
            return ParsedCueResponse(
                cues = emptyList(),
                rawResponse = "",
                parseErrors = listOf("LLM client not configured")
            )
        }

        val prompt = buildPrompt(scriptFragment)
        val response = sendRequest(prompt)

        if (!response.success) {
            return ParsedCueResponse(
                cues = emptyList(),
                rawResponse = response.content,
                parseErrors = listOf(response.error ?: "Unknown error")
            )
        }

        return parseLlmResponse(response.content)
    }

    /**
     * Interpret a single line or scene description
     */
    suspend fun interpretLine(line: String, context: String = ""): ParsedCueResponse {
        val fullPrompt = if (context.isNotEmpty()) {
            "Context: $context\n\nCurrent line: $line"
        } else {
            line
        }
        return interpretScript(fullPrompt)
    }

    /**
     * Test connection to Ollama endpoint
     */
    suspend fun testConnection(): Result<String> {
        if (!isConfigured()) {
            return Result.failure(IllegalStateException("LLM client not configured"))
        }

        return try {
            val response = sendRequest("Respond with only: OK")
            if (response.success) {
                Result.success("Connection successful (${response.latencyMs}ms)")
            } else {
                Result.failure(Exception(response.error ?: "Connection failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildPrompt(scriptFragment: String): String {
        return """${config.systemPrompt}

Script/Scene to interpret:
$scriptFragment

Output camera cues in the specified format, one per line."""
    }

    /**
     * Send request to Ollama endpoint
     */
    private suspend fun sendRequest(prompt: String): LlmResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val endpoint = config.endpoint.ifEmpty { DEFAULT_ENDPOINT }

        val validationResult = validateEndpoint(endpoint)
        if (validationResult.isFailure) {
            return@withContext LlmResponse(
                success = false,
                content = "",
                latencyMs = System.currentTimeMillis() - startTime,
                error = "Endpoint validation failed: ${validationResult.exceptionOrNull()?.message}"
            )
        }

        try {
            val url = if (endpoint.endsWith("/")) {
                "${endpoint}api/generate"
            } else if (!endpoint.contains("/api/")) {
                "${endpoint}/api/generate"
            } else {
                endpoint
            }

            val requestBody = JSONObject().apply {
                put("model", config.model)
                put("prompt", "${config.systemPrompt}\n\n$prompt")
                put("stream", false)
                put("options", JSONObject().apply {
                    put("temperature", config.temperature)
                    put("num_predict", config.maxTokens)
                })
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }

            val latency = System.currentTimeMillis() - startTime

            if (responseCode !in 200..299) {
                Timber.tag(TAG).e("Ollama request failed: $responseCode - $responseBody")
                return@withContext LlmResponse(
                    success = false,
                    content = responseBody,
                    latencyMs = latency,
                    error = "HTTP $responseCode: ${extractErrorMessage(responseBody)}"
                )
            }

            val json = JSONObject(responseBody)
            val content = json.optString("response", responseBody)
            val tokens = json.optInt("eval_count", 0)

            Timber.tag(TAG).d("Ollama request successful: $tokens tokens, ${latency}ms")

            LlmResponse(
                success = true,
                content = content,
                tokensUsed = tokens,
                latencyMs = latency
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Ollama request error")
            LlmResponse(
                success = false,
                content = "",
                latencyMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun extractErrorMessage(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            json.optString("error", responseBody.take(200))
        } catch (e: Exception) {
            responseBody.take(200)
        }
    }

    private fun parseLlmResponse(content: String): ParsedCueResponse {
        val parser = ScriptParser()
        val cues = mutableListOf<DirectorCue>()
        val errors = mutableListOf<String>()

        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val parsedCues = parser.parseCuesFromLine(trimmed)
                if (parsedCues.isNotEmpty()) {
                    cues.addAll(parsedCues)
                } else if (trimmed.startsWith("[") && trimmed.contains("]")) {
                    errors.add("Failed to parse: $trimmed")
                }
            }
        }

        return ParsedCueResponse(
            cues = cues,
            rawResponse = content,
            parseErrors = errors
        )
    }

    /**
     * Cancel all pending requests
     */
    fun cancelAll() {
        scope.coroutineContext.cancelChildren()
    }

    /**
     * Cleanup resources
     */
    fun destroy() {
        scope.cancel()
    }
}
