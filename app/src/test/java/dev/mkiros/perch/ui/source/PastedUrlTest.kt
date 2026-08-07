package dev.mkiros.perch.ui.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What a reader is allowed to paste into the add-source field.
 *
 * The repository deliberately does not normalise: it treats what it is given as an
 * address and reports `example.com` as unreachable. The sheet is where a human's paste
 * becomes a URL, so that is here.
 */
class PastedUrlTest {

    @Test
    fun `a bare host is followed over https`() {
        assertThat(normalizePastedUrl("example.com")).isEqualTo("https://example.com")
    }

    @Test
    fun `a bare host with a path is followed over https`() {
        assertThat(normalizePastedUrl("example.com/blog/feed.xml"))
            .isEqualTo("https://example.com/blog/feed.xml")
    }

    @Test
    fun `surrounding whitespace from a paste is dropped`() {
        assertThat(normalizePastedUrl("  https://example.com/feed.xml \n"))
            .isEqualTo("https://example.com/feed.xml")
    }

    @Test
    fun `an address that already names its scheme is left alone`() {
        assertThat(normalizePastedUrl("http://example.com/feed.xml"))
            .isEqualTo("http://example.com/feed.xml")
        assertThat(normalizePastedUrl("HTTPS://Example.com/feed.xml"))
            .isEqualTo("HTTPS://Example.com/feed.xml")
    }

    @Test
    fun `a feed scheme link from a reader app becomes https`() {
        assertThat(normalizePastedUrl("feed://example.com/feed.xml"))
            .isEqualTo("https://example.com/feed.xml")
        assertThat(normalizePastedUrl("feed:https://example.com/feed.xml"))
            .isEqualTo("https://example.com/feed.xml")
    }

    @Test
    fun `nothing pasted stays nothing rather than becoming a scheme`() {
        assertThat(normalizePastedUrl("   ")).isEmpty()
    }
}
