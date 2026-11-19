package com.pulsa.player.core

import android.content.Context
import android.system.Os
import android.util.Log
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

internal object LegacyMigrationPlan {
    fun needsMigration(markerPresent: Boolean): Boolean = !markerPresent

    fun writes(
        stores: Map<String, Map<String, PreferenceValue>>,
        excludedSettingsKeys: Set<String>,
        currentValues: Map<String, Map<String, PreferenceValue>> = emptyMap(),
        trustedNamespaces: Set<String>? = null
    ): List<PreferenceWrite> = stores.map { (namespace, values) ->
        val fallbackKeys = decodeFallbackKeys(
            (values[DataStoreNamespaces.FALLBACK_MARKER] as? PreferenceValue.Text)?.value
        )
        val filtered = values.filterKeys { key ->
            key != DataStoreNamespaces.FALLBACK_MARKER &&
                key != DataStoreNamespaces.FALLBACK_GENERATION_MARKER &&
                key != DataStoreNamespaces.MIRROR_MARKER &&
                (namespace != DataStorePreferences.SETTINGS_NAMESPACE || key !in excludedSettingsKeys)
        }
        val migratedValues = filtered.mapValuesTo(linkedMapOf<String, PreferenceValue?>()) { it.value }
        fallbackKeys.forEach { key -> migratedValues[key] = filtered[key] }
        val trusted = trustedNamespaces?.contains(namespace) != false
        val externalKeys = if (trusted) {
            externalChangeKeys(values, filtered, currentValues[namespace].orEmpty())
        } else {
            emptySet()
        }
        if (!trusted) {
            // Sem confiança no espelho: o que já está no DataStore prevalece sobre qualquer
            // alteração feita na cópia legada por uma versão antiga do app. As chaves de
            // fallback ficam de fora porque a cópia legada é a fonte da recuperação.
            val current = currentValues[namespace].orEmpty()
            current.forEach { (key, value) ->
                if (key !in fallbackKeys && migratedValues.containsKey(key)) {
                    migratedValues[key] = value
                }
            }
        }
        PreferenceWrite(
            namespace = namespace,
            clear = false,
            values = migratedValues,
            overwriteKeys = externalKeys,
            fallbackOverwriteKeys = fallbackKeys,
            fallbackGeneration = (
                values[DataStoreNamespaces.FALLBACK_GENERATION_MARKER] as? PreferenceValue.LongNumber
                )?.value ?: if (fallbackKeys.isNotEmpty()) 0L else null
        )
    }

    fun externalChangeKeys(
        rawValues: Map<String, PreferenceValue>,
        filteredValues: Map<String, PreferenceValue>,
        currentValues: Map<String, PreferenceValue>
    ): Set<String> {
        val marker = (rawValues[DataStoreNamespaces.MIRROR_MARKER] as? PreferenceValue.Text)?.value
            ?: return emptySet()
        val content = rawValues.filterKeys { it != DataStoreNamespaces.MIRROR_MARKER }
        if (marker == LegacyPreferencesXml.fingerprint(content)) return emptySet()
        return filteredValues.filter { (key, value) -> currentValues[key] != value }.keys
    }

    fun dataVersion(stores: Map<String, Map<String, PreferenceValue>>): Int {
        val value = stores[DataStorePreferences.SETTINGS_NAMESPACE]?.get("data_version")
        return (value as? PreferenceValue.Integer)?.value?.coerceAtLeast(0) ?: 0
    }

    fun canApplyWrite(write: PreferenceWrite, protectedKeys: Set<String>): Boolean =
        write.values.keys.none { it in protectedKeys }

    fun mergeWrite(
        existing: Map<String, PreferenceValue>,
        write: PreferenceWrite,
        protectedKeys: Set<String>
    ): Map<String, PreferenceValue> {
        if (!canApplyWrite(write, protectedKeys)) return existing
        val merged = existing.toMutableMap()
        if (write.clear) {
            merged.keys.removeAll { it !in protectedKeys }
        }
        write.values.forEach { (key, value) ->
            if (key in protectedKeys) return@forEach
            if (value == null) merged.remove(key) else merged[key] = value
        }
        return merged
    }
}

