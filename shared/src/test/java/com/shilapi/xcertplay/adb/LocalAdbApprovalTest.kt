package com.shilapi.xcertplay.adb

import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class LocalAdbApprovalTest {
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test fun savedKeyCompletesWithoutAReplyOnTheOriginalConnection() {
        ApprovalServer().use { server ->
            val result = connectAndRun(server)
            assertTrue(server.keyOffered.await(3, TimeUnit.SECONDS))
            server.approved = true
            assertEquals("ok", result.get(5, TimeUnit.SECONDS))
            assertEquals(1, server.keyOffers.get())
            assertEquals(listOf("shell:echo ok"), server.commands)
            assertNull(server.failure.get())
        }
    }

    @Test fun quietChecksDoNotRepeatThePromptOrRunCommandsBeforeApproval() {
        ApprovalServer().use { server ->
            val result = connectAndRun(server)
            assertTrue(server.keyOffered.await(3, TimeUnit.SECONDS))
            assertTrue(server.quietCheck.await(3, TimeUnit.SECONDS))
            assertFalse(result.isDone)
            assertEquals(1, server.keyOffers.get())
            assertTrue(server.commands.isEmpty())
            server.approved = true
            assertEquals("ok", result.get(5, TimeUnit.SECONDS))
            assertEquals(1, server.keyOffers.get())
            assertNull(server.failure.get())
        }
    }

    @Test fun partialApprovalPacketSurvivesAQuietConnectionCheck() {
        ApprovalServer(fragmentReply = true).use { server ->
            assertEquals("ok", connectAndRun(server).get(5, TimeUnit.SECONDS))
            assertFalse(server.approved)
            assertEquals(1, server.keyOffers.get())
            assertEquals(listOf("shell:echo ok"), server.commands)
            assertNull(server.failure.get())
        }
    }

    private fun connectAndRun(server: ApprovalServer) = CompletableFuture.supplyAsync {
        LocalAdb(key, port = server.port).use { adb ->
            assertEquals(LocalAdb.Access.READY, adb.connect(mayAsk = true))
            adb.shell("echo ok")
        }
    }

    // 模拟车机保存密钥，却不向首次授权的连接发送 CNXN。
    private class ApprovalServer(private val fragmentReply: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0)
        val port = server.localPort
        @Volatile var approved = false
        @Volatile private var closed = false
        val keyOffered = CountDownLatch(1)
        val quietCheck = CountDownLatch(1)
        val keyOffers = AtomicInteger()
        val commands = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable>()
        private val sockets = CopyOnWriteArrayList<Socket>()
        private val workers = CopyOnWriteArrayList<Thread>()
        private val acceptor = thread(isDaemon = true) {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                sockets += socket
                workers += thread(isDaemon = true) {
                    runCatching { socket.use(::serve) }.onFailure {
                        if (!closed) failure.compareAndSet(null, it)
                    }
                }
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 8_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            fun send(packet: AdbPacket) = output.write(packet.encode())
            assertEquals(AdbPacket.CNXN, AdbPacket.read(input).command)
            send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)))
            assertEquals(AdbPacket.AUTH_SIGNATURE, AdbPacket.read(input).arg0)
            val connected = AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD,
                "device::\u0000".toByteArray())
            if (!approved) {
                send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)))
                val offer = runCatching { AdbPacket.read(input) }.getOrNull()
                if (offer == null) {
                    quietCheck.countDown()
                    return
                }
                assertEquals(AdbPacket.AUTH_PUBLIC_KEY, offer.arg0)
                keyOffers.incrementAndGet()
                keyOffered.countDown()
                if (!fragmentReply) {
                    assertEquals(-1, input.read())
                    return
                }
                val bytes = connected.encode()
                output.write(bytes, 0, 10)
                assertTrue(quietCheck.await(3, TimeUnit.SECONDS))
                output.write(bytes, 10, bytes.size - 10)
            } else send(connected)
            while (true) {
                val open = runCatching { AdbPacket.read(input) }.getOrNull() ?: return
                assertEquals(AdbPacket.OPEN, open.command)
                commands += String(open.payload).trimEnd('\u0000')
                send(AdbPacket(AdbPacket.OKAY, 77, open.arg0, ByteArray(0)))
                send(AdbPacket(AdbPacket.WRTE, 77, open.arg0, "ok".toByteArray()))
                assertEquals(AdbPacket.OKAY, AdbPacket.read(input).command)
                send(AdbPacket(AdbPacket.CLSE, 77, open.arg0, ByteArray(0)))
                assertEquals(AdbPacket.CLSE, AdbPacket.read(input).command)
            }
        }

        override fun close() {
            closed = true
            server.close()
            sockets.forEach { it.close() }
            acceptor.join(1_000)
            workers.forEach { it.join(1_000) }
        }
    }
}
