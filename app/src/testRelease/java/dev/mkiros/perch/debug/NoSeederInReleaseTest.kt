package dev.mkiros.perch.debug

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The seeder is a screenshot fixture, not a feature: a release install must start empty,
 * on the reader's own sources, and must not carry three quarters of a megabyte of feed
 * XML it will never read.
 *
 * This test only exists in the *release* unit-test variant, so it is asserting about the
 * build it is compiled into. It is deliberately reflective — naming `DebugSeeder` as a
 * type here would not compile, which is itself the guarantee, but a source set that
 * cannot be compiled is not a check anyone can run.
 */
@RunWith(RobolectricTestRunner::class)
class NoSeederInReleaseTest {

    @Test
    fun `the seeder is not compiled into release`() {
        for (name in SEEDER_CLASSES) {
            assertWithMessage("$name is on the release classpath")
                .that(runCatching { Class.forName(name) }.isSuccess)
                .isFalse()
        }
    }

    @Test
    fun `the seed snapshots are not packaged into release`() {
        val assets = ApplicationProvider.getApplicationContext<android.app.Application>().assets
        val seed = runCatching { assets.list("seed") }.getOrNull().orEmpty()

        assertThat(seed.toList()).isEmpty()
    }

    private companion object {
        val SEEDER_CLASSES = listOf(
            "dev.mkiros.perch.debug.DebugSeeder",
            "dev.mkiros.perch.debug.DebugSeedProvider",
        )
    }
}
