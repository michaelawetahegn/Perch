package dev.mkiros.perch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The skeleton's only job: prove the JVM unit-test source set compiles and runs
 * with the pinned test libraries. Real behaviour tests start at T05.
 */
class SkeletonSmokeTest {

    @Test
    fun `unit test source set runs with truth available`() {
        assertThat("Perch".lowercase()).isEqualTo("perch")
    }
}
