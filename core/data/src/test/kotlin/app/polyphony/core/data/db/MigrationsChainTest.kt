package app.polyphony.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library database also stores the configured servers and destructive fallback is off for
 * upgrades, so a version bump without a matching migration would crash every upgraded install.
 * This runs on every build and fails as soon as the chain 1 → [PolyphonyDatabase.VERSION] has a gap.
 */
class MigrationsChainTest {

    @Test
    fun `migrations form an unbroken chain up to the current version`() {
        val steps = Migrations.ALL.map { it.startVersion to it.endVersion }.sortedBy { it.first }
        val expected = (1 until PolyphonyDatabase.VERSION).map { it to it + 1 }
        assertEquals("Add a Migration for every version bump (and export its schema JSON)", expected, steps)
    }
}
