package com.pulsa.player.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

internal sealed interface PreferenceValue {
    data class Text(val value: String) : PreferenceValue
    data class TextSet(val value: Set<String>) : PreferenceValue
    data class Integer(val value: Int) : PreferenceValue
    data class LongNumber(val value: Long) : PreferenceValue
    data class Decimal(val value: Float) : PreferenceValue
    data class BooleanValue(val value: Boolean) : PreferenceValue
}

internal data class PreferenceWrite(
    val namespace: String,
    val clear: Boolean,
    val values: Map<String, PreferenceValue?>,
    val revision: Long = 0L,
    val overwriteKeys: Set<String> = emptySet(),
    val fallbackOverwriteKeys: Set<String> = emptySet(),
    val fallbackGeneration: Long? = null
)

internal data class LegacySnapshot(
    val values: Map<String, Map<String, PreferenceValue>>,
    val fallbackKeys: Map<String, Set<String>>,
    val fallbackGenerations: Map<String, Long> = emptyMap()
)

internal data class DecodedSnapshot(
    val values: Map<String, Map<String, PreferenceValue>>,
    val revisions: Map<String, Map<String, Long>>,
    val consumedFallbackGenerations: Map<String, Long> = emptyMap()
)

internal enum class PreferenceCommitOutcome {
    SUCCESS,
    FAILURE,
    UNKNOWN
}

internal interface PreferencePersistence {
    fun apply(write: PreferenceWrite, onResult: (Boolean) -> Unit)
    fun commit(write: PreferenceWrite): PreferenceCommitOutcome
}

internal object PreferenceWriteRevision {
    private val counter = AtomicLong()

    fun next(): Long = counter.incrementAndGet()
}

internal object DataStoreNamespaces {
    private const val SEPARATOR = "::"
    const val MIGRATION_MARKER = "__pulsa_datastore_legacy_migrated_v3"
    const val FALLBACK_MARKER = "__pulsa_datastore_fallback__"
    const val FALLBACK_GENERATION_MARKER = "__pulsa_datastore_fallback_generation__"
    const val MIRROR_MARKER = "__pulsa_datastore_mirror__"
    const val METADATA_NAMESPACE = "__pulsa_datastore_metadata"
    const val CLEAR_MARKER = "__clear__"

    fun encode(namespace: String, key: String): String = "$namespace$SEPARATOR$key"

    fun revisionKey(namespace: String, key: String): String =
        encode(METADATA_NAMESPACE, "$namespace$SEPARATOR$key")

    fun consumedFallbackKey(namespace: String): String =
        encode(METADATA_NAMESPACE, "fallback_generation_$namespace")

    fun mirrorDirtyKey(namespace: String): String =
        encode(METADATA_NAMESPACE, "mirror_dirty_$namespace")

    fun decode(encoded: String): Pair<String, String>? {
        val separator = encoded.indexOf(SEPARATOR)
        if (separator <= 0 || separator + SEPARATOR.length >= encoded.length) return null
        return encoded.substring(0, separator) to encoded.substring(separator + SEPARATOR.length)
    }

    fun decodeRevision(encoded: String): Pair<String, String>? {
        val pair = decode(encoded) ?: return null
        if (pair.first != METADATA_NAMESPACE) return null
        val separator = pair.second.indexOf(SEPARATOR)
        if (separator <= 0 || separator + SEPARATOR.length >= pair.second.length) return null
        return pair.second.substring(0, separator) to pair.second.substring(separator + SEPARATOR.length)
    }
}

internal object DataStorePreferences {
    internal const val SETTINGS_NAMESPACE = "pulsa_settings"

    internal val LEGACY_NAMESPACES = listOf(
        SETTINGS_NAMESPACE,
        "pulsa_profile",
        "pulsa_blacklist",
        "pulsa_radio_stations",
        "pulsa_stream_history",
        "pulsa_dj_memory",
        "subtitle_config",
        "pulsa_update",
        "pulsa_last_changelog",
        "pulsa_last_installed"
    )

