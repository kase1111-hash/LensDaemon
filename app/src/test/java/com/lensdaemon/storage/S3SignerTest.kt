package com.lensdaemon.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import java.util.Date

/**
 * AWS Signature V4 for S3, checked against the examples AWS publishes in "Signature
 * Calculations for the Authorization Header: Transferring Payload in a Single Chunk"
 * (bucket examplebucket, us-east-1, 20130524T000000Z, the documented example keys).
 */
class S3SignerTest {

    private val client = S3Client(
        S3Credentials(
            endpoint = "examplebucket.s3.amazonaws.com",
            region = "us-east-1",
            bucket = "examplebucket",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        )
    )

    private val date = Date.from(Instant.parse("2013-05-24T00:00:00Z"))
    private val host = "https://examplebucket.s3.amazonaws.com"
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private fun sign(
        method: String,
        pathAndQuery: String,
        headers: Map<String, String> = emptyMap(),
        payloadHash: String = emptyHash
    ): S3Client.SignedRequest {
        val all = headers.toMutableMap()
        all["x-amz-content-sha256"] = payloadHash
        return client.signRequest(method, host + pathAndQuery, all, payloadHash, date)
    }

    private fun signature(request: S3Client.SignedRequest): String =
        request.headers.getValue("Authorization").substringAfter("Signature=")

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun getObject_matchesTheAwsExample() {
        val signed = sign("GET", "/test.txt", mapOf("Range" to "bytes=0-9"))

        val expectedCanonical = listOf(
            "GET",
            "/test.txt",
            "",
            "host:examplebucket.s3.amazonaws.com",
            "range:bytes=0-9",
            "x-amz-content-sha256:$emptyHash",
            "x-amz-date:20130524T000000Z",
            "",
            "host;range;x-amz-content-sha256;x-amz-date",
            emptyHash
        ).joinToString("\n")
        assertEquals(expectedCanonical, signed.canonicalRequest)
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", signature(signed))

        val authorization = signed.headers.getValue("Authorization")
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request"))
        assertTrue(authorization.contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"))
        assertEquals("20130524T000000Z", signed.headers["x-amz-date"])
        assertEquals("examplebucket.s3.amazonaws.com", signed.headers["Host"])
    }

    @Test
    fun putObject_matchesTheAwsExample() {
        val body = "Welcome to Amazon S3."
        val bodyHash = sha256Hex(body)
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", bodyHash)

        // The client builds object URLs from the key with uriEncode
        val path = "/" + uriEncode("test\$file.text", keepSlash = true)
        val signed = sign(
            "PUT",
            path,
            mapOf("Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-storage-class" to "REDUCED_REDUNDANCY"),
            bodyHash
        )

        val lines = signed.canonicalRequest.split("\n")
        assertEquals("/test%24file.text", lines[1])
        assertEquals("date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class", lines[lines.size - 2])
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd", signature(signed))
    }

    @Test
    fun getBucketLifecycle_matchesTheAwsExample() {
        val signed = sign("GET", "/?lifecycle")

        val lines = signed.canonicalRequest.split("\n")
        assertEquals("/", lines[1])
        assertEquals("lifecycle=", lines[2])
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543", signature(signed))
    }

    @Test
    fun listObjects_matchesTheAwsExample() {
        val signed = sign("GET", "/?max-keys=2&prefix=J")

        assertEquals("max-keys=2&prefix=J", signed.canonicalRequest.split("\n")[2])
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7", signature(signed))
    }

    @Test
    fun initiateMultipart_signsUploadsWithAnEmptyValue() {
        assertEquals("uploads=", canonicalQueryString("uploads"))

        val signed = sign("POST", "/examplebucket/clip.mp4?uploads")
        assertEquals("uploads=", signed.canonicalRequest.split("\n")[2])
    }

    @Test
    fun canonicalQuery_isSortedAndEncodedOnce() {
        assertEquals("", canonicalQueryString(null))
        assertEquals("", canonicalQueryString(""))
        assertEquals("partNumber=2&uploadId=a%2Bb%2Fc", canonicalQueryString("uploadId=a%2Bb%2Fc&partNumber=2"))
        assertEquals("prefix=my%20clips", canonicalQueryString("prefix=my%20clips"))
        // '+' in a URI query is a plus sign, not a space
        assertEquals("q=a%2Bb", canonicalQueryString("q=a+b"))
        assertEquals("a=1&a=2&b=", canonicalQueryString("b&a=2&a=1"))
    }

    @Test
    fun uriEncode_keepsOnlyUnreservedCharacters() {
        assertEquals("a%20b%281%29/c", uriEncode("a b(1)/c", keepSlash = true))
        assertEquals("a%20b%281%29%2Fc", uriEncode("a b(1)/c", keepSlash = false))
        assertEquals("AZaz09-_.~", uriEncode("AZaz09-_.~", keepSlash = false))
        assertEquals("%C3%BC%2B%3D%26", uriEncode("\u00FC+=&", keepSlash = false))
    }

    @Test
    fun objectKeyWithSpacesIsEncodedExactlyOnce() {
        val key = "front door/LensDaemon_Pixel (6)_20240101.mp4"
        val signed = sign("PUT", "/examplebucket/" + uriEncode(key, keepSlash = true))

        assertEquals(
            "/examplebucket/front%20door/LensDaemon_Pixel%20%286%29_20240101.mp4",
            signed.canonicalRequest.split("\n")[1]
        )
    }

    @Test
    fun everyRequestSendsAndSignsThePayloadHash() {
        // The connection test sends no body and sets no hash header itself
        val signed = client.createSignedRequest(
            "GET", "https://s3.us-east-1.amazonaws.com/examplebucket?max-keys=1", mutableMapOf(), ByteArray(0), date
        )

        assertEquals(emptyHash, signed.headers["x-amz-content-sha256"])
        assertTrue(signed.headers.getValue("Authorization").contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date"))
        val lines = signed.canonicalRequest.split("\n")
        assertEquals("/examplebucket", lines[1])
        assertEquals("max-keys=1", lines[2])
        assertEquals(emptyHash, lines.last())
    }

    @Test
    fun hostHeaderKeepsThePortAndLocalHostNames() {
        // A MinIO box on the LAN, addressed by a name java.net.URI would reject
        val signed = client.createSignedRequest(
            "GET", "http://my_minio:9000/examplebucket?max-keys=1", mutableMapOf(), ByteArray(0), date
        )

        assertEquals("my_minio:9000", signed.headers["Host"])
        assertTrue(signed.canonicalRequest.contains("\nhost:my_minio:9000\n"))
    }

    @Test
    fun errorText_carriesTheS3ErrorCode() {
        val body = "<?xml version=\"1.0\"?><Error><Code>SignatureDoesNotMatch</Code><Message>x</Message></Error>"
        assertEquals("HTTP 403 SignatureDoesNotMatch", s3ErrorText(403, body))
        assertEquals("HTTP 500", s3ErrorText(500, null))
        assertEquals("HTTP 400", s3ErrorText(400, "<html>Bad Request</html>"))
    }
}
