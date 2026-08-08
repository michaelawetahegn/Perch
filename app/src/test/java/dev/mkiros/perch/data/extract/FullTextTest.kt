package dev.mkiros.perch.data.extract

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When Perch decides a feed body is not the article (U10).
 *
 * A length threshold on its own is not enough, and the two §0 shapes are why. A body of
 * 144 characters is obviously not an article; a body of 2,000 that ends in *Continue
 * reading* obviously is not either, and no threshold catches it. So the trigger is four
 * independent signals, any one of which is sufficient, and the *guard* against a wrong
 * answer lives elsewhere: an extraction only ever replaces a body it is longer than.
 */
class FullTextTest {

    private val realArticle = "<p>${"Sentence about the subject, with commas. ".repeat(60)}</p>"

    @Test
    fun `an entry with no body at all needs extraction`() {
        assertThat(FullText.needsExtraction(null, bodyIsExcerpt = false)).isTrue()
        assertThat(FullText.needsExtraction("", bodyIsExcerpt = false)).isTrue()
        assertThat(FullText.needsExtraction("<p></p>", bodyIsExcerpt = false)).isTrue()
    }

    @Test
    fun `a body shorter than the prose floor needs extraction`() {
        val excerpt = "<p>Learn how fast, crack-free GPU work graph subdivision works.</p>"

        assertThat(FullText.needsExtraction(excerpt, bodyIsExcerpt = false)).isTrue()
    }

    @Test
    fun `a full article does not need extraction`() {
        assertThat(FullText.needsExtraction(realArticle, bodyIsExcerpt = false)).isFalse()
    }

    /**
     * The shape a threshold cannot see: long enough to look like an article, ending in the
     * marker that says it is not.
     */
    @Test
    fun `a long body ending in a truncation marker needs extraction`() {
        val markers = listOf(
            "…",
            "[…]",
            "Read more",
            "Read more &rarr;",
            "Continue reading",
            "Continue reading &#8594;",
            "The post Something Happened appeared first on Example Blog.",
        )

        val missed = markers.filterNot { marker ->
            FullText.needsExtraction(realArticle + "<p>$marker</p>", bodyIsExcerpt = false)
        }

        assertThat(missed).isEmpty()
    }

    /**
     * §0's gpuopen shape. An RSS 2.0 item carrying a `<description>` and no
     * `<content:encoded>` means "excerpt" far more often than it means "short post", and
     * that is a fact about the *feed*, invisible in the body alone.
     */
    @Test
    fun `a body the feed only ever offered as a description needs extraction`() {
        assertThat(FullText.needsExtraction(realArticle, bodyIsExcerpt = true)).isTrue()
    }

    /** Code is not prose: a listing padding a stub out past the floor must not count. */
    @Test
    fun `a stub padded out by a code listing still needs extraction`() {
        val body = "<p>Here is the patch.</p><pre><code>${"int x = 0;\n".repeat(200)}</code></pre>"

        assertThat(FullText.needsExtraction(body, bodyIsExcerpt = false)).isTrue()
    }

    @Test
    fun `malformed markup is treated as no body rather than throwing`() {
        assertThat(FullText.needsExtraction("<<<>&", bodyIsExcerpt = false)).isTrue()
    }
}