internal fun encodeFallbackKeys(keys: Set<String>): String =
    keys.sorted().joinToString("") { key -> "${key.length}:$key" }

internal fun decodeFallbackKeys(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    val keys = linkedSetOf<String>()
    var offset = 0
    while (offset < raw.length) {
        val separator = raw.indexOf(':', offset)
        if (separator <= offset) return keys
        val length = raw.substring(offset, separator).toIntOrNull() ?: return keys
        val start = separator + 1
        val end = start + length
        if (length < 0 || end > raw.length) return keys
        keys.add(raw.substring(start, end))
        offset = end
    }
    return keys
}

internal fun interface LegacyFileRenamer {
    fun rename(source: File, target: File): Boolean
}

private object AndroidLegacyFileRenamer : LegacyFileRenamer {
    override fun rename(source: File, target: File): Boolean = try {
        Os.rename(source.absolutePath, target.absolutePath)
        true
    } catch (t: Throwable) {
        false
    }
}

internal object LegacyPreferencesFileStore {
    private const val TAG = "PulsaLegacyPrefs"
    private val locks = mutableMapOf<String, Any>()

    fun read(file: File): Map<String, PreferenceValue> = synchronized(lockFor(file)) {
        readUnlocked(file)
    }

    fun write(
        file: File,
        values: Map<String, PreferenceValue>,
        renamer: LegacyFileRenamer = AndroidLegacyFileRenamer
    ): Boolean = synchronized(lockFor(file)) {
        writeUnlocked(file, values, renamer)
    }

    fun applyWrite(
        file: File,
        write: PreferenceWrite,
        protectedKeys: Set<String>,
        markFallback: Boolean = true,
        renamer: LegacyFileRenamer = AndroidLegacyFileRenamer
    ): Boolean = synchronized(lockFor(file)) {
        if (!LegacyMigrationPlan.canApplyWrite(write, protectedKeys)) {
            Log.e(TAG, "escrita de secret bloqueada no fallback")
            return@synchronized false
        }
        val current = readUnlocked(file)
        val merged = LegacyMigrationPlan.mergeWrite(current, write, protectedKeys).toMutableMap()
        if (markFallback) {
            val changedKeys = if (write.clear) {
                current.keys - protectedKeys
            } else {
                write.values.keys - protectedKeys
            }
            val fallbackKeys = decodeFallbackKeys(
                (current[DataStoreNamespaces.FALLBACK_MARKER] as? PreferenceValue.Text)?.value
            ).toMutableSet()
            fallbackKeys.addAll(changedKeys)
            val generation = (current[DataStoreNamespaces.FALLBACK_GENERATION_MARKER] as? PreferenceValue.LongNumber)?.value
                ?: System.currentTimeMillis()
            merged[DataStoreNamespaces.FALLBACK_GENERATION_MARKER] = PreferenceValue.LongNumber(generation)
            merged[DataStoreNamespaces.FALLBACK_MARKER] = PreferenceValue.Text(
                encodeFallbackKeys(fallbackKeys)
            )
        } else {
            current[DataStoreNamespaces.FALLBACK_MARKER]?.let {
                merged[DataStoreNamespaces.FALLBACK_MARKER] = it
            }
            current[DataStoreNamespaces.FALLBACK_GENERATION_MARKER]?.let {
                merged[DataStoreNamespaces.FALLBACK_GENERATION_MARKER] = it
            }
        }
        writeUnlocked(file, merged, renamer)
    }

