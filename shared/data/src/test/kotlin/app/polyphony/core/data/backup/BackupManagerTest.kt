package app.polyphony.core.data.backup

import app.polyphony.core.domain.model.Server
import app.polyphony.core.domain.model.ServerOptions
import app.polyphony.core.domain.model.ServerType
import app.polyphony.core.domain.repository.AppearanceSettings
import app.polyphony.core.domain.repository.AppSettings
import app.polyphony.core.domain.repository.LibrarySettings
import app.polyphony.core.domain.repository.PlaybackSettings
import app.polyphony.core.domain.repository.ServerRepository
import app.polyphony.core.domain.repository.SettingsRepository
import app.polyphony.core.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    // Mirrors the Json provided to the app (NetworkModule): tolerant of unknown/missing keys.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val sampleSettings = AppSettings(
        onboardingComplete = true,
        appearance = AppearanceSettings(themeMode = ThemeMode.DARK, pureBlack = true, gridColumns = 3),
        playback = PlaybackSettings(crossfadeMs = 5_000, scrobble = false),
        library = LibrarySettings(autoSyncHours = 12, syncOnWifiOnly = false),
    )

    private val subsonic = Server(
        id = "s1", type = ServerType.SUBSONIC, name = "Home", baseUrl = "https://music.example",
        username = "me", credential = "secret", deviceId = "d1",
        options = mapOf("legacyAuth" to "true"), createdAt = 42L,
    )

    private val jellyfin = Server(
        id = "s2", type = ServerType.JELLYFIN, name = "Away", baseUrl = "https://jelly.example",
        username = "me2", credential = "token", userId = "u2", deviceId = "d2", createdAt = 7L,
    )

    private val continuo = Server(
        id = "s3", type = ServerType.SUBSONIC, name = "continuo", baseUrl = "https://continuo.example",
        username = "lib-user", credential = "app-password", deviceId = "d3",
        options = mapOf(
            ServerOptions.CONTINUO to "true",
            ServerOptions.CONTINUO_DEVICE_ID to "dev_abc",
            ServerOptions.CONTINUO_REFRESH_TOKEN to "crt_1",
            ServerOptions.TRUST_SELF_SIGNED to "true",
        ),
        createdAt = 9L,
    )

    /** [continuo] as a fresh sign-in needs it: same server and settings, no device session. */
    private val continuoSignedOut = continuo.copy(
        credential = "",
        options = mapOf(ServerOptions.CONTINUO to "true", ServerOptions.TRUST_SELF_SIGNED to "true"),
    )

    @Test
    fun `backup round trips settings and servers`() = runTest {
        val manager = BackupManager(
            json = json,
            settingsRepository = FakeSettingsRepository(sampleSettings),
            serverRepository = FakeServerRepository(listOf(subsonic, jellyfin)),
        )

        val restored = manager.decode(manager.export())

        assertEquals(BackupFile.CURRENT_VERSION, restored.version)
        assertEquals(sampleSettings, restored.settings)
        assertEquals(listOf(subsonic, jellyfin), restored.servers)
    }

    @Test
    fun `restore replaces settings and adds servers without stealing the active one`() = runTest {
        val existing = subsonic.copy(id = "existing", name = "Existing")
        val settings = FakeSettingsRepository(AppSettings())
        val servers = FakeServerRepository(listOf(existing))
        val manager = BackupManager(json, settings, servers)

        val backup = json.encodeToString(
            BackupFile.serializer(),
            BackupFile(settings = sampleSettings, servers = listOf(jellyfin)),
        )
        val result = manager.restore(backup)

        assertEquals(1, result.serverCount)
        assertEquals(sampleSettings, settings.current())
        // Existing server is kept, the backup's server is added.
        assertEquals(setOf("existing", "s2"), servers.ids())
        // The already-active server stays active.
        assertEquals("existing", servers.activeId())
    }

    @Test
    fun `restore into an empty install activates the first server`() = runTest {
        val servers = FakeServerRepository(emptyList())
        val manager = BackupManager(json, FakeSettingsRepository(), servers)

        manager.restore(
            json.encodeToString(
                BackupFile.serializer(),
                BackupFile(settings = AppSettings(), servers = listOf(subsonic, jellyfin)),
            ),
        )

        assertEquals("s1", servers.activeId())
    }

    @Test
    fun `export leaves out the continuo device session and keeps the server`() = runTest {
        val manager = BackupManager(
            json = json,
            settingsRepository = FakeSettingsRepository(sampleSettings),
            serverRepository = FakeServerRepository(listOf(subsonic, continuo, jellyfin)),
        )

        val text = manager.export()
        val exported = manager.decode(text)

        assertEquals(listOf(subsonic, continuoSignedOut, jellyfin), exported.servers)
        assertFalse(text.contains("app-password"))
        assertFalse(text.contains("dev_abc"))
        assertFalse(text.contains("crt_1"))
    }

    @Test
    fun `restore of an older backup drops the continuo session and asks for a sign-in`() = runTest {
        val servers = FakeServerRepository(emptyList())
        val manager = BackupManager(json, FakeSettingsRepository(), servers)

        // Backups written before the export left the session out still carry it.
        val result = manager.restore(
            json.encodeToString(
                BackupFile.serializer(),
                BackupFile(settings = AppSettings(), servers = listOf(subsonic, continuo, jellyfin)),
            ),
        )

        assertEquals(3, result.serverCount)
        assertEquals(1, result.needsSignIn)
        assertEquals(continuoSignedOut, servers.get("s3"))
        assertTrue(servers.get("s3")!!.needsContinuoSignIn)
        // Other servers are restored exactly as backed up, credentials included.
        assertEquals(subsonic, servers.get("s1"))
        assertEquals(jellyfin, servers.get("s2"))
    }

    @Test
    fun `restore over the same install keeps this phone's continuo session`() = runTest {
        // This install has since rotated its refresh token; the backup holds an older one.
        val local = continuo.copy(
            name = "Old name",
            options = continuo.options + (ServerOptions.CONTINUO_REFRESH_TOKEN to "crt_7"),
        )
        val servers = FakeServerRepository(listOf(local))
        val manager = BackupManager(json, FakeSettingsRepository(), servers)

        val result = manager.restore(
            json.encodeToString(
                BackupFile.serializer(),
                BackupFile(settings = AppSettings(), servers = listOf(continuo)),
            ),
        )

        assertEquals(0, result.needsSignIn)
        val restored = servers.get("s3")!!
        assertEquals("continuo", restored.name)
        assertEquals("app-password", restored.credential)
        assertEquals("dev_abc", restored.options[ServerOptions.CONTINUO_DEVICE_ID])
        assertEquals("crt_7", restored.options[ServerOptions.CONTINUO_REFRESH_TOKEN])
        assertFalse(restored.needsContinuoSignIn)
    }

    @Test
    fun `decode fills defaults and ignores unknown keys for forward compatibility`() {
        val manager = BackupManager(json, FakeSettingsRepository(), FakeServerRepository())

        // A file from a hypothetical newer build: a bumped version, an unknown top-level key, and no
        // settings or servers blocks at all.
        val future = """{"version":99,"unknownFutureField":{"a":1},"settings":{}}"""
        val decoded = manager.decode(future)

        assertEquals(99, decoded.version)
        assertEquals(AppSettings(), decoded.settings)
        assertTrue(decoded.servers.isEmpty())

        // An empty object falls back to every default, including the current version.
        val empty = manager.decode("{}")
        assertEquals(BackupFile.CURRENT_VERSION, empty.version)
        assertEquals(AppSettings(), empty.settings)
        assertTrue(empty.servers.isEmpty())
    }
}

