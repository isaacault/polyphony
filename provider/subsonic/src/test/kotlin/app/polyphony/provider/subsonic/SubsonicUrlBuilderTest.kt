package app.polyphony.provider.subsonic

import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.provider.subsonic.api.SubsonicUrlBuilder
import app.polyphony.provider.subsonic.internal.md5Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicUrlBuilderTest {

    @Test
    fun `token auth includes salted md5 and standard params`() {
        val urls = SubsonicUrlBuilder.forServer(testServer)
        val url = urls.build("ping")
        assertEquals("https", url.scheme)
        assertEquals("music.example.com", url.host)
        assertEquals("/rest/ping", url.encodedPath)
        assertEquals("alice", url.queryParameter("u"))
        val salt = url.queryParameter("s")
        assertNotNull(salt)
        assertEquals(md5Hex("sesame" + salt), url.queryParameter("t"))
        assertNull(url.queryParameter("p"))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("Polyphony", url.queryParameter("c"))
        assertEquals("json", url.queryParameter("f"))
    }

    @Test
    fun `salt is stable for a server so artwork urls are cacheable`() {
        val a = SubsonicUrlBuilder.forServer(testServer).build("getCoverArt", mapOf("id" to "al-1", "size" to 300))
        val b = SubsonicUrlBuilder.forServer(testServer).build("getCoverArt", mapOf("id" to "al-1", "size" to 300))
        assertEquals(a, b)
    }

    @Test
    fun `legacy auth sends hex encoded password`() {
        val server = testServer.copy(options = mapOf(ServerOptions.LEGACY_AUTH to "true"))
        val url = SubsonicUrlBuilder.forServer(server).build("ping")
        assertEquals("enc:" + "sesame".toByteArray().joinToString("") { "%02x".format(it) }, url.queryParameter("p"))
        assertNull(url.queryParameter("t"))
        assertNull(url.queryParameter("s"))
    }

    @Test
    fun `list params repeat the key and nulls are dropped`() {
        val url = SubsonicUrlBuilder.forServer(testServer)
            .build("updatePlaylist", mapOf("playlistId" to "p1", "songIdToAdd" to listOf("a", "b"), "name" to null))
        assertEquals(listOf("a", "b"), url.queryParameterValues("songIdToAdd"))
        assertNull(url.queryParameter("name"))
    }

    @Test
    fun `base url normalisation`() {
        assertEquals("https://music.example.com", SubsonicUrlBuilder.normalizeBaseUrl("music.example.com"))
        assertEquals("https://music.example.com", SubsonicUrlBuilder.normalizeBaseUrl("  https://music.example.com/  "))
        assertEquals("http://192.168.1.10:4533", SubsonicUrlBuilder.normalizeBaseUrl("http://192.168.1.10:4533/rest/"))
        assertEquals("https://host/navidrome", SubsonicUrlBuilder.normalizeBaseUrl("https://host/navidrome/rest"))
        assertTrue(runCatching { SubsonicUrlBuilder.normalizeBaseUrl("ftp://nope") }.isFailure)
        assertTrue(runCatching { SubsonicUrlBuilder.normalizeBaseUrl("   ") }.isFailure)
    }
}