    fun remove(
        file: File,
        keys: Set<String>,
        renamer: LegacyFileRenamer = AndroidLegacyFileRenamer
    ): Boolean = synchronized(lockFor(file)) {
        if (keys.isEmpty()) return@synchronized true
        try {
            val values = readUnlocked(file)
            val written = writeUnlocked(file, values.filterKeys { it !in keys }, renamer)
            if (!written) return@synchronized false
            val backupFile = backup(file)
            if (!backupFile.exists()) return@synchronized true
            if (backupFile.delete()) return@synchronized true
            val backupValues = try {
                LegacyPreferencesXml.parse(backupFile.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                null
            } ?: return@synchronized false
            val temporaryBackup = backupTemporary(file)
            temporaryBackup.delete()
            writeSynced(temporaryBackup, LegacyPreferencesXml.write(backupValues.filterKeys { it !in keys }))
            val sanitized = renamer.rename(temporaryBackup, backupFile)
            temporaryBackup.delete()
            sanitized
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao remover valores do XML legado", t)
            false
        }
    }

    fun delete(file: File): Boolean = synchronized(lockFor(file)) {
        var deleted = true
        listOf(file, backup(file), temporary(file), backupTemporary(file)).forEach { candidate ->
            if (candidate.exists() && !candidate.delete()) deleted = false
        }
        deleted
    }

    private fun lockFor(file: File): Any = synchronized(locks) {
        locks.getOrPut(file.absolutePath) { Any() }
    }

    private fun readUnlocked(file: File): Map<String, PreferenceValue> {
        val backup = backup(file)
        var primaryFailure: Throwable? = null
        if (file.exists()) {
            try {
                return LegacyPreferencesXml.parse(file.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                primaryFailure = t
            }
        }
        if (backup.exists()) {
            try {
                return LegacyPreferencesXml.parse(backup.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                if (primaryFailure == null) primaryFailure = t
            }
        }
        if (primaryFailure != null) throw primaryFailure
        return emptyMap()
    }

    private fun writeUnlocked(
        file: File,
        values: Map<String, PreferenceValue>,
        renamer: LegacyFileRenamer
    ): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temporary = temporary(file)
        val backupTemporary = backupTemporary(file)
        temporary.delete()
        backupTemporary.delete()
        val hadPrimary = file.exists()
        return try {
            writeSynced(temporary, LegacyPreferencesXml.write(withMirrorMarker(values)))
            if (hadPrimary) {
                copySynced(file, backupTemporary)
                if (!renamer.rename(backupTemporary, backup(file))) return false
            }
            if (!renamer.rename(temporary, file)) return false
            if (!hadPrimary) {
                runCatching {
                    copySynced(file, backupTemporary)
                    renamer.rename(backupTemporary, backup(file))
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao gravar XML legado", t)
            false
        } finally {
            temporary.delete()
            backupTemporary.delete()
        }
    }

    private fun withMirrorMarker(values: Map<String, PreferenceValue>): Map<String, PreferenceValue> {
        val content = values.filterKeys { it != DataStoreNamespaces.MIRROR_MARKER }
        return content + (
            DataStoreNamespaces.MIRROR_MARKER to
                PreferenceValue.Text(LegacyPreferencesXml.fingerprint(content))
            )
    }

    private fun writeSynced(file: File, text: String) {
        FileOutputStream(file).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun copySynced(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun backup(file: File): File = File(file.parentFile, "${file.name}.bak")
    private fun temporary(file: File): File = File(file.parentFile, ".${file.name}.tmp")
    private fun backupTemporary(file: File): File = File(file.parentFile, ".${file.name}.bak.tmp")
}

internal object LegacyPreferencesFile {
    fun read(context: Context, name: String): Map<String, PreferenceValue> =
        LegacyPreferencesFileStore.read(file(context, name))

    fun write(context: Context, name: String, values: Map<String, PreferenceValue>): Boolean =
        LegacyPreferencesFileStore.write(file(context, name), values)

    fun applyWrite(
        context: Context,
        name: String,
        write: PreferenceWrite,
        protectedKeys: Set<String>,
        markFallback: Boolean = true
    ): Boolean = LegacyPreferencesFileStore.applyWrite(
        file(context, name),
        write,
        protectedKeys,
        markFallback
    )

    fun remove(context: Context, name: String, keys: Set<String>): Boolean =
        LegacyPreferencesFileStore.remove(file(context, name), keys)

    fun delete(context: Context, names: List<String>): Boolean {
        var deleted = true
        names.forEach { name ->
            if (!LegacyPreferencesFileStore.delete(file(context, name))) deleted = false
        }
        return deleted
    }

    private fun file(context: Context, name: String): File {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        return File(directory, "$name.xml")
    }
}

internal object LegacyPreferencesXml {
    private val FIELD_SEPARATOR = byteArrayOf(0)

    fun parse(xml: String): Map<String, PreferenceValue> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        try {
            factory.isXIncludeAware = false
            factory.isExpandEntityReferences = false
        } catch (t: Throwable) {
        }
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val root = document.documentElement ?: return emptyMap()
        if (root.tagName != "map") throw IllegalArgumentException("XML de preferencias invalido")
        val values = linkedMapOf<String, PreferenceValue>()
        val children = root.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val key = element.getAttribute("name")
            if (key.isEmpty()) continue
            values[key] = when (element.tagName) {
                "boolean" -> PreferenceValue.BooleanValue(
                    element.getAttribute("value").toBooleanStrictOrNull()
                        ?: throw IllegalArgumentException("boolean invalido para $key")
                )
                "int" -> PreferenceValue.Integer(
                    element.getAttribute("value").toIntOrNull()
                        ?: throw IllegalArgumentException("int invalido para $key")
                )
                "long" -> PreferenceValue.LongNumber(
                    element.getAttribute("value").toLongOrNull()
                        ?: throw IllegalArgumentException("long invalido para $key")
                )
                "float" -> PreferenceValue.Decimal(
                    element.getAttribute("value").toFloatOrNull()
                        ?: throw IllegalArgumentException("float invalido para $key")
                )
                "string" -> PreferenceValue.Text(element.textContent.orEmpty())
                "set" -> PreferenceValue.TextSet(readSet(element))
                else -> continue
            }
        }
        return values
    }

    fun fingerprint(values: Map<String, PreferenceValue>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.toSortedMap().forEach { (key, value) ->
            digest.update(key.toByteArray(Charsets.UTF_8))
            digest.update(FIELD_SEPARATOR)
            digest.update(canonical(value).toByteArray(Charsets.UTF_8))
            digest.update(FIELD_SEPARATOR)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun contentFingerprint(values: Map<String, PreferenceValue>): String =
        fingerprint(values.filterKeys { it != DataStoreNamespaces.MIRROR_MARKER })

    fun canonical(value: PreferenceValue): String = when (value) {
        is PreferenceValue.Text -> "s:${value.value}"
        is PreferenceValue.TextSet -> "t:${value.value.size}:${value.value.sorted().joinToString("\u001F")}"
        is PreferenceValue.Integer -> "i:${value.value}"
        is PreferenceValue.LongNumber -> "l:${value.value}"
        is PreferenceValue.Decimal -> "f:${value.value}"
        is PreferenceValue.BooleanValue -> "b:${value.value}"
    }

    fun write(values: Map<String, PreferenceValue>): String {
        val output = StringBuilder()
        output.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        output.append("<map>\n")
        values.toSortedMap().forEach { (key, value) ->
            val safeName = escapeAttribute(key)
            when (value) {
                is PreferenceValue.Text -> output.append("    <string name=\"$safeName\">")
                    .append(escapeText(value.value))
                    .append("</string>\n")
                is PreferenceValue.TextSet -> {
                    output.append("    <set name=\"$safeName\">\n")
                    value.value.sorted().forEach { item ->
                        output.append("        <string>")
                            .append(escapeText(item))
                            .append("</string>\n")
                    }
                    output.append("    </set>\n")
                }
                is PreferenceValue.Integer -> output.append("    <int name=\"$safeName\" value=\"")
                    .append(value.value)
                    .append("\" />\n")
                is PreferenceValue.LongNumber -> output.append("    <long name=\"$safeName\" value=\"")
                    .append(value.value)
                    .append("\" />\n")
                is PreferenceValue.Decimal -> output.append("    <float name=\"$safeName\" value=\"")
                    .append(String.format(Locale.US, "%s", value.value))
                    .append("\" />\n")
                is PreferenceValue.BooleanValue -> output.append("    <boolean name=\"$safeName\" value=\"")
                    .append(value.value)
                    .append("\" />\n")
            }
        }
        output.append("</map>\n")
        return output.toString()
    }

    private fun readSet(element: Element): Set<String> {
        val values = linkedSetOf<String>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val child = node as Element
            if (child.tagName == "string") values.add(child.textContent.orEmpty())
        }
        return values
    }

    private fun escapeAttribute(value: String): String = value
        .replace("&", "\u0026amp;")
        .replace("<", "\u0026lt;")
        .replace(">", "\u0026gt;")
        .replace("\"", "\u0026quot;")
        .replace("'", "\u0026apos;")

    private fun escapeText(value: String): String = value
        .replace("&", "\u0026amp;")
        .replace("<", "\u0026lt;")
        .replace(">", "\u0026gt;")

    private fun setFeature(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (t: Throwable) {
        }
    }
}

internal object LegacyPreferencesMigration {
    fun migrate(context: Context, excludedSettingsKeys: Set<String>): Boolean {
        val secretsReady = Settings.migrateLegacySecretsResult(context)
        if (!secretsReady) {
            Log.e("PulsaLegacyPrefs", "secrets legados ainda nao foram migrados")
        }
        if (!DataStorePreferences.isAvailable()) return false
        val migrationWasComplete = DataStorePreferences.isMigrationComplete()
        val namespaces = DataStorePreferences.LEGACY_NAMESPACES
        val stores = namespaces.associateWith { namespace ->
            try {
                LegacyPreferencesFile.read(context, namespace)
            } catch (t: Throwable) {
                Log.e("PulsaLegacyPrefs", "falha ao ler XML legado $namespace", t)
                emptyMap()
            }
        }
        val currentValues = namespaces.associateWith { namespace ->
            DataStorePreferences.snapshotValues(namespace)
        }
        val dirtyNamespaces = namespaces.filter { namespace ->
            DataStorePreferences.isMirrorDirty(namespace)
        }.toSet()
        val trustedNamespaces = namespaces.filterNot { it in dirtyNamespaces }.toSet()
        val writes = LegacyMigrationPlan.writes(
            stores,
            excludedSettingsKeys,
            currentValues,
            trustedNamespaces
        )
        if (!DataStorePreferences.persistLegacyMigration(writes)) return false
        val mirrorOk = mutableMapOf<String, Boolean>()
        val repaired = mutableSetOf<String>()
        namespaces.forEach { namespace ->
            if (namespace == DataStorePreferences.SETTINGS_NAMESPACE && !secretsReady) {
                mirrorOk[namespace] = false
                return@forEach
            }
            val values = DataStorePreferences.snapshotValues(namespace)
            val oldValues = stores[namespace].orEmpty()
            val needsMirror = namespace in dirtyNamespaces ||
                !migrationWasComplete ||
                oldValues.containsKey(DataStoreNamespaces.FALLBACK_MARKER) ||
                (oldValues.isEmpty() && values.isNotEmpty())
            if (!needsMirror || (values.isEmpty() && oldValues.isEmpty())) {
                mirrorOk[namespace] = true
                return@forEach
            }
            val written = LegacyPreferencesFile.write(context, namespace, values)
            if (!written) {
                Log.e("PulsaLegacyPrefs", "falha ao atualizar copia de recuperacao $namespace")
            } else {
                repaired.add(namespace)
            }
            mirrorOk[namespace] = written
        }
        DataStorePreferences.clearMirrorDirty(repaired)
        return namespaces.all { namespace ->
            if (!stores[namespace].orEmpty().containsKey(DataStoreNamespaces.FALLBACK_MARKER)) {
                true
            } else {
                if (mirrorOk[namespace] != true) {
                    Log.e("PulsaLegacyPrefs", "marcador de fallback mantido em $namespace")
                    return@all false
                }
                LegacyPreferencesFile.remove(
                    context,
                    namespace,
                    setOf(
                        DataStoreNamespaces.FALLBACK_MARKER,
                        DataStoreNamespaces.FALLBACK_GENERATION_MARKER
                    )
                )
            }
        }
    }
}
