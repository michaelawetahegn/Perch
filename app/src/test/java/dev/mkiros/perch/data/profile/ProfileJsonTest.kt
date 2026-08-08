package dev.mkiros.perch.data.profile

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The profile document (U14): the file half of backup and restore.
 *
 * Two properties are worth pinning here and nowhere else. A profile must survive the round
 * trip through text **exactly** — every folder, every source, every flag and every one of
 * their timestamps — because the file is the only thing a new phone will ever see. And a
 * file from a *later* version of Perch must be refused whole rather than read for the
 * fields this version happens to recognise: a half-applied restore is worse than no
 * restore, since the reader has no way to tell it happened.
 *
 * Robolectric because `org.json` is an Android class; on a bare JVM every method is a stub.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileJsonTest {

    private val profile = Profile(
        exportedAt = Instant.parse("2026-08-08T09:30:00Z"),
        folders = listOf(
            ProfileFolder(name = "Graphics", sortIndex = 1),
            ProfileFolder(name = "Security", sortIndex = 2),
        ),
        sources = listOf(
            ProfileSource(
                feedUrl = "https://fabiensanglard.net/rss.xml",
                title = "Fabien Sanglard",
                siteUrl = "https://fabiensanglard.net",
                customTitle = "Fabien",
                folder = "Graphics",
            ),
            ProfileSource(
                feedUrl = "https://example.com/feed.xml",
                title = "Example",
                siteUrl = null,
                customTitle = null,
                folder = null,
            ),
        ),
        entryState = listOf(
            ProfileEntryState(
                feedUrl = "https://fabiensanglard.net/rss.xml",
                guid = "guid-a",
                isRead = true,
                readAt = 600L,
                isSaved = true,
                savedAt = 7_007L,
                isLiked = true,
                likedAt = 8_008L,
            ),
            ProfileEntryState(
                feedUrl = "https://example.com/feed.xml",
                guid = "guid-b",
                isRead = false,
                readAt = null,
                isSaved = false,
                savedAt = null,
                isLiked = true,
                likedAt = 9_009L,
            ),
        ),
    )

    @Test
    fun `a profile survives the round trip through text unchanged`() {
        val parsed = ProfileJson.read(ProfileJson.write(profile))

        assertThat(parsed).isInstanceOf(ProfileParse.Success::class.java)
        assertThat((parsed as ProfileParse.Success).profile).isEqualTo(profile)
    }

    @Test
    fun `the document declares the schema version it was written at`() {
        val text = ProfileJson.write(profile)

        assertThat(text).contains("\"schemaVersion\"")
        assertThat(text).contains(ProfileJson.SCHEMA_VERSION.toString())
    }

    @Test
    fun `a profile from a later version of Perch is refused whole`() {
        val future = ProfileJson.write(profile)
            .replace(
                "\"schemaVersion\": ${ProfileJson.SCHEMA_VERSION}",
                "\"schemaVersion\": ${ProfileJson.SCHEMA_VERSION + 1}",
            )

        val parsed = ProfileJson.read(future)

        assertThat(parsed).isEqualTo(ProfileParse.Unsupported(ProfileJson.SCHEMA_VERSION + 1))
    }

    @Test
    fun `a file that is not JSON at all is rejected with a reason`() {
        val parsed = ProfileJson.read("<opml version=\"2.0\"><body/></opml>")

        assertThat(parsed).isInstanceOf(ProfileParse.Malformed::class.java)
        assertThat((parsed as ProfileParse.Malformed).message).isNotEmpty()
    }

    @Test
    fun `JSON that is not a Perch profile is rejected rather than read as an empty one`() {
        val parsed = ProfileJson.read("""{"name":"something else","items":[]}""")

        assertThat(parsed).isInstanceOf(ProfileParse.Malformed::class.java)
    }

    /**
     * Forward compatibility in the other direction: a source whose optional fields are
     * simply absent is still a source, and a key this version has never heard of is not a
     * reason to refuse a file written at a version it does understand.
     */
    @Test
    fun `missing optional fields and unknown keys are tolerated`() {
        val minimal = """
            {
              "schemaVersion": ${ProfileJson.SCHEMA_VERSION},
              "somethingNew": {"a": 1},
              "sources": [{"feedUrl": "https://example.com/feed.xml", "title": "Example"}]
            }
        """.trimIndent()

        val parsed = ProfileJson.read(minimal) as ProfileParse.Success

        assertThat(parsed.profile.sources).containsExactly(
            ProfileSource(
                feedUrl = "https://example.com/feed.xml",
                title = "Example",
                siteUrl = null,
                customTitle = null,
                folder = null,
            ),
        )
        assertThat(parsed.profile.folders).isEmpty()
        assertThat(parsed.profile.entryState).isEmpty()
    }

    /** A source with no address is not a source; it cannot be restored to anything. */
    @Test
    fun `an addressless source is dropped rather than restored as a blank row`() {
        val text = """
            {
              "schemaVersion": ${ProfileJson.SCHEMA_VERSION},
              "sources": [{"title": "No address"},
                          {"feedUrl": "https://example.com/feed.xml", "title": "Example"}]
            }
        """.trimIndent()

        val parsed = ProfileJson.read(text) as ProfileParse.Success

        assertThat(parsed.profile.sources.map { it.feedUrl })
            .containsExactly("https://example.com/feed.xml")
    }

    @Test
    fun `the suggested file name carries the date it was exported on`() {
        assertThat(ProfileJson.fileName(LocalDate.of(2026, 8, 8)))
            .isEqualTo("perch-profile-20260808.json")
    }
}
