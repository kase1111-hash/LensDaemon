package com.lensdaemon.director

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for RemoteLlmClient (Ollama-only) endpoint validation.
 */
class RemoteLlmClientTest {

    private lateinit var client: RemoteLlmClient

    @Before
    fun setUp() {
        client = RemoteLlmClient(LlmConfig())
    }

    // ==================== Endpoint Validation Tests ====================

    @Test
    fun `allows localhost for Ollama`() {
        client.updateConfig(LlmConfig(endpoint = "http://localhost:11434"))
        val result = client.validateEndpoint("http://localhost:11434")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `allows 127_0_0_1 for Ollama`() {
        client.updateConfig(LlmConfig(endpoint = "http://127.0.0.1:11434"))
        val result = client.validateEndpoint("http://127.0.0.1:11434")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `blocks remote endpoints`() {
        val result = client.validateEndpoint("https://api.openai.com/v1/chat/completions")
        assertTrue(result.isFailure)
    }

    @Test
    fun `blocks private IP 192_168`() {
        val result = client.validateEndpoint("http://192.168.1.100:8080")
        assertTrue(result.isFailure)
    }

    @Test
    fun `blocks private IP 10_x`() {
        val result = client.validateEndpoint("http://10.0.0.1:8080")
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
    fun `isConfigured returns true for default localhost`() {
        val defaultClient = RemoteLlmClient(LlmConfig())
        assertTrue(defaultClient.isConfigured())
    }

    @Test
    fun `isConfigured returns true for explicit localhost`() {
        val ollamaClient = RemoteLlmClient(LlmConfig(endpoint = "http://localhost:11434"))
        assertTrue(ollamaClient.isConfigured())
    }

    @Test
    fun `isConfigured returns false for remote endpoint`() {
        val remoteClient = RemoteLlmClient(LlmConfig(endpoint = "https://api.openai.com"))
        assertFalse(remoteClient.isConfigured())
    }

    @Test
    fun `updateConfig changes endpoint`() {
        val client = RemoteLlmClient(LlmConfig(endpoint = "https://api.openai.com"))
        assertFalse(client.isConfigured())

        client.updateConfig(LlmConfig(endpoint = "http://localhost:11434"))
        assertTrue(client.isConfigured())
    }
}
