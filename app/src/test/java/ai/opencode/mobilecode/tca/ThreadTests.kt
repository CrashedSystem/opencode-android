package ai.opencode.mobilecode.tca

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ThreadTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun sendingFromAnotherThreadThrows() {
        val store = TestStore(initialState = { Child() }) { ChildFeature() }
        var failure: Throwable? = null
        thread { failure = runCatching { store.send(ChildAction.increment) }.exceptionOrNull() }.join()
        assertEquals("Store.send must be called on the main thread. An effect sends through its Send.", failure?.message)
        store.send(ChildAction.increment) { it.count = 1 }
    }
}
