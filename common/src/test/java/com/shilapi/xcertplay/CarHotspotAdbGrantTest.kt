package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.adb.LocalAdb
import java.io.DataInputStream
import java.io.InputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [CarHotspotAdbGrantTest.WritePermission::class])
class CarHotspotAdbGrantTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Before fun reset() {
        WritePermission.allowed = false
        ShadowSettings.setCanDrawOverlays(false)
    }

    @Test fun unapprovedProbeDoesNotOfferTheKeyOrRunShellCommands() {
        FakeAdbd(knownKey = false).use { server ->
            assertEquals(LocalAdb.Access.NOT_APPROVED, CarHotspotSetup.check(context, server.client()))
            server.await()
            assertFalse(server.offeredKey)
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test fun authorizedProbeDoesNotReadVehicleDataOrGrantPermissions() {
        FakeAdbd().use { server ->
            assertEquals(LocalAdb.Access.READY, CarHotspotSetup.check(context, server.client()))
            server.await()
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test fun explicitHotspotGrantOffersTheKeyAndOnlyRequestsWriteSettings() {
        FakeAdbd(knownKey = false, onCommand = { WritePermission.allowed = true }).use { server ->
            assertEquals(LocalAdb.Access.READY,
                CarHotspotSetup.grant(context, listOf(CarHotspotSetup.Permission.HOTSPOT), server.client()))
            server.await()
            assertTrue(server.offeredKey)
            assertEquals(listOf("appops set ${context.packageName} WRITE_SETTINGS allow"), server.commands)
            assertTrue(CarHotspotSetup.Permission.HOTSPOT.granted(context))
            assertFalse(CarHotspotSetup.Permission.BOOT_LAUNCH.granted(context))
        }
    }

    @Test fun emptyShellOutputDoesNotImplyPermissionWasGranted() {
        FakeAdbd().use { server ->
            assertEquals(LocalAdb.Access.READY,
                CarHotspotSetup.grant(context, listOf(CarHotspotSetup.Permission.HOTSPOT), server.client()))
            assertFalse(CarHotspotSetup.Permission.HOTSPOT.granted(context))
        }
    }

    @Test fun bootPermissionIsASeparateExplicitGrant() {
        FakeAdbd(onCommand = { ShadowSettings.setCanDrawOverlays(true) }).use { server ->
            CarHotspotSetup.grant(context, listOf(CarHotspotSetup.Permission.BOOT_LAUNCH), server.client())
            server.await()
            assertEquals(listOf("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"), server.commands)
            assertTrue(CarHotspotSetup.Permission.BOOT_LAUNCH.granted(context))
            assertFalse(CarHotspotSetup.Permission.HOTSPOT.granted(context))
        }
    }

    @Test fun existingPermissionIsNotGrantedAgain() {
        WritePermission.allowed = true
        FakeAdbd().use { server ->
            CarHotspotSetup.grant(context, listOf(CarHotspotSetup.Permission.HOTSPOT), server.client())
            server.await()
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test fun permissionsAreNotRequestedUntilTheNewKeyIsApproved() {
        val approval = CountDownLatch(1)
        FakeAdbd(knownKey = false, approval = approval, onCommand = { WritePermission.allowed = true }).use { server ->
            val result = AtomicReference<LocalAdb.Access>()
            val client = thread {
                result.set(CarHotspotSetup.grant(context, listOf(CarHotspotSetup.Permission.HOTSPOT), server.client()))
            }
            try {
                assertTrue(server.keyOffered.await(3, TimeUnit.SECONDS))
                assertTrue(client.isAlive)
                assertTrue(server.commands.isEmpty())
                assertFalse(WritePermission.allowed)
            } finally {
                approval.countDown()
                client.join(3_000)
            }
            assertFalse(client.isAlive)
            assertEquals(LocalAdb.Access.READY, result.get())
            assertTrue(WritePermission.allowed)
        }
    }

    @Test fun deniedKeyDoesNotRunAnyPermissionCommand() {
        FakeAdbd(knownKey = false, approveKey = false).use { server ->
            assertEquals(LocalAdb.Access.NOT_APPROVED, CarHotspotSetup.grant(context,
                CarHotspotSetup.Permission.entries, server.client()))
            assertTrue(server.commands.isEmpty())
            assertFalse(WritePermission.allowed)
        }
    }

    @Test fun bothPermissionsUseTheSameApprovedConnection() {
        FakeAdbd(knownKey = false, onCommand = { command ->
            if ("WRITE_SETTINGS" in command) WritePermission.allowed = true
            if ("SYSTEM_ALERT_WINDOW" in command) ShadowSettings.setCanDrawOverlays(true)
        }).use { server ->
            assertEquals(LocalAdb.Access.READY, CarHotspotSetup.grant(context,
                CarHotspotSetup.Permission.entries, server.client()))
            assertEquals(listOf("appops set ${context.packageName} WRITE_SETTINGS allow",
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"), server.commands)
            assertTrue(CarHotspotSetup.Permission.entries.all { it.granted(context) })
        }
    }

    @Test fun ordinaryVehicleSwitchOnlyConnectsWithoutGrantingAppPermissions() {
        FakeAdbd(knownKey = false).use { server ->
            assertEquals(LocalAdb.Access.READY, CarHotspotSetup.grant(context, emptyList(), server.client()))
            assertTrue(server.offeredKey)
            assertTrue(server.commands.isEmpty())
        }
    }

    @Implements(Settings.System::class)
    class WritePermission {
        companion object {
            @Volatile var allowed = false
            @JvmStatic @Implementation fun canWrite(context: Context): Boolean = allowed
        }
    }

    private inner class FakeAdbd(
        knownKey: Boolean = true,
        approveKey: Boolean = true,
        approval: CountDownLatch? = null,
        onCommand: (String) -> Unit = {},
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val commands = CopyOnWriteArrayList<String>()
        @Volatile var offeredKey = false
        val keyOffered = CountDownLatch(1)
        private val worker = thread(isDaemon = true) {
            server.accept().use { socket ->
                socket.soTimeout = 2_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                fun send(packet: AdbPacket) = output.write(packet.encode())
                AdbPacket.read(input)
                if (!knownKey) {
                    send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)))
                    AdbPacket.read(input)
                    send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_TOKEN, 0, ByteArray(20)))
                    val offer = runCatching { AdbPacket.read(input) }.getOrNull() ?: return@use
                    offeredKey = offer.arg0 == AdbPacket.AUTH_PUBLIC_KEY
                    keyOffered.countDown()
                    if (approval != null) check(approval.await(3, TimeUnit.SECONDS))
                    if (!approveKey) {
                        send(AdbPacket(AdbPacket.CLSE, 0, 0, ByteArray(0)))
                        return@use
                    }
                }
                send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, AdbPacket.MAX_PAYLOAD, "device::\u0000".toByteArray()))
                while (true) {
                    val open = runCatching { AdbPacket.read(input) }.getOrNull() ?: return@use
                    val command = String(open.payload).removePrefix("shell:").trimEnd('\u0000')
                    commands += command
                    onCommand(command)
                    send(AdbPacket(AdbPacket.OKAY, 77, open.arg0, ByteArray(0)))
                    send(AdbPacket(AdbPacket.CLSE, 77, open.arg0, ByteArray(0)))
                    AdbPacket.read(input)
                }
            }
        }

        fun client() = LocalAdb(key, port = server.localPort)
        fun await() { worker.join(3_000); assertFalse("Fake adbd did not finish", worker.isAlive) }
        override fun close() { await(); server.close() }
    }

    // Independent wire fixture: the protocol codec is internal to the shared module.
    private class AdbPacket(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
        fun encode(): ByteArray = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 0xff }).putInt(command.inv()).put(payload).array()

        companion object {
            const val AUTH = 0x48545541
            const val CNXN = 0x4e584e43
            const val OKAY = 0x59414b4f
            const val CLSE = 0x45534c43
            const val AUTH_TOKEN = 1
            const val AUTH_PUBLIC_KEY = 3
            const val VERSION = 0x01000000
            const val MAX_PAYLOAD = 4096

            fun read(input: InputStream): AdbPacket {
                val data = DataInputStream(input)
                val header = ByteBuffer.wrap(ByteArray(24).also(data::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                val command = header.int
                val arg0 = header.int
                val arg1 = header.int
                val payload = ByteArray(header.int).also(data::readFully)
                return AdbPacket(command, arg0, arg1, payload)
            }
        }
    }
}
