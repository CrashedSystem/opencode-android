package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeProject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FirstComputerPairingTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun project(directory: String, name: String? = null) = OpenCodeProject(id = directory, name = name ?: directory, directory = directory)

    @Test
    fun homeFoldersAreNotProjects() {
        for (directory in listOf(
            "/", "/Users/kit", "/home/kit", "/root", "/var/root", "C:\\Users\\kit", "/Users/kit/",
            // Termux on the phone the server often runs on.
            "/data/data/com.termux/files/home",
        )) {
            assertTrue(directory, project(directory).isHomeFolder)
        }
        for (directory in listOf(
            "/Users/kit/code", "/srv/web", "/home/kit/app", "/Volumes/SSD", "/tmp",
            "/data/data/com.termux/files/home/projects/opencode",
        )) {
            assertFalse(directory, project(directory).isHomeFolder)
        }
    }
}
