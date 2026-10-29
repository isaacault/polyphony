package app.polyphony.ui.settings.servers

import app.polyphony.core.domain.model.ContinuoDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinuoDeviceLogicTest {

    private fun device(id: String, name: String = id, created: Long? = null, seen: Long? = null, current: Boolean = false) =
        ContinuoDevice(id, name, "android", created, seen, current)

    @Test
    fun `this phone comes first, then the most recently seen`() {
        val list = listOf(
            device("stale", seen = 100),
            device("never", created = 50),
            device("me", seen = 10, current = true),
            device("recent", seen = 900),
            device("unknown"),
        )
        assertEquals(listOf("me", "recent", "stale", "never", "unknown"), ContinuoDeviceLogic.ordered(list).map { it.id })
    }

    @Test
    fun `a same-named device signed in before this one is an earlier install`() {
        val me = device("me", name = "Pixel 8", created = 1_000, current = true)
        val restoredFrom = device("old", name = "pixel 8 ", created = 500)
        val later = device("later", name = "Pixel 8", created = 2_000)
        val tablet = device("tab", name = "Galaxy Tab", created = 100)
        val undated = device("undated", name = "Pixel 8")
        val all = listOf(me, restoredFrom, later, tablet, undated)

        assertTrue(ContinuoDeviceLogic.isEarlierInstall(restoredFrom, all))
        assertTrue(ContinuoDeviceLogic.isEarlierInstall(undated, all))
        assertFalse(ContinuoDeviceLogic.isEarlierInstall(later, all))
        assertFalse(ContinuoDeviceLogic.isEarlierInstall(tablet, all))
        assertFalse(ContinuoDeviceLogic.isEarlierInstall(me, all))
        assertFalse(ContinuoDeviceLogic.isEarlierInstall(restoredFrom, all - me))
    }

    @Test
    fun `revoking the others leaves only this phone`() {
        val list = listOf(device("a"), device("me", current = true), device("b"))
        assertEquals(listOf("me"), ContinuoDeviceLogic.afterRevokingOthers(list).map { it.id })
    }
}
