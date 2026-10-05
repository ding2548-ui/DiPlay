package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test

class HotspotJoinTransactionTest {
    private data class Config(val credentials: String = "synthetic", val vendors: List<String> = listOf("other"))
    private class Device : HotspotJoinTransaction.Port<Config> {
        var current = Config()
        var off: Boolean? = true
        var firmware = "firmware-a"
        var writes = 0
        var capabilitiesUnavailable = false
        var accepted = true
        var afterWrite: (() -> Unit)? = null
        var afterRead: (() -> Unit)? = null
        var readFails = false
        override fun firmware() = firmware
        override fun read(): Config {
            if (readFails) throw java.io.IOException()
            val snapshot = current; afterRead?.invoke(); return snapshot
        }
        override fun repaired(config: Config): Config {
            if (capabilitiesUnavailable) throw SecurityException("synthetic unsupported API")
            return if ("apple" in config.vendors) config else config.copy(vendors = config.vendors + "apple")
        }
        override fun hotspotOff() = off
        override fun write(config: Config): Boolean { writes++; current = config; afterWrite?.invoke(); return accepted }
    }
    private class Store : HotspotJoinTransaction.Store<Config> {
        var saved: HotspotJoinTransaction.Record<Config>? = null
        var failSave = false
        var afterSave: (() -> Unit)? = null
        override fun load() = saved
        override fun save(record: HotspotJoinTransaction.Record<Config>) {
            if (failSave) throw java.io.IOException("synthetic storage failure")
            saved = record; afterSave?.invoke()
        }
    }

    @Test fun backsUpBeforeWritingVerifiesAndRestoresAfterRecreation() {
        val device = Device()
        val store = Store()
        val original = device.current
        val transaction = HotspotJoinTransaction(device, store)
        val preview = transaction.prepare()
        assertEquals(HotspotJoinTransaction.Status.READY, preview.status)
        assertEquals(0, device.writes)
        assertEquals(original, store.saved!!.original)
        val result = transaction.apply(preview.token!!)
        assertEquals(HotspotJoinTransaction.Status.APPLIED, result.status)
        assertEquals(original.credentials, device.current.credentials)
        assertEquals(listOf("other", "apple"), device.current.vendors)
        assertEquals(HotspotJoinTransaction.Status.RESTORED,
            HotspotJoinTransaction(device, store).restore(preview.token).status)
        assertEquals(original, device.current)
    }

    @Test fun refusesUnsafeWritesAndDoesNotOverwriteAnOutstandingBackup() {
        for (unsafe in listOf("on", "unknown", "firmware", "drift", "cancel")) {
            val device = Device(); val store = Store()
            var cancelled = false
            val transaction = HotspotJoinTransaction(device, store) { cancelled }
            val preview = transaction.prepare()
            val expected = when (unsafe) {
                "on" -> { device.off = false; HotspotJoinTransaction.Status.HOTSPOT_ON }
                "unknown" -> { device.off = null; HotspotJoinTransaction.Status.UNKNOWN_STATE }
                "firmware" -> { device.firmware = "firmware-b"; HotspotJoinTransaction.Status.FIRMWARE_CHANGED }
                "drift" -> { device.current = Config("edited"); HotspotJoinTransaction.Status.DRIFT }
                else -> { cancelled = true; HotspotJoinTransaction.Status.CANCELLED }
            }
            assertEquals(unsafe, expected, transaction.apply(preview.token!!).status)
            assertEquals(0, device.writes)
        }
        val device = Device(); val store = Store()
        val transaction = HotspotJoinTransaction(device, store)
        val token = transaction.prepare().token!!
        transaction.apply(token)
        assertEquals(token, transaction.prepare().token)
        assertEquals(listOf("other"), store.saved!!.original.vendors)
        device.current = Config("edited", listOf("other", "apple"))
        assertEquals(HotspotJoinTransaction.Status.DRIFT, transaction.restore(token).status)
        assertEquals(1, device.writes)
    }

    @Test fun partialFailuresAreCompensatedOnlyWhenTheConfigurationIsStillOurs() {
        val device = Device(); val store = Store()
        val transaction = HotspotJoinTransaction(device, store)
        val preview = transaction.prepare()
        device.accepted = false // Firmware reports rejection after changing its saved configuration.
        assertEquals(HotspotJoinTransaction.Status.FAILED_ROLLED_BACK, transaction.apply(preview.token!!).status)
        assertEquals(Config(), device.current)
        assertEquals(HotspotJoinTransaction.Stage.RESTORED, store.saved!!.stage)

        val next = transaction.prepare()
        device.afterWrite = { device.current = Config("external-edit", listOf("other", "apple")) }
        assertEquals(HotspotJoinTransaction.Status.RECOVERY_REQUIRED, transaction.apply(next.token!!).status)
        assertEquals("external-edit", device.current.credentials)
        assertEquals(HotspotJoinTransaction.Stage.PENDING, store.saved!!.stage)
        assertEquals(HotspotJoinTransaction.Status.DRIFT, transaction.restore(next.token).status)
    }

