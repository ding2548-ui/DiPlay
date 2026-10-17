package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayStatus

/**
 * Maps CarPlay statuses onto the step numbering of [ConnectionSteps], the same
 * mapping FreeAuto's ConnectionStepMap uses (statuses this tree does not emit
 * are simply absent). Returns null when a status does not advance a step.
 */
object ConnectionStepMap {
    fun forStatus(wireless: Boolean, status: CarPlayStatus): Int? = if (wireless) {
        when (status) {
            CarPlayStatus.DiscoveringMfi,
            CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission,
            CarPlayStatus.MfiReady -> 10
            CarPlayStatus.StartingHotspot -> 32
            is CarPlayStatus.HotspotReady -> 32
            CarPlayStatus.WaitingForPairedIphone -> 13
            CarPlayStatus.ConnectingBluetooth -> 17
            CarPlayStatus.RunningWireless -> 33
            CarPlayStatus.WirelessActive,
            CarPlayStatus.WirelessActiveFallback -> ConnectionSteps.WIRELESS_PICTURE
            else -> null
        }
    } else {
        when (status) {
            CarPlayStatus.DiscoveringMfi,
            CarPlayStatus.WaitingForMfi,
            CarPlayStatus.RequestingMfiPermission,
            CarPlayStatus.MfiReady -> 2
            CarPlayStatus.DiscoveringIphone,
            CarPlayStatus.WaitingForIphone -> 3
            CarPlayStatus.RequestingIphonePermission -> 4
            CarPlayStatus.WaitingForReenumeration,
            CarPlayStatus.SelectingConfiguration -> 7
            CarPlayStatus.OpeningDataPaths -> 11
            CarPlayStatus.ConnectingControl -> 12
            CarPlayStatus.Pairing -> 16
            CarPlayStatus.AttachingNetwork -> 20
            CarPlayStatus.RunningControl -> 23
            else -> null
        }
    }
}
