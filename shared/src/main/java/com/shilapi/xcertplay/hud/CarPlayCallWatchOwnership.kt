package com.shilapi.xcertplay.hud

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

/** Shared by shell commands/watchers so an old process cannot reset a newer call. */
internal class CarPlayCallWatchOwnership(private val directory: File = File("/data/local/tmp")) {
    private val owner = File(directory, "diplay-carplay-call-owner")
    private val lock = File(directory, "diplay-carplay-call-lock")

    fun claim(path: String, action: (File) -> Unit) = locked {
        val token = token(path)
        // Persist recovery intent before claiming hardware or entering a setter. A failed
        // first show must leave a real token for the watcher and later end commands.
        token.writeText("cleanup 0")
        owner.writeText(token.path)
        action(token)
    }

    fun update(path: String, action: (File) -> Unit): Boolean = locked {
        val token = token(path)
        if (owner.readTextOrNull() != token.path) return@locked false
        action(token)
        true
    }

    fun retire(path: String, action: () -> Unit): Boolean = locked {
        val token = token(path)
        val ours = owner.readTextOrNull() == token.path
        if (ours) {
            // An idle write can also fail after mutation. Keep recovery intent visible to
            // the existing watcher before attempting any owned end operation.
            token.writeText("cleanup 0")
            action()
            owner.delete()
        }
        token.delete()
        ours
    }

    /** True when cleanup completed or a newer owner superseded us; false retains retryable ownership. */
    fun retireOrRetry(path: String, action: () -> Unit): Boolean =
        runCatching { retire(path, action); true }.getOrDefault(false)

    /** Reserve recovery ownership without entering any vehicle setter. Do not steal a live record. */
    fun prepare(path: String, appPid: String, alive: (String) -> Boolean = { true }): Boolean = locked {
        require(appPid.matches(Regex("[0-9]+")))
        val token = token(path)
        val previous = owner.readTextOrNull()
        if (previous != null && previous != token.path) {
            val old = token(previous)
            if (old.exists()) {
                val fields = parts(old)
                val oldPid = fields.getOrNull(2)
                // A child may never have initialized. A dead app's pristine reservation
                // is safe to retire under the claim lock, without resetting any hardware.
                if (fields.firstOrNull() != "prepared" || oldPid == null ||
                    !oldPid.matches(Regex("[0-9]+")) || alive(oldPid)) return@locked false
                ready(old).delete()
                old.delete()
            }
        }
        if (previous == token.path && token.exists()) {
            return@locked token.readText().trim() == "prepared 0 $appPid"
        }
        token.writeText("prepared 0 $appPid")
        owner.writeText(token.path)
        ready(token).delete()
        true
    }

    /** Only the initialized watcher child calls this, and only for its original app process. */
    fun markWatcherReady(path: String, appPid: String, watcherPid: String): Boolean = locked {
        val token = token(path)
        if (owner.readTextOrNull() != token.path || parts(token).getOrNull(2) != appPid) return@locked false
        require(watcherPid.matches(Regex("[0-9]+")))
        ready(token).writeText("$appPid $watcherPid")
        true
    }

    fun watcherReady(path: String, appPid: String, alive: (String) -> Boolean): Boolean = locked {
        watcherReadyLocked(token(path), appPid, alive)
    }

    /** The readiness check and pristine-to-dirty transition are atomic with watcher death cleanup. */
    fun claimReady(path: String, appPid: String, alive: (String) -> Boolean, action: (File) -> Unit): Boolean = locked {
        val token = token(path)
        if (!watcherReadyLocked(token, appPid, alive) || parts(token).firstOrNull() == "cleanup") return@locked false
        token.writeText("cleanup 0 $appPid")
        action(token)
        true
    }

    /** Cancellation of an unprotected prepared call never writes idle values over another call. */
    fun cancelPrepared(path: String): Boolean = locked {
        val token = token(path)
        if (token.exists() && parts(token).firstOrNull() != "prepared") return@locked false
        if (!token.exists() && owner.readTextOrNull() == token.path) return@locked false
        if (owner.readTextOrNull() == token.path) owner.delete()
        ready(token).delete()
        token.delete()
        true
    }

    /** Inspect the phase under the same lock that guards a concurrent first hardware write. */
    fun retireStaged(path: String, action: () -> Unit): Boolean = locked {
        val token = token(path)
        val ours = owner.readTextOrNull() == token.path
        if (ours) {
            val fields = parts(token)
            if (fields.firstOrNull() != "prepared") {
                token.writeText("cleanup 0 ${fields.getOrNull(2).orEmpty()}")
                action()
            }
            owner.delete()
        }
        ready(token).delete()
        token.delete()
        ours
    }

    fun clearWatcherReady(path: String, watcherPid: String) = locked {
        val token = token(path)
        if (ready(token).readTextOrNull()?.trim()?.split(' ')?.getOrNull(1) == watcherPid) ready(token).delete()
    }

    private fun watcherReadyLocked(token: File, appPid: String, alive: (String) -> Boolean): Boolean {
        if (owner.readTextOrNull() != token.path || parts(token).getOrNull(2) != appPid) return false
        val fields = ready(token).readTextOrNull()?.trim()?.split(' ') ?: return false
        val pid = fields.getOrNull(1) ?: return false
        return fields.firstOrNull() == appPid && pid.matches(Regex("[0-9]+")) && runCatching { alive(pid) }.getOrDefault(false)
    }

    private fun parts(token: File): List<String> = token.readTextOrNull()?.trim()?.split(' ') ?: emptyList()
    private fun ready(token: File) = File(token.path + ".ready")

    private fun token(path: String): File {
        val file = File(path).canonicalFile
        require(file.parentFile == directory.canonicalFile &&
            file.name.matches(Regex("diplay-carplay-call-[A-Za-z0-9_.]+-[0-9a-f-]{36}"))) {
            "Invalid CarPlay call token"
        }
        return file
    }

    private fun File.readTextOrNull(): String? = if (isFile) readText() else null

    private fun <T> locked(action: () -> T): T = synchronized(processLock) {
        RandomAccessFile(lock, "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }

    companion object {
        // File locks serialize different shell processes; this also serializes threads in one JVM.
        private val processLock = Any()
        fun newToken(packageName: String): String {
            require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
            return "/data/local/tmp/diplay-carplay-call-$packageName-${UUID.randomUUID()}"
        }
    }
}
