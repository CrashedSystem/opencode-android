package ai.opencode.mobilecode.tca

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreTests {
    @get:Rule
    val dependencies = TestDependencies()

    @Test
    fun aRunEffectStartsOnALaterMainThreadTurnAfterSendReturns() {
        val store = Store(initialState = { Log() }) { LogFeature() }
        store.send(LogAction.ordered)
        assertEquals(listOf("sent"), store.state.entries)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("sent", "run"), store.state.entries)
    }
}
