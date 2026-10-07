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
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.KProperty
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

/** `$seenIDs.withLock { $0.formUnion(ids) }` is `::seenIDs.withLock { value += ids }`. */
fun <T> KMutableProperty0<T>.withLock(body: Inout<T>.() -> Unit) {
    val box = Inout(get())
    box.body()
    set(box.value)
}

/**
 * A `SharedKey`. Declare one the way Swift extends `SharedKey`:
 * `val SharedKey.Companion.whatsNewSeenIDs get() = SharedKey.fileStorage(…, default = emptySet<String>())`.
 */
class SharedKey<T> @PublishedApi internal constructor(
    internal val strategy: SharedStrategy,
    internal val serializer: KSerializer<T>,
    internal val defaultValue: () -> T,
) {
    companion object
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
        val stored = backend.load(key.strategy) ?: return key.defaultValue()
        return runCatching { json.decodeFromString(key.serializer, stored) }.getOrElse { key.defaultValue() }
    }

    companion object : DependencyKey<SharedMemory> {
        @Volatile
        private var installed: SharedMemory? = null

        /** Installs the live storage. `OpenCodeApp.onCreate` calls it before the store exists. */
        fun installLive(context: Context) {
            if (installed == null) installed = SharedMemory(AndroidSharedBackend(context.applicationContext))
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
    override fun didWrite(value: T) {
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
        }

    override fun save(strategy: SharedStrategy, encoded: String) {
        when (strategy) {
            is SharedStrategy.appStorage -> preferences.edit().putString(strategy.name, encoded).apply()
            is SharedStrategy.fileStorage -> files.write(strategy.file, encoded)
            is SharedStrategy.keychain -> keychain.write(strategy.account, encoded.takeIf { it != "null" })
            is SharedStrategy.inMemory -> Unit
        }
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

    internal companion object {
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
