package com.shilapi.xcertplay.network

/** Non-DFS channel choices. Android still enforces the radio's capabilities and country limits. */
object WifiP2pChannels {
    const val AUTO = 0
    val channels: List<Int> = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165) + (1..11)

    fun isValid(channel: Int): Boolean = channel == AUTO || channel in channels

    fun frequencyMhz(channel: Int): Int? {
        require(isValid(channel)) { "Unsupported Wi-Fi Direct channel: $channel" }
        return when {
            channel == AUTO -> null
            channel <= 11 -> 2407 + channel * 5
            else -> 5000 + channel * 5
        }
    }
}

internal class P2pChannelUnavailableException(channel: Int, details: String, cause: Throwable? = null) :
    java.io.IOException("Wi-Fi Direct could not use channel $channel. Choose Auto or another channel. $details", cause)
