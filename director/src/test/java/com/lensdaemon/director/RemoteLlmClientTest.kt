package com.lensdaemon.director

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for RemoteLlmClient endpoint validation (SSRF protection).
 */
class RemoteLlmClientTest {

    private lateinit var client: RemoteLlmClient

    @Before
    fun setUp() {
        client = RemoteLlmClient(LlmConfig())
    }

    // ==================== Endpoint Validation Tests ====================

    @Test
    fun `validates public OpenAI endpoint`() {
        client.updateConfig(LlmConfig(endpoint = "https://api.openai.com/v1/chat/completions", apiKey = "sk-test"))
        val result = client.validateEndpoint("https://api.openai.com/v1/chat/completions")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `validates public Anthropic endpoint`() {
        client.updateConfig(LlmConfig(endpoint = "https://api.anthropic.com/v1/messages", apiKey = "sk-test"))
        val result = client.validateEndpoint("https://api.anthropic.com/v1/messages")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `allows localhost for Ollama provider`() {
        client.updateConfig(LlmConfig(endpoint = "http://localhost:11434"))
        val result = client.validateEndpoint("http://localhost:11434")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `allows 127_0_0_1 for Ollama provider`() {
        client.updateConfig(LlmConfig(endpoint = "http://127.0.0.1:11434"))
        val result = client.validateEndpoint("http://127.0.0.1:11434")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `blocks localhost for non-Ollama provider`() {
        client.updateConfig(LlmConfig(endpoint = "http://localhost:8080", apiKey = "key"))
        val result = client.validateEndpoint("http://localhost:8080")
        assertTrue(result.isFailure)
    }

    @Test
    fun `blocks private IP 192_168`() {
        client.updateConfig(LlmConfig(endpoint = "http://192.168.1.100:8080", apiKey = "key"))
        val result = client.validateEndpoint("http://192.168.1.100:8080")
        assertTrue(result.isFailure)
    }

    @Test
    fun `blocks private IP 10_x`() {
        client.updateConfig(LlmConfig(endpoint = "http://10.0.0.1:8080", apiKey = "key"))
        val result = client.validateEndpoint("http://10.0.0.1:8080")
        assertTrue(result.isFailure)
    }

    @Test
    fun `blocks private IP 172_16`() {
        client.updateConfig(LlmConfig(endpoint = "http://172.16.0.1:8080", apiKey = "key"))
        val result = client.validateEndpoint("http://172.16.0.1:8080")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects FTP protocol`() {
        val result = client.validateEndpoint("ftp://example.com/endpoint")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects file protocol`() {
        val result = client.validateEndpoint("file:///etc/passwd")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects invalid URL`() {
        val result = client.validateEndpoint("not-a-url")
        assertTrue(result.isFailure)
    }

    @Test
    fun `rejects empty URL`() {
        val result = client.validateEndpoint("")
        assertTrue(result.isFailure)
    }

    // ==================== Configuration Tests ====================

    @Test
    fun `isConfigured returns false with empty endpoint`() {
        val emptyClient = RemoteLlmClient(LlmConfig(endpoint = ""))
        assertFalse(emptyClient.isConfigured())
    }

    @Test
    fun `isConfigured returns false with empty api key for non-Ollama`() {
        val noKeyClient = RemoteLlmClient(LlmConfig(endpoint = "https://api.openai.com", apiKey = ""))
        assertFalse(noKeyClient.isConfigured())
    }

    @Test
    fun `isConfigured returns true for Ollama without api key`() {
        val ollamaClient = RemoteLlmClient(LlmConfig(endpoint = "http://localhost:11434"))
        assertTrue(ollamaClient.isConfigured())
    }

    @Test
    fun `isConfigured returns true with endpoint and api key`() {
        val configuredClient = RemoteLlmClient(LlmConfig(endpoint = "https://api.openai.com", apiKey = "sk-test"))
        assertTrue(configuredClient.isConfigured())
    }

    @Test
    fun `updateConfig changes provider detection`() {
        val client = RemoteLlmClient(LlmConfig(endpoint = "http://localhost:11434"))
        assertTrue(client.isConfigured())

        client.updateConfig(LlmConfig(endpoint = "https://api.openai.com", apiKey = "sk-test"))
        assertTrue(client.isConfigured())
    }
}
