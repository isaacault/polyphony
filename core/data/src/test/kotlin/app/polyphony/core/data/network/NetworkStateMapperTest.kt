package app.polyphony.core.data.network

import app.polyphony.core.data.network.NetworkStateMapper.Flags
import app.polyphony.core.domain.transcode.NetworkKind
import app.polyphony.core.domain.transcode.NetworkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStateMapperTest {
    @Test
    fun `no capabilities means offline`() {
        assertEquals(NetworkState.OFFLINE, NetworkStateMapper.map(null))
        assertEquals(NetworkState.OFFLINE, NetworkStateMapper.map(Flags(hasInternet = false, wifi = true)))
    }

    @Test
    fun `unmetered wifi`() {
        val s = NetworkStateMapper.map(Flags(wifi = true, notMetered = true))
        assertEquals(NetworkKind.WIFI, s.kind)
        assertFalse(s.isMetered)
        assertTrue(s.isUnmeteredWifi)
    }

    @Test
    fun `hotspot wifi is metered`() {
        val s = NetworkStateMapper.map(Flags(wifi = true))
        assertEquals(NetworkKind.WIFI, s.kind)
        assertTrue(s.isMetered)
    }

    @Test
    fun `temporarily unmetered cellular counts as unmetered`() {
        val s = NetworkStateMapper.map(Flags(cellular = true, temporarilyNotMetered = true))
        assertEquals(NetworkKind.CELLULAR, s.kind)
        assertFalse(s.isMetered)
    }

    @Test
    fun `ethernet wins over wifi and vpn is flagged`() {
        val s = NetworkStateMapper.map(Flags(wifi = true, ethernet = true, vpn = true, notMetered = true))
        assertEquals(NetworkKind.ETHERNET, s.kind)
        assertTrue(s.isVpn)
    }

    @Test
    fun `vpn only transport is other`() {
        assertEquals(NetworkKind.OTHER, NetworkStateMapper.map(Flags(vpn = true)).kind)
    }
}
