package ai.opencode.mobilecode.tca

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.coroutineContext
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * `@Shared(.key) var value: T` is `var value by Shared(SharedKey.key)`.
 *
 * A write is visible at once to every holder of the key, with no action, and persists through the
 * key's storage. Compose observes the value like any `StoreVar`. The storage is bound when the
 * property is created, from the dependencies of the store or test creating it.
 */
class Shared<T>(key: SharedKey<T>) : ReadWriteProperty<Any?, T> {
    internal val cell: Cell<T> = DependencyValues.current[SharedMemory].cell(key)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (thisRef is ObservableState && Overlay.current.get() == null && StoreContext.phase.get() == Phase.Effect) {
            check(!thisRef.attached) {
                "Read ${typeName(thisRef.javaClass)}.${property.name} from an effect. Copy the value into a local before Effect.run."
            }
        }
        return cell.read()
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        cell.write(value)
    }
}

/** The value inside `withLock`, Swift's `$0`. */
class Inout<T>(var value: T)

/**
 * `$seenIDs.withLock { $0.formUnion(ids) }` is `::seenIDs.withLock { value += ids }`. The read, the
 * change, and the write happen under one lock, so concurrent updates from effects are not lost.
 */
fun <T> KMutableProperty0<T>.withLock(body: Inout<T>.() -> Unit) {
    synchronized(withLockMonitor) {
        val box = Inout(get())
        box.body()
        set(box.value)
    }
}

private val withLockMonitor = Any()

/**
 * A `SharedKey`. Declare one the way Swift extends `SharedKey`:
 * `val SharedKey.Companion.whatsNewSeenIDs get() = SharedKey.fileStorage(…, default = emptySet<String>())`.
 */
class SharedKey<T> @PublishedApi internal constructor(
    internal val strategy: SharedStrategy,
    internal val serializer: KSerializer<T>,
    internal val loadOverride: (() -> T?)? = null,
    internal val saveOverride: ((T) -> Unit)? = null,
    internal val defaultValue: () -> T,
) {
    companion object
}

/** `$savedServers.withLock` when the key is not held as a property. Same lock as a property `withLock`. */
fun <T> SharedKey<T>.withLock(body: Inout<T>.() -> Unit) {
    synchronized(withLockMonitor) {
        val cell = DependencyValues.current[SharedMemory].cell(this)
        val box = Inout(cell.read())
        box.body()
        cell.write(box.value)
    }
}

/** `.appStorage("name")`, a preference. */
inline fun <reified T> SharedKey.Companion.appStorage(name: String, default: T): SharedKey<T> =
    SharedKey(SharedStrategy.appStorage(name), serializer()) { default }

/** `.fileStorage(url)`, a JSON file written atomically off the main thread. */
inline fun <reified T> SharedKey.Companion.fileStorage(file: StorageFile, default: T): SharedKey<T> =
    SharedKey(SharedStrategy.fileStorage(file), serializer()) { default }

/** `.inMemory("name")`, gone when the process ends. */
inline fun <reified T> SharedKey.Companion.inMemory(name: String, default: T): SharedKey<T> =
    SharedKey(SharedStrategy.inMemory(name), serializer()) { default }

/** A keychain item: JSON sealed with AES-GCM under a key that never leaves the Android Keystore. */
inline fun <reified T> SharedKey.Companion.keychain(account: String, default: T): SharedKey<T> =
    SharedKey(SharedStrategy.keychain(account), serializer()) { default }

/**
 * The transcript snapshot cache. [save] is scheduled, not written immediately: the coalesced writer
 * waits 500 ms on the wall clock, and a newer value replaces a queued one. [load] returning null
 * uses [default].
 */
fun <T> SharedKey.Companion.protectedTranscriptCache(
    identity: String,
    default: T,
    serializer: KSerializer<T>,
    load: () -> T?,
    save: (T) -> Unit,
): SharedKey<T> =
    SharedKey(
        SharedStrategy.protectedTranscriptCache(identity),
        serializer,
        loadOverride = load,
        saveOverride = save,
        defaultValue = { default },
    )

sealed interface SharedStrategy {
    val id: String

    data class appStorage(val name: String) : SharedStrategy {
        override val id get() = "appStorage:$name"
    }

    data class fileStorage(val file: StorageFile) : SharedStrategy {
        override val id get() = "fileStorage:${file.directory}/${file.path}"
    }

    data class inMemory(val name: String) : SharedStrategy {
        override val id get() = "inMemory:$name"
    }

    data class keychain(val account: String) : SharedStrategy {
        override val id get() = "keychain:$account"
    }