    @Test fun durablePendingWriteIsASecondCancellationAndDriftBoundary() {
        for (race in listOf("cancel", "drift", "on", "storage")) {
            val device = Device(); val store = Store(); var cancelled = false
            val transaction = HotspotJoinTransaction(device, store) { cancelled }
            val token = transaction.prepare().token!!
            val expected = when (race) {
                "cancel" -> { store.afterSave = { cancelled = true }; HotspotJoinTransaction.Status.CANCELLED }
                "drift" -> { store.afterSave = { device.current = Config("edited") }; HotspotJoinTransaction.Status.DRIFT }
                "on" -> { store.afterSave = { device.off = false }; HotspotJoinTransaction.Status.HOTSPOT_ON }
                else -> { store.failSave = true; HotspotJoinTransaction.Status.STORAGE_FAILED }
            }
            assertEquals(race, expected, transaction.apply(token).status)
            assertEquals(0, device.writes)
        }
    }

    @Test fun failedCompletionJournalStillLeavesTheDurableOriginalForRecovery() {
        val device = Device(); val store = Store()
        val transaction = HotspotJoinTransaction(device, store)
        val token = transaction.prepare().token!!
        device.afterWrite = { store.failSave = true }
        assertEquals(HotspotJoinTransaction.Status.RECOVERY_REQUIRED, transaction.apply(token).status)
        assertEquals(HotspotJoinTransaction.Stage.PENDING, store.saved!!.stage)
        store.failSave = false; device.afterWrite = null
        assertEquals(HotspotJoinTransaction.Status.RESTORED, HotspotJoinTransaction(device, store).restore(token).status)
        assertEquals(Config(), device.current)
    }

    @Test fun unavailableCapabilitiesFailClosedWithoutTakingRollbackOwnership() {
        val device = Device().apply { capabilitiesUnavailable = true }
        val store = Store()
        assertEquals(HotspotJoinTransaction.Status.UNSUPPORTED, HotspotJoinTransaction(device, store).prepare().status)
        assertNull(store.saved); assertEquals(0, device.writes)
        device.capabilitiesUnavailable = false
        device.current = Config(vendors = listOf("other", "apple"))
        assertEquals(HotspotJoinTransaction.Status.ALREADY_PRESENT, HotspotJoinTransaction(device, store).prepare().status)
        assertNull(store.saved)
    }

    @Test fun cancellationDuringTheLastReadDoesNotStartAWrite() {
        val device = Device(); val store = Store(); var cancelled = false
        val transaction = HotspotJoinTransaction(device, store) { cancelled }
        val token = transaction.prepare().token!!
        var reads = 0
        device.afterRead = { if (++reads == 2) cancelled = true }
        assertEquals(HotspotJoinTransaction.Status.CANCELLED, transaction.apply(token).status)
        assertEquals(0, device.writes)
    }

    @Test fun lostReadbackRetainsRollbackAndCancellationAfterWritingCompensates() {
        val device = Device(); val store = Store(); var cancelled = false
        val transaction = HotspotJoinTransaction(device, store) { cancelled }
        val token = transaction.prepare().token!!
        device.afterWrite = { device.readFails = true }
        assertEquals(HotspotJoinTransaction.Status.RECOVERY_REQUIRED, transaction.apply(token).status)
        assertEquals(HotspotJoinTransaction.Stage.PENDING, store.saved!!.stage)
        device.readFails = false; device.afterWrite = null
        assertEquals(HotspotJoinTransaction.Status.RESTORED, transaction.restore(token).status)
        val next = transaction.prepare().token!!
        device.afterWrite = { cancelled = true }
        assertEquals(HotspotJoinTransaction.Status.FAILED_ROLLED_BACK, transaction.apply(next).status)
        assertEquals(Config(), device.current)
    }
    @Test fun changedCapabilitiesSinceCheckPreventApplyingThePreparedTarget() {
        val device = Device(); val store = Store()
        val transaction = HotspotJoinTransaction(device, store)
        val token = transaction.prepare().token!!
        device.capabilitiesUnavailable = true
        assertEquals(HotspotJoinTransaction.Status.UNSUPPORTED, transaction.apply(token).status)
        assertEquals(0, device.writes)
        assertEquals(HotspotJoinTransaction.Stage.PREPARED, store.saved!!.stage)
    }
}
