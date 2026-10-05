package com.shilapi.xcertplay.hud

import java.lang.reflect.Modifier

/**
 * One-shot entry point launched under the head unit's shell uid with `app_process`. It reflects
 * only the six read fields DiPlay needs and their device ids, then exits. It performs no Binder
 * writes and does not register a persistent service.
 */
object Byd13CatalogProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val wanted = buildSet {
            for (field in BydVehicleField.entries) {
                add(field.symbol)
                addAll(field.symbolAliases)
                add("BYDAutoConstants.BYDAUTO_DEVICE_${field.deviceName}")
            }
        }
        println(HEADER)
        for (rootName in ROOTS) {
            val root = runCatching { Class.forName(rootName) }.getOrNull() ?: continue
            scan(root, wanted, HashSet()).sorted().forEach(::println)
        }
    }

    private fun scan(type: Class<*>, wanted: Set<String>, seen: MutableSet<String>): List<String> {
        if (!seen.add(type.name)) return emptyList()
        val prefix = type.simpleName.ifBlank { type.name.substringAfterLast('.') }
        val lines = runCatching {
            type.declaredFields.mapNotNull { field ->
                val key = "$prefix.${field.name}"
                if (key !in wanted || !Modifier.isStatic(field.modifiers) ||
                    field.type != Int::class.javaPrimitiveType && field.type != Long::class.javaPrimitiveType) {
                    return@mapNotNull null
                }
                field.isAccessible = true
                "$key=${field.get(null)}"
            }
        }.getOrDefault(emptyList())
        val nested = runCatching { type.declaredClasses.toList() }.getOrDefault(emptyList())
            .flatMap { scan(it, wanted, seen) }
        return lines + nested
    }

    const val HEADER = "DIPLAY_BYD13_CATALOG_V1"
    private val ROOTS = listOf(
        "android.hardware.bydauto.BYDAutoFeatureIds",
        "android.hardware.bydauto.BYDAutoConstants",
    )
}

internal data class BydFirmwareCatalog(
    val symbols: Map<String, Int>,
    val devices: Map<String, Int>,
) {
    fun fid(field: BydVehicleField): Int? =
        (listOf(field.symbol) + field.symbolAliases).firstNotNullOfOrNull(symbols::get)

    fun device(field: BydVehicleField): Int = devices[field.deviceName] ?: field.defaultDevice

    companion object {
        private const val DEVICE_PREFIX = "BYDAutoConstants.BYDAUTO_DEVICE_"

        fun parse(output: String?): BydFirmwareCatalog? {
            val text = output ?: return null
            if (Byd13CatalogProbeMain.HEADER !in text.lineSequence().map(String::trim)) return null
            val symbols = LinkedHashMap<String, Int>()
            val devices = LinkedHashMap<String, Int>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                val equals = line.lastIndexOf('=')
                if (equals <= 0 || equals == line.lastIndex) continue
                val key = line.substring(0, equals)
                val value = line.substring(equals + 1).toLongOrNull()
                    ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: continue
                if (key.startsWith(DEVICE_PREFIX)) {
                    devices[key.removePrefix(DEVICE_PREFIX)] = value
                } else if ('.' in key && !key.startsWith("BYDAutoFeatureIds.")) {
                    symbols[key] = value
                }
            }
            return BydFirmwareCatalog(symbols, devices).takeIf { it.symbols.isNotEmpty() }
        }
    }
}
