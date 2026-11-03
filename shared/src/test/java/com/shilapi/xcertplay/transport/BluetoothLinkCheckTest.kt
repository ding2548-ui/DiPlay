package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothLinkCheckTest {
    // Logged on two head units where nothing ever arrived: an Allwinner T8 and the unit in issue 354.
    @Test fun anInstantConnectThatStaysSilentLooksUnreal() {
        for (millis in listOf(4L, 6L, 8L, 13L, 17L, 49L)) {
            assertTrue("$millis ms", BluetoothLinkCheck.looksUnreal(connectMillis = millis, bytesReceived = 0))
        }
    }

    // Logged on a head unit where wireless CarPlay works (report attached to issue 274).
    @Test fun aConnectThatTookAsLongAsARealOneDoesNot() {
        for (millis in listOf(50L, 100L, 111L, 117L, 162L, 407L, 2206L)) {
            assertFalse("$millis ms", BluetoothLinkCheck.looksUnreal(connectMillis = millis, bytesReceived = 0))
        }
    }

    @Test fun oneByteFromThePhoneSettlesIt() {
        assertFalse(BluetoothLinkCheck.looksUnreal(connectMillis = 6, bytesReceived = 1))
    }

    // On the Allwinner T8 a connect to a random service UUID "succeeded" in 12 ms.
    @Test fun onlyAnInstantControlConnectProvesTheLinkUnreal() {
        assertTrue(BluetoothLinkCheck.controlProvesUnreal(controlConnected = true, controlMillis = 12))
        // What a working stack does: the service is not found, however quickly that is known.
        assertFalse(BluetoothLinkCheck.controlProvesUnreal(controlConnected = false, controlMillis = 12))
        assertFalse(BluetoothLinkCheck.controlProvesUnreal(controlConnected = false, controlMillis = 3_000))
        // A connect that took as long as a real one is not evidence either way.
        assertFalse(BluetoothLinkCheck.controlProvesUnreal(controlConnected = true, controlMillis = 400))
    }

    // What every connect threw on an Allwinner T7 with Android 9, within 3 ms (issue 709).
    private val noSocketManager = NullPointerException(
        "Attempt to invoke interface method 'android.os.ParcelFileDescriptor " +
            "android.bluetooth.IBluetoothSocketManager.connectSocket(android.bluetooth.BluetoothDevice, int, " +
            "android.os.ParcelUuid, int, int)' on a null object reference",
    )

    @Test fun aMissingSocketManagerIsRecognisedHoweverAndroidReportsIt() {
        assertTrue(BluetoothLinkCheck.socketServiceMissing(noSocketManager))
        // Android 13 checks before it calls.
        assertTrue(BluetoothLinkCheck.socketServiceMissing(IOException("bt get socket manager failed")))
        // Wrapped, as it reached the report in issue 709.
        assertTrue(BluetoothLinkCheck.socketServiceMissing(IOException("Could not connect RFCOMM to phone", noSocketManager)))
    }

    // What a working stack throws when the phone is away, asleep or refuses, and unrelated faults.
    @Test fun otherFailuresAreNotAMissingSocketManager() {
        for (failure in listOf(
            IOException("read failed, socket might closed or timeout, read ret: -1"),
            IOException("Bluetooth is off"),
            IOException("socket closed"),
            IOException("Service discovery failed"),
            IOException(),
            NullPointerException("Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference"),
            NullPointerException(),
            SecurityException("Need android.permission.BLUETOOTH_CONNECT permission"),
            IllegalStateException("IBluetoothSocketManager"),
        )) {
            assertFalse(failure.toString(), BluetoothLinkCheck.socketServiceMissing(failure))
        }
    }

    @Test fun aMissingSocketManagerStopsTheAttemptsWithItsCause() {
        for (reported in listOf(noSocketManager, IOException("bt get socket manager failed"))) {
            val failure = BluetoothLinkCheck.connectFailure(reported, "phone-address")
            assertTrue(failure is WirelessStartupException)
            assertEquals(WirelessStartupFailure.BLUETOOTH_UNUSABLE, (failure as WirelessStartupException).reason)
            assertFalse(failure.reason.retryable)
            assertTrue(failure.message, failure.message!!.contains(BluetoothLinkCheck.NO_SOCKETS_MARK))
            // Nothing about the phone belongs in a failure that is the head unit's.
            assertFalse(failure.message, failure.message!!.contains("phone-address"))
            assertSame(reported, failure.cause)
        }
    }

    @Test fun everyOtherConnectFailureStaysWhatItWas() {
        val refused = IOException("read failed, socket might closed or timeout, read ret: -1")
        assertSame(refused, BluetoothLinkCheck.connectFailure(refused, "phone-address"))
        val other = IllegalStateException("not connected")
        val wrapped = BluetoothLinkCheck.connectFailure(other, "phone-address")
        assertFalse(wrapped is WirelessStartupException)
        assertEquals("Could not connect RFCOMM to phone-address", wrapped.message)
        assertSame(other, wrapped.cause)
    }
}
