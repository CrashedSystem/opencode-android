package ai.opencode.mobilecode.tca

import android.content.Context
import java.io.File
import java.time.Instant
import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class DependencyTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun defaultsAreBuiltOncePerContainerAndNeverShared() {
        val first = DependencyValues.test()
        val second = DependencyValues.test()
        assertSame(first[Counter], first[Counter])
        assertNotSame(first[Counter], second[Counter])
        val child = first.child()
        assertSame(first[Counter], child[Counter])
        child[Counter] = Counter()
        assertNotSame(first[Counter], child[Counter])
    }

    @Test
    fun testsSeeIsTesting() {
        expect(isTesting)
        expect(!DependencyValues.live.isTesting)
    }

    @Test
    fun aKeyWithoutATestValueFailsClosed() {
        val failure = assertThrows(UnimplementedDependency::class.java) { DependencyValues.current[LiveOnly] }
        assertEquals("LiveOnly has no test value. Override it in TestStore(withDependencies = { … }).", failure.message)
    }

    @Test
    fun withDependenciesOverridesForItsOperation() {
        val now = Instant.parse("2026-10-06T12:00:00Z")
        assertEquals(now, withDependencies({ it.date = DateGenerator.constant(now) }) { DependencyValues.current.date.now })
        assertThrows(UnimplementedDependency::class.java) { DependencyValues.current.date.now }
    }

    @Test
    fun incrementingUUIDsCountUp() {
        val uuid = UUIDGenerator.incrementing()
        assertEquals("00000000-0000-0000-0000-000000000000", uuid().toString())
        assertEquals("00000000-0000-0000-0000-000000000001", uuid().toString())
    }

    @Test
    fun sharedValuesDoNotSurviveARelaunchOfAnInMemoryKey() {
        val backend = InMemorySharedBackend()
        val key = SharedKey.inMemory("upload-unsupported-servers", default = emptySet<String>())
        SharedMemory(backend).cell(key).write(setOf("srv"))
        assertEquals(emptySet<String>(), SharedMemory(backend).cell(key).live)
        val persisted = SharedKey.appStorage("persisted", default = 0)
        SharedMemory(backend).cell(persisted).write(7)
        assertEquals(7, SharedMemory(backend).cell(persisted).live)
    }
}

class Counter {
    companion object : DependencyKey<Counter> {
        override val liveValue get() = Counter()
        override val testValue get() = Counter()
    }
}

object LiveOnly : DependencyKey<String> {
    override val liveValue = "live"
}

class SharedOutsideATestContextTests {
    @Test
    fun sharedStorageFailsClosedWithoutTheTestRule() {
        val failure = assertThrows(IllegalStateException::class.java) { Toggle() }
        assertEquals(
            "Shared storage is not installed. Call SharedMemory.installLive(context) in Application.onCreate. " +
                "In a test, add @get:Rule val dependencies = TestDependencies().",
            failure.message,
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharedStorageTests {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun keychainItemsAreSealedAndBoundToTheirAccount() {
        val preferences = context.getSharedPreferences("keychain-test", Context.MODE_PRIVATE)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keychain = Keychain(preferences) { key }
        val server = """{"url":"http://10.0.2.2:4096","password":"hunter2"}"""

        keychain.write("saved-server", server)
        assertEquals(server, keychain.read("saved-server"))
        val stored = preferences.getString("saved-server", null)!!
        assertFalse(String(Base64.getDecoder().decode(stored), Charsets.ISO_8859_1).contains("hunter2"))

        preferences.edit().putString("other-account", stored).commit()
        assertNull(keychain.read("other-account"))

        val tampered = Base64.getDecoder().decode(stored).also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() }
        preferences.edit().putString("saved-server", Base64.getEncoder().encodeToString(tampered)).commit()
        assertNull(keychain.read("saved-server"))

        keychain.write("saved-server", null)
        assertNull(preferences.getString("saved-server", null))
    }

    @Test
    fun appStorageAndFilesSurviveARelaunch() {
        val flag = SharedKey.appStorage("relaunch-flag", default = false)
        val seen = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "relaunch-seen.json"), default = emptySet<String>())
        val first = SharedMemory(AndroidSharedBackend(context))
        first.cell(flag).write(true)
        first.cell(seen).write(setOf("a", "b"))

        val file = File(context.filesDir, "relaunch-seen.json")
        val deadline = System.currentTimeMillis() + 2_000
        while (!file.exists() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("""["a","b"]""", file.readText())
        assertFalse(File(context.filesDir, "relaunch-seen.json.tmp").exists())

        val second = SharedMemory(AndroidSharedBackend(context))
        assertEquals(true, second.cell(flag).live)
        assertEquals(setOf("a", "b"), second.cell(seen).live)
    }

    @Test
    fun aFileThatDoesNotDecodeLoadsTheDefault() {
        File(context.filesDir, "corrupt.json").writeText("{not json")
        val key = SharedKey.fileStorage(StorageDirectory.documents.appending(component = "corrupt.json"), default = setOf("x"))
        assertEquals(setOf("x"), SharedMemory(AndroidSharedBackend(context)).cell(key).live)
    }
}
