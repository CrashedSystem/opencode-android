package ai.opencode.mobilecode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Calls the live client from the main thread on a real Android runtime, where socket reads on the
 * main thread throw. Opt in with `-Pandroid.testInstrumentationRunnerArguments.serverURL=...` and
 * `...password=...`; it is skipped otherwise.
 */
@RunWith(AndroidJUnit4::class)
class LiveServerOnDeviceTest {
    @Test
    fun homeSnapshotLoadsFromTheMainThread() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("serverURL")
        val password = arguments.getString("password")
        assumeTrue("set serverURL and password to run", url != null && password != null)
        val server = OpenCodeServer(url = url!!, username = "opencode", password = password!!)
        val snapshot = runBlocking { withContext(Dispatchers.Main) { OpenCodeClient.liveValue.homeSnapshot(server) } }
        assertTrue("the server lists at least one project", snapshot.projects.isNotEmpty())
    }
}