    /** Application Support `OpenCode/transcript-snapshots.json`, written by [CoalescedWriter]. */
    data class protectedTranscriptCache(val identity: String) : SharedStrategy {
        override val id get() = "protectedTranscriptCache:$identity"
    }
}

/** `.documentsDirectory`, `.cachesDirectory`, `.applicationSupportDirectory` */
enum class StorageDirectory {
    documents,
    caches,
    applicationSupport,
    ;

    fun appending(component: String) = StorageFile(this, component)
}

data class StorageFile(val directory: StorageDirectory, val path: String) {
    fun appending(component: String) = StorageFile(directory, "$path/$component")
}

/** One value per key, shared by every `Shared` that names the key. A dependency, fresh in each test. */
class SharedMemory internal constructor(private val backend: SharedBackend) {
    private val cells = HashMap<String, Cell<*>>()

    @Suppress("UNCHECKED_CAST")
    internal fun <T> cell(key: SharedKey<T>): Cell<T> =
        synchronized(cells) { cells.getOrPut(key.strategy.id) { SharedCell(key, load(key), backend) } as Cell<T> }

    private fun <T> load(key: SharedKey<T>): T {
        key.loadOverride?.let { return it() ?: key.defaultValue() }
        val stored = backend.load(key.strategy) ?: return key.defaultValue()
        return runCatching { json.decodeFromString(key.serializer, stored) }.getOrElse { key.defaultValue() }
    }

    companion object : DependencyKey<SharedMemory> {
        @Volatile
        private var installed: SharedMemory? = null

        /** Installs the live storage. `OpenCodeApp.onCreate` calls it before the store exists. */
        fun installLive(context: Context) {
            val app = context.applicationContext
            TranscriptCacheDirectory.install(app)
            installed = SharedMemory(AndroidSharedBackend(app))
        }

        override val liveValue: SharedMemory
            get() =
                installed ?: error(
                    "Shared storage is not installed. Call SharedMemory.installLive(context) in Application.onCreate. " +
                        "In a test, add @get:Rule val dependencies = TestDependencies().",
                )

        override val testValue: SharedMemory
            get() = SharedMemory(InMemorySharedBackend())
    }
}

private val json = Json { ignoreUnknownKeys = true }

private class SharedCell<T>(private val key: SharedKey<T>, initial: T, private val backend: SharedBackend) : Cell<T>(initial) {
    override val label get() = key.strategy.id

    override val isShared get() = true

    override fun didWrite(value: T) {
        val override = key.saveOverride
        if (override != null) {
            override(value)
            return
        }
        backend.save(key.strategy, json.encodeToString(key.serializer, value))
    }
}

internal interface SharedBackend {
    fun load(strategy: SharedStrategy): String?

    fun save(strategy: SharedStrategy, encoded: String)
}

internal class InMemorySharedBackend : SharedBackend {
    private val values = ConcurrentHashMap<String, String>()

    override fun load(strategy: SharedStrategy): String? = values[strategy.id]

    override fun save(strategy: SharedStrategy, encoded: String) {
        if (strategy !is SharedStrategy.inMemory) values[strategy.id] = encoded
    }
}

/** `filesDir/Application Support/OpenCode/transcript-snapshots.json`, or a temp dir before install. */
object TranscriptCacheDirectory {
    @Volatile
    var root: File? = null

    fun install(context: Context) {
        root = File(context.applicationContext.filesDir, "Application Support")
    }

    fun file(): File {
        val base = root ?: File(System.getProperty("java.io.tmpdir"), "opencode")
        return File(base, "OpenCode/transcript-snapshots.json")
    }
}

/**
 * Replaces a queued write instead of writing each one. The wait is [delay] on the wall clock, so a
 * `TestClock` does not move it. [flush] writes whatever is queued and cancels the wait.
 */
class CoalescedWriter(
    private val write: (String?) -> Unit,
    private val coalesce: Duration = 500.milliseconds,
) {
    private class State {
        var pending: String? = null
        var hasPending = false
        var generation = 0
        var job: Job? = null
    }

    private val state = State()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule(encoded: String?) {
        val generation = synchronized(state) {
            state.pending = encoded
            state.hasPending = true
            state.generation += 1
            state.job?.cancel()
            state.generation
        }
        val task = scope.launch {
            delay(coalesce)
            if (!coroutineContext.isActive) return@launch
            writePending(generation)
        }
        synchronized(state) {
            if (state.generation != generation) task.cancel() else state.job = task
        }
    }

    suspend fun flush() {
        val pending = synchronized(state) {
            state.job?.cancel()
            state.job = null
            if (!state.hasPending) return
            state.hasPending = false
            state.pending
        }
        write(pending)
    }

    private fun writePending(generation: Int) {
        val pending = synchronized(state) {
            if (state.generation != generation || !state.hasPending) return
            state.job = null
            state.hasPending = false
            state.pending
        }
        write(pending)
    }

    companion object {
        private val installed = ConcurrentHashMap<String, CoalescedWriter>()

        /** One writer per identity. The first [write] wins; later callers share it. */
        fun shared(identity: String, write: (String?) -> Unit): CoalescedWriter =
            installed.computeIfAbsent(identity) { CoalescedWriter(write) }
    }
}

