package ai.opencode.mobilecode.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SymbolNamesTest {
    @Test
    fun everyMappedNameHasADrawable() {
        assertEquals(SymbolNames.all.size, SymbolNames.all.toSet().size)
        SymbolNames.all.forEach { name ->
            assertNotNull(name, SymbolNames.drawable(name))
        }
    }
}