    private const val DATA_STORE_FILE = "pulsa_preferences.preferences_pb"
    private const val DATA_STORE_DIRECTORY = "datastore"
    private const val TAG = "PulsaDataStore"
    private const val INITIALIZATION_TIMEOUT_MS = 1_000L
    private const val FALLBACK_TIMEOUT_MS = 1_000L
    private const val WRITE_TIMEOUT_MS = 2_500L
    private const val MAX_INITIALIZATION_ATTEMPTS = 3
    private val lock = Any()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pulsa-datastore-writer")
    }
    @Volatile private var dataStore: androidx.datastore.core.DataStore<Preferences>? = null
    @Volatile private var applicationContext: Context? = null
    @Volatile private var initializationAttempts = 0
    @Volatile private var available = false
    @Volatile private var snapshot: Map<String, Map<String, PreferenceValue>> = emptyMap()
    @Volatile private var snapshotRevisions: Map<String, Map<String, Long>> = emptyMap()
    @Volatile private var migrationComplete = false
    private val dirtyMirrors = mutableSetOf<String>()
    private val adapters = mutableMapOf<String, DataStoreSharedPreferences>()

    private val persistence = object : PreferencePersistence {
        override fun apply(write: PreferenceWrite, onResult: (Boolean) -> Unit) {
            try {
                executor.execute {
                    val persisted = try {
                        runBlocking { persist(write) }
                    } catch (t: Throwable) {
                        Log.e(TAG, "falha ao gravar escrita assincrona", t)
                        false
                    }
                    if (!persisted) Log.e(TAG, "escrita assincrona nao persistida")
                    runCatching { onResult(persisted) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "falha ao agendar escrita", t)
                runCatching { onResult(false) }
            }
        }

        override fun commit(write: PreferenceWrite): PreferenceCommitOutcome {
            var future: java.util.concurrent.Future<Boolean>? = null
            return try {
                future = executor.submit<Boolean> {
                    runBlocking { persist(write) }
                }
                if (future.get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    PreferenceCommitOutcome.SUCCESS
                } else {
                    Log.e(TAG, "commit nao persistido")
                    PreferenceCommitOutcome.FAILURE
                }
            } catch (timeout: TimeoutException) {
                future?.cancel(true)
                Log.e(TAG, "timeout ao gravar commit", timeout)
                PreferenceCommitOutcome.UNKNOWN
            } catch (t: Throwable) {
                if (t is InterruptedException) Thread.currentThread().interrupt()
                Log.e(TAG, "falha ao gravar commit", t)
                PreferenceCommitOutcome.FAILURE
            }
        }
    }

    private suspend fun persist(write: PreferenceWrite): Boolean {
        val protectedKeys = if (write.namespace == SETTINGS_NAMESPACE) {
            Settings.secretKeys.toSet()
        } else {
            emptySet()
        }
        val safeWrite = write.copy(
            values = write.values.filterKeys { it !in protectedKeys }
        )
        if (safeWrite.values.isEmpty() && !safeWrite.clear) return true
        val context = applicationContext ?: return false
        if (available) {
            val store = dataStore
            if (store != null) {
                try {
                    store.updateData { preferences ->
                        val mutable = preferences.toMutablePreferences()
                        mutable.applyWrite(safeWrite)
                        mutable
                    }
                    val mirrored = LegacyPreferencesFile.applyWrite(
                        context,
                        safeWrite.namespace,
                        safeWrite,
                        protectedKeys,
                        markFallback = false
                    )
                    if (!mirrored) {
                        Log.e(TAG, "falha ao atualizar copia de recuperacao legado")
                        markMirrorDirty(safeWrite.namespace)
                    }
                    return true
                } catch (t: Throwable) {
                    Log.e(TAG, "falha ao gravar no DataStore; usando fallback legado", t)
                    available = false
                }
            }
        }
        return LegacyPreferencesFile.applyWrite(
            context,
            safeWrite.namespace,
            safeWrite,
            protectedKeys,
            markFallback = true
        )
    }

    private suspend fun markMirrorDirty(namespace: String) {
        synchronized(lock) { dirtyMirrors.add(namespace) }
        val store = dataStore ?: return
        try {
            store.updateData { preferences ->
                val mutable = preferences.toMutablePreferences()
                mutable[longPreferencesKey(DataStoreNamespaces.mirrorDirtyKey(namespace))] =
                    System.currentTimeMillis()
                mutable
            }
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao marcar copia de recuperacao como pendente", t)
        }
    }

    internal fun clearMirrorDirty(namespaces: Set<String>) {
        if (namespaces.isEmpty()) return
        synchronized(lock) { dirtyMirrors.removeAll(namespaces) }
        val store = dataStore ?: return
        try {
            runBlocking {
                store.updateData { preferences ->
                    val mutable = preferences.toMutablePreferences()
                    namespaces.forEach { namespace ->
                        mutable.remove(longPreferencesKey(DataStoreNamespaces.mirrorDirtyKey(namespace)))
                    }
                    mutable
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao limpar espelho legado pendente", t)
            synchronized(lock) { dirtyMirrors.addAll(namespaces) }
        }
    }

    internal fun isMirrorDirty(namespace: String): Boolean =
        synchronized(lock) { namespace in dirtyMirrors }

    fun initialize(context: Context): Boolean {
        synchronized(lock) {
            if (available) return true
            if (initializationAttempts >= MAX_INITIALIZATION_ATTEMPTS) return false
            initializationAttempts++
            val appContext = context.applicationContext
            applicationContext = appContext
            val store = dataStore ?: createStore(appContext)
            dataStore = store
            val legacy = loadLegacySnapshot(appContext)
            if (store == null) {
                snapshot = legacy.values
                snapshotRevisions = emptyMap()
                migrationComplete = false
                available = false
                return false
            }
            return try {
                val preferences = runBlocking {
                    withTimeout(INITIALIZATION_TIMEOUT_MS) {
                        store.data.first()
                    }
                }
                val decoded = decodeSnapshot(preferences)
                val merged = mergeSnapshots(decoded, legacy)
                snapshot = merged
                snapshotRevisions = decoded.revisions
                migrationComplete = preferences[booleanPreferencesKey(DataStoreNamespaces.MIGRATION_MARKER)] == true
                synchronized(lock) {
                    dirtyMirrors.clear()
                    LEGACY_NAMESPACES.forEach { namespace ->
                        if (preferences[longPreferencesKey(DataStoreNamespaces.mirrorDirtyKey(namespace))] != null) {
                            dirtyMirrors.add(namespace)
                        }
                    }
                }
                available = true
                adapters.forEach { (namespace, adapter) -> adapter.refresh(merged[namespace].orEmpty()) }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "falha ao carregar DataStore", t)
                snapshot = legacy.values
                snapshotRevisions = emptyMap()
                migrationComplete = false
                available = false
                false
            }
        }
    }

    private fun createStore(appContext: Context): androidx.datastore.core.DataStore<Preferences>? = try {
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler {
                Log.e(TAG, "arquivo do DataStore corrompido; recriando vazio")
                emptyPreferences()
            },
            produceFile = {
                val directory = File(appContext.filesDir, DATA_STORE_DIRECTORY)
                if (!directory.exists()) directory.mkdirs()
                File(directory, DATA_STORE_FILE)
            }
        )
    } catch (t: Throwable) {
        Log.e(TAG, "falha ao criar DataStore", t)
        null
    }

    private fun loadLegacySnapshot(context: Context): LegacySnapshot {
        val secretKeys = Settings.secretKeys.toSet()
        return try {
            runBlocking {
                withTimeout(FALLBACK_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val stores = LEGACY_NAMESPACES.associateWith { namespace ->
                            try {
                                LegacyPreferencesFile.read(context, namespace)
                            } catch (t: Throwable) {
                                Log.e(TAG, "falha ao ler fallback legado $namespace", t)
                                emptyMap()
                            }
                        }
                        val fallbackKeys = stores.mapValues { (_, values) ->
                            decodeFallbackKeys((values[DataStoreNamespaces.FALLBACK_MARKER] as? PreferenceValue.Text)?.value)
                        }
                        val values = stores.mapValues { (namespace, storeValues) ->
                            storeValues.filterKeys { key ->
                                key != DataStoreNamespaces.FALLBACK_MARKER &&
                                    key != DataStoreNamespaces.FALLBACK_GENERATION_MARKER &&
                                    key != DataStoreNamespaces.MIRROR_MARKER &&
                                    (namespace != SETTINGS_NAMESPACE || key !in secretKeys)
                            }
                        }
                        val fallbackGenerations = stores.mapValues { (namespace, storeValues) ->
                            (storeValues[DataStoreNamespaces.FALLBACK_GENERATION_MARKER] as? PreferenceValue.LongNumber)?.value
                                ?: if (fallbackKeys[namespace].orEmpty().isNotEmpty()) 0L else null
                        }.filterValues { it != null }.mapValues { it.value as Long }
                        LegacySnapshot(values, fallbackKeys, fallbackGenerations)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao preparar fallback legado", t)
            LegacySnapshot(emptyMap(), emptyMap())
        }
    }

    internal fun mergeSnapshots(
        current: DecodedSnapshot,
        legacy: LegacySnapshot
    ): Map<String, Map<String, PreferenceValue>> {
        val namespaces = current.values.keys + legacy.values.keys
        return namespaces.associateWith { namespace ->
            val merged = current.values[namespace].orEmpty().toMutableMap()
            val oldValues = legacy.values[namespace].orEmpty()
            val fallbackKeys = legacy.fallbackKeys[namespace].orEmpty()
            val fallbackGeneration = legacy.fallbackGenerations[namespace]
            val consumedGeneration = current.consumedFallbackGenerations[namespace]
            val fallbackApplies = fallbackKeys.isNotEmpty() && fallbackGeneration != consumedGeneration
            val revisions = current.revisions[namespace].orEmpty()
            val namespaceCleared = revisions.containsKey(DataStoreNamespaces.CLEAR_MARKER)
            oldValues.forEach { (key, value) ->
                val hasDataStoreRevision = revisions.containsKey(key)
                val fallbackWins = fallbackApplies && key in fallbackKeys
                if (fallbackWins || (!namespaceCleared && !hasDataStoreRevision && key !in merged)) {
                    merged[key] = value
                }
            }
            if (fallbackApplies) {
                fallbackKeys.forEach { key ->
                    if (key !in oldValues) merged.remove(key)
                }
            }
            merged
        }
    }

    fun preferences(context: Context, namespace: String): SharedPreferences {
        initialize(context)
        synchronized(lock) {
            adapters[namespace]?.let { return it }
            val adapter = DataStoreSharedPreferences(
                namespace = namespace,
                initialValues = snapshot[namespace].orEmpty(),
                persistence = persistence,
                blockedKeys = if (namespace == SETTINGS_NAMESPACE) Settings.secretKeys.toSet() else emptySet()
            )
            adapters[namespace] = adapter
            return adapter
        }
    }

    internal fun isAvailable(): Boolean = available

    internal fun snapshotValues(namespace: String): Map<String, PreferenceValue> =
        snapshot[namespace].orEmpty()

    internal fun isMigrationComplete(): Boolean = migrationComplete

    internal fun persistLegacyMigration(writes: List<PreferenceWrite>): Boolean {
        if (!available) return false
        val revision = PreferenceWriteRevision.next()
        val revisionedWrites = writes.map { it.copy(revision = revision) }
        val inserted = linkedMapOf<String, Set<String>>()
        var future: java.util.concurrent.Future<Boolean>? = null
        val persisted = try {
            future = executor.submit<Boolean> {
                runBlocking {
                    val store = dataStore ?: return@runBlocking false
                    store.updateData { preferences ->
                        inserted.clear()
                        val mutable = preferences.toMutablePreferences()
                        revisionedWrites.forEach { write ->
                            val keys = mutable.applyWriteIfAbsent(write)
                            inserted[write.namespace] = keys
                        }
                        mutable[booleanPreferencesKey(DataStoreNamespaces.MIGRATION_MARKER)] = true
                        mutable
                    }
                    true
                }
            }
            future.get(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future?.cancel(true)
            Log.e(TAG, "timeout ao persistir migracao legado", timeout)
            false
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            Log.e(TAG, "falha ao persistir migracao legado", t)
            false
        }
        if (!persisted) return false
        migrationComplete = true
        synchronized(lock) {
            revisionedWrites.forEach { write ->
                val keys = inserted[write.namespace].orEmpty()
                if (keys.isNotEmpty()) {
                    val namespaceValues = snapshot[write.namespace].orEmpty().toMutableMap()
                    val namespaceRevisions = snapshotRevisions[write.namespace].orEmpty().toMutableMap()
                    keys.forEach keyLoop@ { key ->
                        if (!write.values.containsKey(key)) return@keyLoop
                        val value = write.values[key]
                        if (value == null) namespaceValues.remove(key) else namespaceValues[key] = value
                        namespaceRevisions[key] = write.revision
                    }
                    snapshot = snapshot + (write.namespace to namespaceValues)
                    snapshotRevisions = snapshotRevisions + (write.namespace to namespaceRevisions)
                }
                adapters[write.namespace]?.mergeMigratedValues(write, keys)
            }
        }
        return true
    }

    private fun decodeSnapshot(preferences: Preferences): DecodedSnapshot {
        val decoded = linkedMapOf<String, MutableMap<String, PreferenceValue>>()
        val revisions = linkedMapOf<String, MutableMap<String, Long>>()
        preferences.asMap().forEach { (key, value) ->
            val revision = DataStoreNamespaces.decodeRevision(key.name)
            if (revision != null) {
                if (value is Long) {
                    revisions.getOrPut(revision.first) { linkedMapOf() }[revision.second] = value
                }
                return@forEach
            }
            val pair = DataStoreNamespaces.decode(key.name) ?: return@forEach
            val namespace = pair.first
            if (namespace == DataStoreNamespaces.METADATA_NAMESPACE) return@forEach
            val preferenceKey = pair.second
            if (namespace == SETTINGS_NAMESPACE && preferenceKey in Settings.secretKeys) return@forEach
            val preferenceValue = when (value) {
                is String -> PreferenceValue.Text(value)
                is Boolean -> PreferenceValue.BooleanValue(value)
                is Int -> PreferenceValue.Integer(value)
                is Long -> PreferenceValue.LongNumber(value)
                is Float -> PreferenceValue.Decimal(value)
                is Set<*> -> PreferenceValue.TextSet(value.filterIsInstance<String>().toSet())
                else -> return@forEach
            }
            decoded.getOrPut(namespace) { linkedMapOf() }[preferenceKey] = preferenceValue
        }
        val consumedFallbackGenerations = LEGACY_NAMESPACES.mapNotNull { namespace ->
            preferences[longPreferencesKey(DataStoreNamespaces.consumedFallbackKey(namespace))]
                ?.let { generation -> namespace to generation }
        }.toMap()
        return DecodedSnapshot(
            values = decoded.mapValues { (_, values) -> values.toMap() },
            revisions = revisions.mapValues { (_, values) -> values.toMap() },
            consumedFallbackGenerations = consumedFallbackGenerations
        )
    }
}

internal class DataStoreSharedPreferences(
    private val namespace: String,
    initialValues: Map<String, PreferenceValue>,
    private val persistence: PreferencePersistence,
    private val blockedKeys: Set<String> = emptySet()
) : SharedPreferences {
    private val lock = Any()
    private val values = LinkedHashMap<String, PreferenceValue>(initialValues)
    private val persistedValues = LinkedHashMap<String, PreferenceValue>(initialValues)
    private val keyRevisions = mutableMapOf<String, Long>()
    private val persistedRevisions = mutableMapOf<String, Long>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    internal fun refresh(newValues: Map<String, PreferenceValue>) {
        synchronized(lock) {
            val pending = linkedMapOf<String, Pair<Long, PreferenceValue>>()
            keyRevisions.forEach { (key, revision) ->
                if (persistedRevisions[key] != revision) {
                    values[key]?.let { pending[key] = revision to it }
                }
            }
            values.clear()
            values.putAll(newValues)
            persistedValues.clear()
            persistedValues.putAll(newValues)
            keyRevisions.clear()
            persistedRevisions.clear()
            pending.forEach { (key, pendingValue) ->
                values[key] = pendingValue.second
                keyRevisions[key] = pendingValue.first
            }
        }
    }

    internal fun mergeMigratedValues(write: PreferenceWrite, insertedKeys: Set<String>) {
        if (insertedKeys.isEmpty()) return
        synchronized(lock) {
            insertedKeys.forEach { key ->
                if (!write.values.containsKey(key)) return@forEach
                val value = write.values[key]
                val persistedRevision = persistedRevisions[key]
                if (persistedRevision == null || persistedRevision <= write.revision) {
                    if (value == null) persistedValues.remove(key) else persistedValues[key] = value
                    persistedRevisions[key] = write.revision
                }
                val currentRevision = keyRevisions[key]
                if (currentRevision == null || currentRevision <= write.revision) {
                    if (value == null) values.remove(key) else values[key] = value
                    keyRevisions[key] = write.revision
                }
            }
        }
    }

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        val result = linkedMapOf<String, Any>()
        values.forEach { (key, value) -> result[key] = value.platformValue() }
        result
    }

    override fun getString(key: String, defValue: String?): String? {
        val value = synchronized(lock) { values[key] } ?: return defValue
        if (value !is PreferenceValue.Text) throw typeError(key, "String", value)
        return value.value
    }

    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
        val value = synchronized(lock) { values[key] } ?: return defValues?.toMutableSet()
        if (value !is PreferenceValue.TextSet) throw typeError(key, "Set<String>", value)
        return value.value.toMutableSet()
    }

    override fun getInt(key: String, defValue: Int): Int {
        val value = synchronized(lock) { values[key] } ?: return defValue
        if (value !is PreferenceValue.Integer) throw typeError(key, "Int", value)
        return value.value
    }

    override fun getLong(key: String, defValue: Long): Long {
        val value = synchronized(lock) { values[key] } ?: return defValue
        if (value !is PreferenceValue.LongNumber) throw typeError(key, "Long", value)
        return value.value
    }

    override fun getFloat(key: String, defValue: Float): Float {
        val value = synchronized(lock) { values[key] } ?: return defValue
        if (value !is PreferenceValue.Decimal) throw typeError(key, "Float", value)
        return value.value
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val value = synchronized(lock) { values[key] } ?: return defValue
        if (value !is PreferenceValue.BooleanValue) throw typeError(key, "Boolean", value)
        return value.value
    }

    override fun contains(key: String): Boolean = synchronized(lock) { values.containsKey(key) }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }

    private fun typeError(key: String, expected: String, actual: PreferenceValue): ClassCastException =
        ClassCastException("Preference $namespace.$key is ${actual::class.java.simpleName}, expected $expected")

    private fun PreferenceValue.platformValue(): Any = when (this) {
        is PreferenceValue.Text -> value
        is PreferenceValue.TextSet -> value.toMutableSet()
        is PreferenceValue.Integer -> value
        is PreferenceValue.LongNumber -> value
        is PreferenceValue.Decimal -> value
        is PreferenceValue.BooleanValue -> value
    }

    private inner class Editor : SharedPreferences.Editor {
        private var clear = false
        private val changes = linkedMapOf<String, PreferenceValue?>()
        private var blocked = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = value?.let { PreferenceValue.Text(it) }
            return this
        }

        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = values?.let { PreferenceValue.TextSet(it.toSet()) }
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = PreferenceValue.Integer(value)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = PreferenceValue.LongNumber(value)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = PreferenceValue.Decimal(value)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = PreferenceValue.BooleanValue(value)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            if (reject(key)) return this
            changes[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            changes.clear()
            return this
        }

        override fun commit(): Boolean = submit(waitForPersistence = true)

        override fun apply() {
            submit(waitForPersistence = false)
        }

        private fun reject(key: String): Boolean {
            if (key !in blockedKeys) return false
            blocked = true
            return true
        }

        private fun submit(waitForPersistence: Boolean): Boolean {
            val pending = synchronized(this) {
                if (blocked && changes.isEmpty()) return false
                if (!clear && changes.isEmpty()) return true
                val current = clear to changes.toMap()
                clear = false
                changes.clear()
                blocked = false
                current
            }
            var write: PreferenceWrite? = null
            var previous: Map<String, PreferenceValue?> = emptyMap()
            var outcome: PreferenceCommitOutcome? = null
            synchronized(lock) {
                val previousValues = linkedMapOf<String, PreferenceValue?>()
                if (pending.first) values.forEach { (key, value) -> previousValues[key] = value }
                pending.second.keys.forEach { key -> previousValues.getOrPut(key) { values[key] } }
                previous = previousValues
                val revision = PreferenceWriteRevision.next()
                val currentWrite = PreferenceWrite(namespace, pending.first, pending.second, revision)
                write = currentWrite
                if (currentWrite.clear) values.clear()
                currentWrite.values.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                val affected = previous.keys + currentWrite.values.keys
                affected.forEach { key -> keyRevisions[key] = revision }
                if (waitForPersistence) {
                    outcome = persistence.commit(currentWrite)
                } else {
                    persistence.apply(currentWrite) { persisted ->
                        if (persisted) {
                            markPersisted(currentWrite)
                            notifyListeners(currentWrite)
                        } else {
                            rollback(currentWrite, previous)
                        }
                    }
                }
            }
            if (waitForPersistence) {
                val committedWrite = write ?: return false
                if (outcome != PreferenceCommitOutcome.SUCCESS) rollback(committedWrite, previous)
                if (outcome == PreferenceCommitOutcome.SUCCESS) {
                    markPersisted(committedWrite)
                    notifyListeners(committedWrite)
                }
                return outcome == PreferenceCommitOutcome.SUCCESS
            }
            return true
        }

        private fun markPersisted(write: PreferenceWrite) {
            synchronized(lock) {
                if (write.clear) persistedValues.clear()
                write.values.forEach { (key, value) ->
                    if (value == null) persistedValues.remove(key) else persistedValues[key] = value
                    persistedRevisions[key] = write.revision
                }
            }
        }

        private fun rollback(write: PreferenceWrite, previous: Map<String, PreferenceValue?>) {
            synchronized(lock) {
                val affected = previous.keys + write.values.keys
                affected.forEach { key ->
                    if (keyRevisions[key] != write.revision) return@forEach
                    val persistedValue = persistedValues[key]
                    if (persistedValue == null) {
                        values.remove(key)
                        keyRevisions.remove(key)
                    } else {
                        values[key] = persistedValue
                        persistedRevisions[key]?.let { keyRevisions[key] = it }
                    }
                }
            }
        }

        private fun notifyListeners(write: PreferenceWrite) {
            if (write.clear) {
                listeners.forEach { listener -> listener.onSharedPreferenceChanged(this@DataStoreSharedPreferences, null) }
            }
            write.values.keys.forEach { key ->
                listeners.forEach { listener -> listener.onSharedPreferenceChanged(this@DataStoreSharedPreferences, key) }
            }
        }
    }
}

