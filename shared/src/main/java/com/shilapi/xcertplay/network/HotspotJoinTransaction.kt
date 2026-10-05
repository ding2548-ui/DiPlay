package com.shilapi.xcertplay.network

import java.util.UUID

/** Full-configuration transaction. Caller owns an inter-process lock for each entry point. */
internal class HotspotJoinTransaction<C>(private val port: Port<C>, private val store: Store<C>,
    private val cancelled: () -> Boolean = { false }) {
    interface Port<C> {
        fun firmware(): String
        fun read(): C
        fun repaired(config: C): C?
        fun hotspotOff(): Boolean?
        fun write(config: C): Boolean
    }
    interface Store<C> { fun load(): Record<C>?; fun save(record: Record<C>) }
    enum class Stage { PREPARED, PENDING, APPLIED, RESTORED }
    enum class Status { READY, ALREADY_PRESENT, APPLIED, RESTORED, UNSUPPORTED, DRIFT,
        FIRMWARE_CHANGED, HOTSPOT_ON, UNKNOWN_STATE, CANCELLED, FAILED_ROLLED_BACK,
        RECOVERY_REQUIRED, STORAGE_FAILED, BUSY }
    data class Result(val status: Status, val token: String? = null, val rollback: Boolean = false)
    // Never stringify/export this record: C contains credentials.
    class Record<C>(val token: String, val firmware: String, val original: C,
        val target: C, val stage: Stage) {
        fun at(stage: Stage) = Record(token, firmware, original, target, stage)
    }

    fun prepare(): Result {
        if (cancelled()) return Result(Status.CANCELLED)
        val saved = try { store.load() } catch (_: Exception) { return Result(Status.STORAGE_FAILED) }
        if (saved != null && saved.stage in listOf(Stage.PENDING, Stage.APPLIED)) {
            if (saved.firmware != port.firmware()) return Result(Status.FIRMWARE_CHANGED, saved.token, true)
            return Result(if (port.read() == saved.target) Status.APPLIED else Status.RECOVERY_REQUIRED, saved.token, true)
        }
        val original = port.read()
        val target = try { port.repaired(original) } catch (_: Exception) { null }
            ?: return Result(Status.UNSUPPORTED)
        if (target == original) return Result(Status.ALREADY_PRESENT)
        val record = Record(UUID.randomUUID().toString(), port.firmware(), original, target, Stage.PREPARED)
        if (!persist(record)) return Result(Status.STORAGE_FAILED)
        return Result(Status.READY, record.token)
    }

    fun apply(token: String): Result {
        val record = try { store.load() } catch (_: Exception) { return Result(Status.STORAGE_FAILED) }
            ?: return Result(Status.DRIFT)
        guard(record, token)?.let { return it }
        if (record.stage != Stage.PREPARED || port.read() != record.original) return Result(Status.DRIFT, token, true)
        // Re-check current AP/country capabilities; a prepared target is not a capability lease.
        val eligible = try { port.repaired(record.original) == record.target } catch (_: Exception) { false }
        if (!eligible) return Result(Status.UNSUPPORTED, token)
        if (!persist(record.at(Stage.PENDING))) return Result(Status.STORAGE_FAILED, token)
        guard(record.at(Stage.PENDING), token)?.let { return it }
        if (port.read() != record.original) return Result(Status.DRIFT, token, true)
        if (cancelled()) return Result(Status.CANCELLED, token, true)
        val verified = try { port.write(record.target) && port.read() == record.target }
            catch (_: Exception) { false }
        if (!verified || cancelled()) return compensate(record)
        if (!persist(record.at(Stage.APPLIED))) return Result(Status.RECOVERY_REQUIRED, token, true)
        return Result(Status.APPLIED, token, true)
    }

    fun restore(token: String): Result {
        val record = try { store.load() } catch (_: Exception) { return Result(Status.STORAGE_FAILED) }
            ?: return Result(Status.DRIFT)
        guard(record, token)?.let { return it }
        if (port.read() == record.original) {
            if (!persist(record.at(Stage.RESTORED))) return Result(Status.RECOVERY_REQUIRED, token, true)
            return Result(Status.RESTORED, token)
        }
        if (port.read() != record.target) return Result(Status.DRIFT, token, true)
        // Mark pending before rollback too: a crash during either write remains recoverable.
        if (!persist(record.at(Stage.PENDING))) return Result(Status.STORAGE_FAILED, token, true)
        guard(record.at(Stage.PENDING), token)?.let { return it }
        if (port.read() != record.target) return Result(Status.DRIFT, token, true)
        if (cancelled()) return Result(Status.CANCELLED, token, true)
        try { port.write(record.original) } catch (_: Exception) { }
        if (port.read() != record.original || !persist(record.at(Stage.RESTORED)))
            return Result(Status.RECOVERY_REQUIRED, token, true)
        return Result(Status.RESTORED, token)
    }

    private fun guard(record: Record<C>, token: String): Result? {
        if (record.token != token) return Result(Status.DRIFT)
        if (record.firmware != port.firmware()) return Result(Status.FIRMWARE_CHANGED, token, true)
        if (cancelled()) return Result(Status.CANCELLED, token, record.stage != Stage.PREPARED)
        return when (port.hotspotOff()) {
            false -> Result(Status.HOTSPOT_ON, token, record.stage != Stage.PREPARED)
            null -> Result(Status.UNKNOWN_STATE, token, record.stage != Stage.PREPARED)
            true -> null
        }
    }

    private fun persist(record: Record<C>): Boolean = try {
        store.save(record)
        val saved = store.load()
        saved != null && saved.token == record.token && saved.firmware == record.firmware &&
            saved.stage == record.stage && saved.original == record.original && saved.target == record.target
    } catch (_: Exception) { false }

    private fun compensate(record: Record<C>): Result {
        return try {
            val current = port.read()
            if (current != record.original) {
                if (current != record.target || port.hotspotOff() != true || port.firmware() != record.firmware)
                    return Result(Status.RECOVERY_REQUIRED, record.token, true)
                port.write(record.original)
            }
            if (port.read() != record.original) return Result(Status.RECOVERY_REQUIRED, record.token, true)
            if (!persist(record.at(Stage.RESTORED))) return Result(Status.RECOVERY_REQUIRED, record.token, true)
            Result(Status.FAILED_ROLLED_BACK, record.token)
        } catch (_: Exception) { Result(Status.RECOVERY_REQUIRED, record.token, true) }
    }
}
