package com.shilapi.xcertplay.transport

/**
 * Switches the iPhone to its CarPlay configuration when a head-unit kernel driver holds an
 * interface of the current one.
 *
 * Linux refuses SET_CONFIGURATION while any interface of the active configuration is bound
 * ("usbfs: interface 0 claimed by snd-usb-audio while '…' sets config #6"). An 8227L kernel bound
 * snd-usb-audio to the iPhone's audio configuration, so every switch failed, the claims landed on
 * the wrong configuration and DiPlay reported "could not claim the NCM interface 3" (#518).
 * Android detaches a kernel driver only through a forced claim, so each interface number is
 * claimed with force. Android's releaseInterface() asks the kernel to reattach the driver
 * (USBDEVFS_CONNECT), which undid the detach before the switch in 0.2.19, so the claims are
 * dropped by closing the connection instead: usbfs releases them on close without reattaching.
 */
internal object UsbConfigurationSelection {
    data class Outcome(
        val target: Int,
        val selected: Boolean,
        val before: Int?,
        val after: Int?,
        val detached: List<Int>,
    ) {
        /** Android reported the switch as failed and the iPhone still reads another configuration. */
        val wrongConfiguration: Boolean get() = !selected && after != null && after != target

        fun diagnostic(): String =
            "USB configuration select target=$target ok=$selected before=${before ?: "unknown"} " +
                "detached=${detached.joinToString(",").ifEmpty { "none" }} after=${after ?: "unknown"}"
    }

    /**
     * Returns null when the first [setConfiguration] succeeds. Otherwise, unless
     * [activeConfiguration] already reads [target], detaches drivers from [interfaceIds] with a
     * claim that stays held, lets [reopen] close and reopen the connection to drop those claims,
     * and tries once more on the new connection.
     */
    fun select(
        target: Int,
        interfaceIds: List<Int>,
        setConfiguration: () -> Boolean,
        activeConfiguration: () -> Int?,
        detach: (Int) -> Boolean,
        reopen: () -> Unit,
    ): Outcome? {
        if (setConfiguration()) return null
        val before = activeConfiguration()
        if (before == target) return Outcome(target, selected = false, before, before, emptyList())
        val detached = interfaceIds.distinct().sorted().filter(detach)
        if (detached.isNotEmpty()) reopen()
        val selected = setConfiguration()
        return Outcome(target, selected, before, activeConfiguration(), detached)
    }
}