internal fun MutablePreferences.applyWrite(write: PreferenceWrite) {
    if (write.clear) {
        asMap().keys
            .map { it.name }
            .filter { encoded ->
                DataStoreNamespaces.decode(encoded)?.first == write.namespace
            }
            .forEach { encoded -> removeEncoded(encoded) }
        set(
            longPreferencesKey(DataStoreNamespaces.revisionKey(write.namespace, DataStoreNamespaces.CLEAR_MARKER)),
            write.revision
        )
    }
    write.values.forEach { (key, value) ->
        val encoded = DataStoreNamespaces.encode(write.namespace, key)
        set(longPreferencesKey(DataStoreNamespaces.revisionKey(write.namespace, key)), write.revision)
        when (value) {
            is PreferenceValue.Text -> set(stringPreferencesKey(encoded), value.value)
            is PreferenceValue.TextSet -> set(stringSetPreferencesKey(encoded), value.value)
            is PreferenceValue.Integer -> set(intPreferencesKey(encoded), value.value)
            is PreferenceValue.LongNumber -> set(longPreferencesKey(encoded), value.value)
            is PreferenceValue.Decimal -> set(floatPreferencesKey(encoded), value.value)
            is PreferenceValue.BooleanValue -> set(booleanPreferencesKey(encoded), value.value)
            null -> removeEncoded(encoded)
        }
    }
}

