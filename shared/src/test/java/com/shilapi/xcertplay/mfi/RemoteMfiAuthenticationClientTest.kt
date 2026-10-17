package com.shilapi.xcertplay.mfi

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.shilapi.xcertplay.iap2.message.Iap2AuthenticationMessages
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class RemoteMfiAuthenticationClientTest {
    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverExecutor = Executors.newCachedThreadPool()
        server.executor = serverExecutor
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
        serverExecutor.shutdownNow()
    }

    @Test
    fun resetsLoadsAndCachesCertificateThenSignsWithStableRequestId() {
        val certificate = ByteArray(300) { it.toByte() }
        val signature = ByteArray(64) { (0xa0 + it).toByte() }
        val certificateRequests = AtomicInteger()
        val signRequests = Collections.synchronizedList(mutableListOf<String>())

        server.createContext("/mfi/reset") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer test-token", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("{}", exchange.requestBody.readText())
            exchange.respond(200, "{\"detail\":\"\"}")
        }
        server.createContext("/mfi/certificate") { exchange ->
            certificateRequests.incrementAndGet()
            val encoded = Base64.getEncoder().encodeToString(certificate)
            val digest = MessageDigest.getInstance("SHA-256").digest(certificate).toHex()
            exchange.respond(
                200,
                "{\"protocolMajor\":3,\"certificate\":\"$encoded\"," +
                    "\"certificateSha256\":\"$digest\"}",
            )
        }
        server.createContext("/mfi/sign") { exchange ->
            val body = exchange.requestBody.readText()
            signRequests += body
            if (signRequests.size == 1) {
                // Longer than the read timeout, so the client retries with the same request ID.
                Thread.sleep(1_500)
            }
            exchange.respond(
                200,
                "{\"signature\":\"${Base64.getEncoder().encodeToString(signature)}\"}",
            )
        }

        val client = RemoteMfiAuthenticationClient(
            serverAddress = serverAddress(),
            token = "test-token",
            // 50 ms timed out the unretried reset on a busy CI runner; only the delayed sign must.
            readTimeoutMillis = 750,
        )
        client.reset()
        assertEquals(3, client.protocolMajor())
        assertArrayEquals(certificate, client.readCertificate())
        assertEquals(1, certificateRequests.get())

        val challenge = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(signature, client.signChallenge(challenge))
        assertEquals(2, signRequests.size)
        val firstRequestId = RemoteMfiJson.string(signRequests[0], "requestId")
        val secondRequestId = RemoteMfiJson.string(signRequests[1], "requestId")
        assertEquals(firstRequestId, secondRequestId)
        assertEquals(
            Base64.getEncoder().encodeToString(challenge),
            RemoteMfiJson.string(signRequests[0], "challenge"),
        )
    }

    @Test
    fun rejectsCertificateWhenSha256DoesNotMatch() {
        server.createContext("/mfi/certificate") { exchange ->
            exchange.respond(
                200,
                "{\"protocolMajor\":3,\"certificate\":\"AQID\"," +
                    "\"certificateSha256\":\"${"00".repeat(32)}\"}",
            )
        }

        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).readCertificate()
        }
        assertEquals(
            "Remote certificate SHA-256 does not match certificateSha256",
            error.message,
        )
    }

    @Test
    fun loadsBaaPackageAndBuildsShowcaseIap2Payload() {
        val leaf = ByteArray(40) { (0x10 + it).toByte() }
        val intermediate = ByteArray(60) { (0x80 + it).toByte() }
        val packageBytes = u32(leaf.size) + u32(intermediate.size) + leaf + intermediate

        server.createContext("/mfi/certificate") { exchange ->
            val encoded = Base64.getEncoder().encodeToString(packageBytes)
            val digest = MessageDigest.getInstance("SHA-256").digest(packageBytes).toHex()
            exchange.respond(
                200,
                "{\"type\":\"baa\",\"protocolMajor\":3,\"certificate\":\"$encoded\"," +
                    "\"certificateSha256\":\"$digest\"}",
            )
        }

        val client = RemoteMfiAuthenticationClient(serverAddress())
        assertEquals(MfiCertificateType.BAA, client.certificateType)
        assertArrayEquals(
            Iap2AuthenticationMessages.baaCertificatePackage(leaf, intermediate).payload,
            client.readCertificate(),
        )
        val certificates = client.baaCertificates()
        assertArrayEquals(leaf, certificates.leaf)
        assertArrayEquals(intermediate, certificates.intermediate)
    }

    @Test
    fun rejectsUnknownCertificateType() {
        server.createContext("/mfi/certificate") { exchange ->
            exchange.respond(
                200,
                "{\"type\":\"unknown\",\"protocolMajor\":3,\"certificate\":\"AQID\"," +
                    "\"certificateSha256\":\"${"00".repeat(32)}\"}",
            )
        }

        val error = assertThrows(MfiInvalidDataException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).readCertificate()
        }
        assertEquals("Unsupported remote MFI certificate type 'unknown'", error.message)
    }

    @Test
    fun resetSurfacesHttp500DetailWithoutRetrying() {
        val requests = AtomicInteger()
        server.createContext("/mfi/reset") { exchange ->
            requests.incrementAndGet()
            exchange.respond(500, "{\"detail\":\"chip reset failed\"}")
        }

        val error = assertThrows(RemoteMfiHttpException::class.java) {
            RemoteMfiAuthenticationClient(serverAddress()).reset()
        }
        assertEquals(500, error.statusCode)
        assertEquals("chip reset failed", error.detail)
        assertEquals(1, requests.get())
    }

    private fun serverAddress(): String = "http://127.0.0.1:${server.address.port}"

    private fun HttpExchange.respond(statusCode: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(statusCode, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    private fun java.io.InputStream.readText(): String =
        use { String(it.readBytes(), StandardCharsets.UTF_8) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}
