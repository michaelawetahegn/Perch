package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * F01 (#69): [urlKey] is the *matching* form of a URL — what the backfill compares a sitemap
 * candidate against the stored guids and links with. One case per normalisation in PLAN-12
 * §0.2 part 2. It never rewrites a stored value.
 */
class UrlKeyTest {

    @Test
    fun `scheme and host are lowercased, the path is not`() {
        assertThat(urlKey("HTTPS://Example.COM/Posts/One")).isEqualTo("https://example.com/Posts/One")
    }

    @Test
    fun `a leading www is dropped`() {
        assertThat(urlKey("https://www.example.com/post")).isEqualTo("https://example.com/post")
    }

    @Test
    fun `the fragment is dropped`() {
        assertThat(urlKey("https://example.com/post#comments")).isEqualTo("https://example.com/post")
    }

    @Test
    fun `tracking query parameters are dropped and the question mark goes with the last of them`() {
        assertThat(urlKey("https://example.com/post?utm_source=rss&utm_medium=feed&fbclid=abc&gclid=1"))
            .isEqualTo("https://example.com/post")
    }

    @Test
    fun `a query that still says something is kept in order`() {
        assertThat(urlKey("https://example.com/?p=123&utm_source=rss")).isEqualTo("https://example.com/?p=123")
    }

    @Test
    fun `one trailing slash is stripped`() {
        assertThat(urlKey("https://example.com/post/")).isEqualTo("https://example.com/post")
    }

    @Test
    fun `a default port is dropped, another is kept`() {
        assertThat(urlKey("https://example.com:443/post")).isEqualTo("https://example.com/post")
        assertThat(urlKey("http://example.com:80/post")).isEqualTo("http://example.com/post")
        assertThat(urlKey("http://example.com:8080/post")).isEqualTo("http://example.com:8080/post")
    }

    @Test
    fun `a bare origin keys to itself with no trailing slash`() {
        assertThat(urlKey("https://example.com/")).isEqualTo("https://example.com")
    }

    @Test
    fun `something that is not a URL keys to itself`() {
        assertThat(urlKey("not a url")).isEqualTo("not a url")
        assertThat(urlKey("tag:example.com,2020:post-1")).isEqualTo("tag:example.com,2020:post-1")
    }
}
