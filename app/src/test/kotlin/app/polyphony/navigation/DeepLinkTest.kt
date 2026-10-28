package app.polyphony.navigation

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {

    private fun server(id: String, url: String, continuo: Boolean = false) = Server(
        id = id,
        type = ServerType.SUBSONIC,
        name = id,
        baseUrl = url,
        username = "isaac",
        credential = "x",
        deviceId = "d",
        options = if (continuo) mapOf(ServerOptions.CONTINUO to "true") else emptyMap(),
    )

    @Test
    fun `parses continuo's album link`() {
        assertEquals(
            DeepLink.Album("music-api.ault.me", "0F0yuQ2ju3i1HBfLVVdSTE"),
            DeepLink.parse("polyphony://album/music-api.ault.me/0F0yuQ2ju3i1HBfLVVdSTE"),
        )
    }

    @Test
    fun `requests links`() {
        assertEquals(DeepLink.Requests(17), DeepLink.parse("polyphony://requests/17"))
        assertEquals(DeepLink.Requests(null), DeepLink.parse("polyphony://requests"))
    }

    @Test
    fun `inbox link`() {
        assertEquals(DeepLink.Inbox, DeepLink.parse("polyphony://inbox"))
        assertEquals(DeepLink.Inbox, DeepLink.parse("polyphony://inbox/"))
        assertNull(DeepLink.parse("polyphony://inbox/3"))
    }

    @Test
    fun `a receipt rides along without changing the link`() {
        val album = DeepLink.withReceipt("polyphony://album/music-api.ault.me/nd-1", "server 1/ü", 42)
        assertEquals(DeepLink.Album("music-api.ault.me", "nd-1"), DeepLink.parse(album))
        assertEquals(DeepLink.Receipt("server 1/ü", 42), DeepLink.receiptOf(album))

        val request = DeepLink.withReceipt("polyphony://requests/17", "s1", 7)
        assertEquals(DeepLink.Requests(17), DeepLink.parse(request))
        assertEquals(DeepLink.Receipt("s1", 7), DeepLink.receiptOf(request))

        val inbox = DeepLink.withReceipt("polyphony://inbox", "s1", 8)
        assertEquals(DeepLink.Inbox, DeepLink.parse(inbox))
        assertEquals(DeepLink.Receipt("s1", 8), DeepLink.receiptOf(inbox))

        val extra = DeepLink.withReceipt("polyphony://requests/17?x=1", "s1", 9)
        assertEquals(DeepLink.Receipt("s1", 9), DeepLink.receiptOf(extra))
    }

    @Test
    fun `links without a complete receipt have none`() {
        assertNull(DeepLink.receiptOf("polyphony://requests/17"))
        assertNull(DeepLink.receiptOf("polyphony://requests/17?fromServer=s1"))
        assertNull(DeepLink.receiptOf("polyphony://requests/17?fromServer=s1&notification=x"))
        assertNull(DeepLink.receiptOf("polyphony://requests/17?fromServer=&notification=3"))
        assertNull(DeepLink.receiptOf("not a uri at all ::"))
    }

    @Test
    fun `launcher shortcut links still work`() {
        assertEquals(DeepLink.Player, DeepLink.parse("polyphony://player"))
        assertEquals(DeepLink.Search, DeepLink.parse("polyphony://search"))
        assertEquals(DeepLink.Shuffle, DeepLink.parse("polyphony://shuffle"))
        assertEquals(DeepLink.Resume, DeepLink.parse("polyphony://resume"))
    }

    @Test
    fun `malformed or foreign links are ignored`() {
        assertNull(DeepLink.parse("polyphony://album/music-api.ault.me"))
        assertNull(DeepLink.parse("polyphony://album/a/b/c"))
        assertNull(DeepLink.parse("polyphony://requests/not-a-number"))
        assertNull(DeepLink.parse("polyphony://requests/1/2"))
        assertNull(DeepLink.parse("https://album/music-api.ault.me/x"))
        assertNull(DeepLink.parse("not a uri at all ::"))
    }

    @Test
    fun `server is found by host, case-insensitively`() {
        val servers = listOf(server("a", "https://music.ault.me"), server("b", "https://Music-API.ault.me"))
        val link = DeepLink.Album("music-api.ault.me", "x")
        assertEquals("b", DeepLink.serverFor(link, servers)?.id)
    }

    @Test
    fun `server id wins, and a continuo-linked server is preferred for a shared host`() {
        val plain = server("plain", "https://music-api.ault.me")
        val linked = server("linked", "https://music-api.ault.me", continuo = true)
        assertEquals("linked", DeepLink.serverFor(DeepLink.Album("music-api.ault.me", "x"), listOf(plain, linked))?.id)
        assertEquals("plain", DeepLink.serverFor(DeepLink.Album("plain", "x"), listOf(plain, linked))?.id)
    }

    @Test
    fun `unknown server resolves to nothing`() {
        assertNull(DeepLink.serverFor(DeepLink.Album("elsewhere.example", "x"), listOf(server("a", "https://music.ault.me"))))
    }
}