internal class AndroidSharedBackend(context: Context) : SharedBackend {
    private val preferences = context.getSharedPreferences("ai.opencode.mobilecode.app-storage", Context.MODE_PRIVATE)
    private val files =
        FileStore(
            directory = { directory ->
                when (directory) {
                    StorageDirectory.documents -> context.filesDir
                    StorageDirectory.caches -> context.cacheDir
                    StorageDirectory.applicationSupport -> File(context.filesDir, "Application Support")
                }
            },
        )
    private val keychain = Keychain.live(context)

    override fun load(strategy: SharedStrategy): String? =
        when (strategy) {
            is SharedStrategy.appStorage -> preferences.getString(strategy.name, null)
            is SharedStrategy.fileStorage -> files.read(strategy.file)
            is SharedStrategy.keychain -> keychain.read(strategy.account)
            is SharedStrategy.inMemory -> null
            is SharedStrategy.protectedTranscriptCache ->
                TranscriptCacheDirectory.file().takeIf(File::exists)?.readText()
        }

    override fun save(strategy: SharedStrategy, encoded: String) {
        when (strategy) {
            is SharedStrategy.appStorage -> preferences.edit().putString(strategy.name, encoded).apply()
            is SharedStrategy.fileStorage -> files.write(strategy.file, encoded)
            is SharedStrategy.keychain -> keychain.write(strategy.account, encoded.takeIf { it != "null" })
            is SharedStrategy.inMemory -> Unit
            is SharedStrategy.protectedTranscriptCache ->
                CoalescedWriter.shared(strategy.identity, ::writeTranscriptCache).schedule(encoded)
        }
    }

    private fun writeTranscriptCache(encoded: String?) {
        val file = TranscriptCacheDirectory.file()
        if (encoded == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(encoded)
        check(temporary.renameTo(file)) { "Couldn't replace $file" }
    }
}

/** JSON files replaced by an atomic rename on one background thread. A newer write supersedes a queued one. */
internal class FileStore(
    private val directory: (StorageDirectory) -> File,
    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "shared-file-store").apply { isDaemon = true } },
) {
    private val pending = ConcurrentHashMap<File, String>()

    fun read(file: StorageFile): String? {
        val target = resolve(file)
        return pending[target] ?: target.takeIf(File::exists)?.readText()
    }

    fun write(file: StorageFile, contents: String) {
        val target = resolve(file)
        pending[target] = contents
        executor.execute { flush(target) }
    }

    private fun flush(target: File) {
        val contents = pending[target] ?: return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(contents)
        check(temporary.renameTo(target)) { "Couldn't replace $target" }
        pending.remove(target, contents)
    }

    private fun resolve(file: StorageFile) = File(directory(file.directory), file.path)
}

/**
 * The keychain: each item is AES-256-GCM sealed under a Keystore key and bound to its account name.
 * An item that no longer decrypts, for example after the key was invalidated, reads as absent.
 */
class Keychain internal constructor(private val preferences: SharedPreferences, private val secretKey: () -> SecretKey) {
    fun read(account: String): String? {
        val stored = preferences.getString(account, null) ?: return null
        return runCatching {
            val sealed = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    fun write(account: String, value: String?) {
        if (value == null) {
            preferences.edit().remove(account).commit()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(account.toByteArray(Charsets.UTF_8))
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(sealed.size > IV_BYTES && cipher.iv.size == IV_BYTES) { "Unexpected GCM IV length ${cipher.iv.size}" }
        preferences.edit().putString(account, Base64.getEncoder().encodeToString(sealed)).commit()
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val ALIAS = "ai.opencode.mobilecode.keychain"

        fun live(context: Context): Keychain {
            val key = lazy(::keystoreKey)
            return Keychain(context.getSharedPreferences("ai.opencode.mobilecode.keychain", Context.MODE_PRIVATE)) { key.value }
        }

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
