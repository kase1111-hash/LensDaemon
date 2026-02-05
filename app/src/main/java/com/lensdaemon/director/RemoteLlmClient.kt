package com.lensdaemon.director

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Remote LLM Client
 *
 * Handles communication with external LLM APIs for dynamic script interpretation.
 * Supports multiple providers:
 * - OpenAI (GPT-4, GPT-3.5)
 * - Anthropic (Claude)
 * - Local Ollama instances
 * - Any OpenAI-compatible endpoint
 */
class RemoteLlmClient(
    private var config: LlmConfig = LlmConfig()
) {
    companion object {
        private const val TAG = "RemoteLlmClient"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        // Provider detection patterns
        private val OPENAI_PATTERN = Regex("openai\\.com|api\\.openai", RegexOption.IGNORE_CASE)
        private val ANTHROPIC_PATTERN = Regex("anthropic\\.com|api\\.anthropic", RegexOption.IGNORE_CASE)
        private val OLLAMA_PATTERN = Regex("localhost|127\\.0\\.0\\.1|ollama", RegexOption.IGNORE_CASE)
    }

    /**
     * LLM provider type
     */
    enum class LlmProvider {
        OPENAI,
        ANTHROPIC,
        OLLAMA,
        GENERIC_OPENAI  // OpenAI-compatible endpoints
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
    private var provider: LlmProvider = LlmProvider.GENERIC_OPENAI

    init {
        detectProvider()
    }

    /**
     * Update configuration
     */
    fun updateConfig(newConfig: LlmConfig) {
        config = newConfig
        detectProvider()
    }

    /**
     * Detect provider from endpoint URL
     */
    private fun detectProvider() {
        provider = when {
            OPENAI_PATTERN.containsMatchIn(config.endpoint) -> LlmProvider.OPENAI
            ANTHROPIC_PATTERN.containsMatchIn(config.endpoint) -> LlmProvider.ANTHROPIC
            OLLAMA_PATTERN.containsMatchIn(config.endpoint) -> LlmProvider.OLLAMA
            else -> LlmProvider.GENERIC_OPENAI
        }
        Timber.tag(TAG).d("Detected provider: $provider from endpoint: ${config.endpoint}")
    }

    /**
     * Check if client is configured
     */
    fun isConfigured(): Boolean {
        return config.endpoint.isNotEmpty() &&
                (config.apiKey.isNotEmpty() || provider == LlmProvider.OLLAMA)
    }

    /**
     * Validate that an endpoint URL is safe (not targeting private/internal networks).
     * Allows localhost only for Ollama provider.
     * Returns true if the URL is safe to connect to.
     */
    fun validateEndpoint(endpoint: String): Result<Unit> {
        return try {
            val url = URL(endpoint)
            val protocol = url.protocol.lowercase()

            // Only allow HTTP/HTTPS
            if (protocol != "http" && protocol != "https") {
                return Result.failure(IllegalArgumentException("Only HTTP/HTTPS protocols are allowed"))
            }

            // Require HTTPS for non-local endpoints
            val host = url.host.lowercase()
            val isLocalHost = host == "localhost" || host == "127.0.0.1" || host == "::1"

            if (isLocalHost) {
                // Only allow local connections for Ollama
                if (provider != LlmProvider.OLLAMA) {
                    return Result.failure(IllegalArgumentException("Local endpoints only allowed for Ollama provider"))
                }
                return Result.success(Unit)
            }

            // Block private IP ranges for non-local hosts
            val address = InetAddress.getByName(host)
            if (address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress) {
                return Result.failure(IllegalArgumentException("Private/internal network addresses are not allowed"))
            }

            // Warn (but allow) HTTP for remote endpoints
            if (protocol == "http") {
                Timber.tag(TAG).w("Using HTTP for remote LLM endpoint - HTTPS recommended")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("Invalid endpoint URL: ${e.message}"))
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

        // Parse the LLM response into cues
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
     * Test connection to LLM endpoint
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

    /**
     * Build the prompt for the LLM
     */
    private fun buildPrompt(scriptFragment: String): String {
        return """${config.systemPrompt}

Script/Scene to interpret:
$scriptFragment

Output camera cues in the specified format, one per line."""
    }

    /**
     * Send request to LLM endpoint
     */
    private suspend fun sendRequest(prompt: String): LlmResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        // Validate endpoint before connecting
        val validationResult = validateEndpoint(config.endpoint)
        if (validationResult.isFailure) {
            return@withContext LlmResponse(
                success = false,
                content = "",
                latencyMs = System.currentTimeMillis() - startTime,
                error = "Endpoint validation failed: ${validationResult.exceptionOrNull()?.message}"
            )
        }

        try {
            val (url, requestBody) = when (provider) {
                LlmProvider.OPENAI, LlmProvider.GENERIC_OPENAI -> buildOpenAiRequest(prompt)
                LlmProvider.ANTHROPIC -> buildAnthropicRequest(prompt)
                LlmProvider.OLLAMA -> buildOllamaRequest(prompt)
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")

                // Set auth header based on provider
                when (provider) {
                    LlmProvider.OPENAI, LlmProvider.GENERIC_OPENAI -> {
                        setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                    }
                    LlmProvider.ANTHROPIC -> {
                        setRequestProperty("x-api-key", config.apiKey)
                        setRequestProperty("anthropic-version", "2023-06-01")
                    }
                    LlmProvider.OLLAMA -> {
                        // No auth needed for local Ollama
                    }
                }
            }

            // Write request body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
            }

            // Read response
            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use(BufferedReader::readText)
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            }

            val latency = System.currentTimeMillis() - startTime

            if (responseCode !in 200..299) {
                Timber.tag(TAG).e("LLM request failed: $responseCode - $responseBody")
                return@withContext LlmResponse(
                    success = false,
                    content = responseBody,
                    latencyMs = latency,
                    error = "HTTP $responseCode: ${extractErrorMessage(responseBody)}"
                )
            }

            // Parse response based on provider
            val (content, tokens) = parseResponse(responseBody)

            Timber.tag(TAG).d("LLM request successful: ${tokens} tokens, ${latency}ms")

            LlmResponse(
                success = true,
                content = content,
                tokensUsed = tokens,
                latencyMs = latency
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "LLM request error")
            LlmResponse(
                success = false,
                content = "",
                latencyMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Build OpenAI-format request
     */
    private fun buildOpenAiRequest(prompt: String): Pair<String, String> {
        val url = if (config.endpoint.endsWith("/")) {
            "${config.endpoint}v1/chat/completions"
        } else if (!config.endpoint.contains("/v1/")) {
            "${config.endpoint}/v1/chat/completions"
        } else {
            config.endpoint
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", config.systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        return Pair(url, body.toString())
    }

    /**
     * Build Anthropic-format request
     */
    private fun buildAnthropicRequest(prompt: String): Pair<String, String> {
        val url = if (config.endpoint.endsWith("/")) {
            "${config.endpoint}v1/messages"
        } else if (!config.endpoint.contains("/v1/")) {
            "${config.endpoint}/v1/messages"
        } else {
            config.endpoint
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("system", config.systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        return Pair(url, body.toString())
    }

    /**
     * Build Ollama-format request
     */
    private fun buildOllamaRequest(prompt: String): Pair<String, String> {
        val url = if (config.endpoint.endsWith("/")) {
            "${config.endpoint}api/generate"
        } else if (!config.endpoint.contains("/api/")) {
            "${config.endpoint}/api/generate"
        } else {
            config.endpoint
        }

        val body = JSONObject().apply {
            put("model", config.model)
            put("prompt", "${config.systemPrompt}\n\n$prompt")
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", config.temperature)
                put("num_predict", config.maxTokens)
            })
        }

        return Pair(url, body.toString())
    }

    /**
     * Parse response based on provider format
     */
    private fun parseResponse(responseBody: String): Pair<String, Int> {
        return try {
            val json = JSONObject(responseBody)

            when (provider) {
                LlmProvider.OPENAI, LlmProvider.GENERIC_OPENAI -> {
                    val content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    val tokens = json.optJSONObject("usage")?.optInt("total_tokens", 0) ?: 0
                    Pair(content, tokens)
                }
                LlmProvider.ANTHROPIC -> {
                    val content = json.getJSONArray("content")
                        .getJSONObject(0)
                        .getString("text")
                    val usage = json.optJSONObject("usage")
                    val tokens = (usage?.optInt("input_tokens", 0) ?: 0) +
                            (usage?.optInt("output_tokens", 0) ?: 0)
                    Pair(content, tokens)
                }
                LlmProvider.OLLAMA -> {
                    val content = json.getString("response")
                    val tokens = json.optInt("eval_count", 0)
                    Pair(content, tokens)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse response")
            Pair(responseBody, 0)
        }
    }

    /**
     * Extract error message from response
     */
    private fun extractErrorMessage(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("error")
                ?: responseBody.take(200)
        } catch (e: Exception) {
            responseBody.take(200)
        }
    }

    /**
     * Parse LLM response into director cues
     */
    private fun parseLlmResponse(content: String): ParsedCueResponse {
        val parser = ScriptParser()
        val cues = mutableListOf<DirectorCue>()
        val errors = mutableListOf<String>()

        // Split response into lines and parse each
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val parsedCues = parser.parseCuesFromLine(trimmed)
                if (parsedCues.isNotEmpty()) {
                    cues.addAll(parsedCues)
                } else if (trimmed.startsWith("[") && trimmed.contains("]")) {
                    // Looks like a cue but didn't parse
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
     * Get provider type
     */
    fun getProvider(): LlmProvider = provider

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
