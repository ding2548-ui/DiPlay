package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.IOException

/**
 * Tells a real RFCOMM link from one a head unit only claims.
 *
 * Reaching a phone takes a page, a service search and a channel setup: hundreds of milliseconds at
 * best. Some head units keep calls and music on the maker's own Bluetooth module and leave Android
 * an adapter whose sockets "connect" at once and never carry a byte. Waiting for iAP2 there tells
 * the driver nothing, and no retry can change it.
 */
object BluetoothLinkCheck {
    /** True for a connect that returned at once and has delivered nothing since. */
    fun looksUnreal(connectMillis: Long, bytesReceived: Long): Boolean =
        connectMillis < INSTANT_CONNECT_MILLIS && bytesReceived == 0L

    /**
     * The control experiment's answer. A real stack has to find a service on the phone before it
     * can connect to it, so a connect to a service no phone offers cannot succeed; where it does,
     * and at once, the adapter is accepting connections that go nowhere.
     */
    fun controlProvesUnreal(controlConnected: Boolean, controlMillis: Long): Boolean =
        controlConnected && controlMillis < INSTANT_CONNECT_MILLIS

    /**
     * A connect that returns sooner than this did not reach a phone. Units whose links are not
     * real answered in 4 to 17 ms; a unit with working Bluetooth never took less than 100 ms.
     */
    const val INSTANT_CONNECT_MILLIS = 50L

    /**
     * How long an instant link may stay silent before the claim is put to a test. A link that
     * fails or ends sooner without a byte is tested at that moment instead.
     */
    const val SILENCE_MILLIS = 12_000L

    /** The words of the failure that reports it; the host screen matches on them. */
    const val UNREAL_MARK = "connections that are not real"

    /**
     * True when Android's Bluetooth service handed BluetoothSocket no socket manager, so no app on
     * the head unit can open an RFCOMM link, whatever the phone does. An Allwinner T7 on Android 9
     * failed every connect this way within 3 ms while the car's own calls and music kept working
     * (#709). Android 9 to 11 call through the missing manager and throw a NullPointerException
     * that names it; Android 13 checks first and throws "bt get socket manager failed".
     */
    fun socketServiceMissing(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }.take(4).any {
            val message = it.message.orEmpty()
            (it is NullPointerException && "IBluetoothSocketManager" in message) ||
                (it is IOException && "get socket manager failed" in message)
        }

    /**
     * What a failed RFCOMM connect is reported as. A missing socket manager cannot be retried
     * away, so it stops the attempts with its cause; anything else stays the IOException it was.
     */
    fun connectFailure(failure: Throwable, address: String): IOException = when {
        socketServiceMissing(failure) -> WirelessStartupException(
            WirelessStartupFailure.BLUETOOTH_UNUSABLE,
            "Android Bluetooth on this head unit $NO_SOCKETS_MARK: its Bluetooth service has no socket manager",
        ).apply { initCause(failure) }
        failure is IOException -> failure
        else -> IOException("Could not connect RFCOMM to $address", failure)
    }

    /** The words of that failure; the host screen matches on them. */
    const val NO_SOCKETS_MARK = "cannot open connections for apps"
}
