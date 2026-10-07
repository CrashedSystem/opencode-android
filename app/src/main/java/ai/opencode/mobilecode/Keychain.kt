@file:PortedFrom("OpenCode/Keychain.swift", "553a407")

package ai.opencode.mobilecode

import ai.opencode.mobilecode.tca.DependencyKey
import ai.opencode.mobilecode.tca.DependencyValues
import ai.opencode.mobilecode.tca.Keychain
import ai.opencode.mobilecode.tca.Shared
import ai.opencode.mobilecode.tca.SharedKey
import ai.opencode.mobilecode.tca.keychain
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Server credentials are secrets, so they live in the keychain rather than a
// JSON file in Documents. Implementing `SharedKey` (instead of hand-rolling a
// store) keeps every call site as a plain `@Shared` property.

/// Either the real keychain (identified by its service name) or an in-memory
/// dictionary for tests, so a test run can never read or clobber real secrets.
class KeychainStorage private constructor(
    val identity: String,
    private val memory: ConcurrentHashMap<String, ByteArray>?,
    private val keychain: Keychain?,
) {
    fun data(forKey: String): ByteArray? {
        memory?.let { return it[forKey] }
        return keychain?.read(forKey)?.toByteArray(Charsets.UTF_8)
    }

    fun set(data: ByteArray, forKey: String) {
        memory?.let {
            it[forKey] = data
            return
        }
        keychain?.write(forKey, data.toString(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean = other is KeychainStorage && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    companion object : DependencyKey<KeychainStorage> {
        const val service = "dev.kitlangton.opencode.servers"

        fun live(keychain: Keychain) = KeychainStorage(service, null, keychain)

        fun inMemory() = KeychainStorage("in-memory-${UUID.randomUUID()}", ConcurrentHashMap(), null)

        fun install(context: Context) {
            installed = live(Keychain.live(context.applicationContext))
        }

        override val liveValue: KeychainStorage
            get() = installed ?: error("Keychain storage is not installed. Call KeychainStorage.install(context) in Application.onCreate.")

        override val testValue: KeychainStorage
            get() = inMemory()

        @Volatile
        private var installed: KeychainStorage? = null
    }
}

var DependencyValues.keychainStorage: KeychainStorage
    get() = this[KeychainStorage]
    set(value) {
        this[KeychainStorage] = value
    }

/// Debug-only Keystore round trip. The first launch seals a value; the next
/// process start reads it back. Grep logcat for `OpenCodeKeychain`.
fun verifyKeychainRoundTrip(context: Context) {
    if (!BuildConfig.DEBUG) return
    runCatching {
        val keychain = Keychain.live(context.applicationContext)
        val previous = keychain.read(PROBE)
        var probe by Shared(SharedKey.keychain(PROBE, default = null as String?))
        val persisted = probe == PROBE_VALUE
        probe = PROBE_VALUE
        val sealed = keychain.read(PROBE)
        val sameProcess = sealed?.contains(PROBE_VALUE) == true
        Log.i(
            TAG,
            "same-process=${if (sameProcess) "ok" else "failed"} persisted=${if (persisted) "ok" else "pending"} stored=${sealed ?: "absent"} previous=${previous ?: "absent"}",
        )
        if (persisted && sameProcess) Log.i(TAG, "persisted across restart")
    }.onFailure { error ->
        Log.e(TAG, "probe failed", error)
    }
}

private const val TAG = "OpenCodeKeychain"
private const val PROBE = "keychain-probe"
private const val PROBE_VALUE = "round-trip"