internal fun MutablePreferences.applyWriteIfAbsent(write: PreferenceWrite): Set<String> {
    val inserted = linkedSetOf<String>()
    val consumedGeneration = this[
        longPreferencesKey(DataStoreNamespaces.consumedFallbackKey(write.namespace))
    ]
    write.values.forEach { (key, value) ->
        val overwrite = key in write.overwriteKeys ||
            (
                key in write.fallbackOverwriteKeys &&
                    write.fallbackGeneration != null &&
                    consumedGeneration != write.fallbackGeneration
                )
        if (!overwrite && hasUserWrite(write.namespace, key)) return@forEach
        val encoded = DataStoreNamespaces.encode(write.namespace, key)
        if (!overwrite && asMap().keys.any { it.name == encoded }) return@forEach
        set(longPreferencesKey(DataStoreNamespaces.revisionKey(write.namespace, key)), write.revision)
        when (value) {
            is PreferenceValue.Text -> set(stringPreferencesKey(encoded), value.value)
            is PreferenceValue.TextSet -> set(stringSetPreferencesKey(encoded), value.value)
            is PreferenceValue.Integer -> set(intPreferencesKey(encoded), value.value)
            is PreferenceValue.LongNumber -> set(longPreferencesKey(encoded), value.value)
            is PreferenceValue.Decimal -> set(floatPreferencesKey(encoded), value.value)
            is PreferenceValue.BooleanValue -> set(booleanPreferencesKey(encoded), value.value)
            null -> removeEncoded(encoded)
        }
        inserted.add(key)
    }
    write.fallbackGeneration?.let { generation ->
        set(longPreferencesKey(DataStoreNamespaces.consumedFallbackKey(write.namespace)), generation)
    }
    return inserted
}

internal fun MutablePreferences.hasUserWrite(namespace: String, key: String): Boolean {
    val clearRevision = longPreferencesKey(
        DataStoreNamespaces.revisionKey(namespace, DataStoreNamespaces.CLEAR_MARKER)
    )
    if (contains(clearRevision)) return true
    return contains(longPreferencesKey(DataStoreNamespaces.revisionKey(namespace, key)))
}

private fun MutablePreferences.removeEncoded(encoded: String) {
    val key = asMap().keys.firstOrNull { it.name == encoded } ?: return
    this -= key
}