private class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

private class FakeServerRepository(initial: List<Server> = emptyList()) : ServerRepository {
    private val state = MutableStateFlow(initial)
    private var activeId: String? = initial.firstOrNull()?.id

    override val servers: Flow<List<Server>> = state
    override val activeServer: Flow<Server?> =
        state.map { list -> list.firstOrNull { it.id == activeId } ?: list.firstOrNull() }

    override suspend fun getActive(): Server? =
        state.value.firstOrNull { it.id == activeId } ?: state.value.firstOrNull()

    override suspend fun get(id: String): Server? = state.value.firstOrNull { it.id == id }

    override suspend fun add(server: Server, makeActive: Boolean) {
        state.value = state.value.filterNot { it.id == server.id } + server
        if (makeActive || activeId == null) activeId = server.id
    }

    override suspend fun update(server: Server) {
        state.value = state.value.map { if (it.id == server.id) server else it }
    }

    override suspend fun remove(serverId: String) {
        state.value = state.value.filterNot { it.id == serverId }
        if (activeId == serverId) activeId = state.value.firstOrNull()?.id
    }

    override suspend fun setActive(serverId: String) {
        activeId = serverId
    }

    fun ids(): Set<String> = state.value.map { it.id }.toSet()
    fun activeId(): String? = activeId
}
