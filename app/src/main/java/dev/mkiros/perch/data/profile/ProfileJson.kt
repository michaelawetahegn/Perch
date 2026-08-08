package dev.mkiros.perch.data.profile

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The profile file: `perch-profile-YYYYMMDD.json` (U14).
 *
 * JSON via `org.json`, which is in the platform — a profile is a private interchange format
 * between one install of Perch and the next, so it owes nobody a schema and does not
 * justify a serialization dependency (CLAUDE.md, SPEC.md §2).
 *
 * Two rules govern reading. **A [SCHEMA_VERSION] this version does not know is refused
 * whole**, because a half-applied restore is the one failure a reader cannot detect. And
 * everything else is read forgivingly: an absent optional field is absent, an unknown key
 * is ignored, and an entry that names no source is dropped rather than restored as a row
 * pointing at nothing. Those are opposite instincts on purpose — the version field is the
 * one place where being strict protects the reader.
 */
object ProfileJson {

    /**
     * Bump only when an *older* Perch could not safely read the file. Adding a field a
     * previous version will ignore is not that; changing what an existing field means is.
     */
    const val SCHEMA_VERSION = 1

    private const val SCHEMA = "schemaVersion"
    private const val APP = "app"
    private const val EXPORTED_AT = "exportedAt"
    private const val FOLDERS = "folders"
    private const val SOURCES = "sources"
    private const val ENTRY_STATE = "entryState"

    /** The name to pre-fill the SAF create-document dialog with. */
    fun fileName(date: LocalDate): String =
        "perch-profile-${date.format(DateTimeFormatter.BASIC_ISO_DATE)}.json"

    fun write(profile: Profile): String = JSONObject().apply {
        put(SCHEMA, SCHEMA_VERSION)
        put(APP, "Perch")
        putOrNull(EXPORTED_AT, profile.exportedAt?.toString())
        put(
            FOLDERS,
            JSONArray(
                profile.folders.map {
                    JSONObject().apply {
                        put("name", it.name)
                        put("sortIndex", it.sortIndex)
                    }
                },
            ),
        )
        put(
            SOURCES,
            JSONArray(
                profile.sources.map {
                    JSONObject().apply {
                        put("feedUrl", it.feedUrl)
                        put("title", it.title)
                        putOrNull("siteUrl", it.siteUrl)
                        putOrNull("customTitle", it.customTitle)
                        putOrNull("folder", it.folder)
                    }
                },
            ),
        )
        put(
            ENTRY_STATE,
            JSONArray(
                profile.entryState.map {
                    JSONObject().apply {
                        put("feedUrl", it.feedUrl)
                        put("guid", it.guid)
                        put("read", it.isRead)
                        putOrNull("readAt", it.readAt)
                        put("saved", it.isSaved)
                        putOrNull("savedAt", it.savedAt)
                        put("liked", it.isLiked)
                        putOrNull("likedAt", it.likedAt)
                    }
                },
            ),
        )
    }.toString(INDENT)

    fun read(text: String): ProfileParse {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return ProfileParse.Malformed(e.message ?: "it is not JSON")
        }
        // The discriminator. Without it this is some other JSON document, and reading it as
        // an empty profile would report a successful restore of nothing at all.
        if (!root.has(SCHEMA)) {
            return ProfileParse.Malformed("it carries no $SCHEMA")
        }
        val version = root.optInt(SCHEMA, 0)
        if (version < 1) return ProfileParse.Malformed("its $SCHEMA is not a version")
        if (version > SCHEMA_VERSION) return ProfileParse.Unsupported(version)

        return ProfileParse.Success(
            Profile(
                folders = root.objects(FOLDERS).mapNotNull { it.folder() },
                sources = root.objects(SOURCES).mapNotNull { it.source() },
                entryState = root.objects(ENTRY_STATE).mapNotNull { it.entryState() },
                exportedAt = root.stringOrNull(EXPORTED_AT)?.let {
                    runCatching { Instant.parse(it) }.getOrNull()
                },
            ),
        )
    }

    private fun JSONObject.folder(): ProfileFolder? {
        val name = stringOrNull("name") ?: return null
        return ProfileFolder(name = name, sortIndex = optInt("sortIndex", 0))
    }

    private fun JSONObject.source(): ProfileSource? {
        val feedUrl = stringOrNull("feedUrl") ?: return null
        return ProfileSource(
            feedUrl = feedUrl,
            title = stringOrNull("title") ?: feedUrl,
            siteUrl = stringOrNull("siteUrl"),
            customTitle = stringOrNull("customTitle"),
            folder = stringOrNull("folder"),
        )
    }

    private fun JSONObject.entryState(): ProfileEntryState? {
        val feedUrl = stringOrNull("feedUrl") ?: return null
        val guid = stringOrNull("guid") ?: return null
        return ProfileEntryState(
            feedUrl = feedUrl,
            guid = guid,
            isRead = optBoolean("read", false),
            readAt = longOrNull("readAt"),
            isSaved = optBoolean("saved", false),
            savedAt = longOrNull("savedAt"),
            isLiked = optBoolean("liked", false),
            likedAt = longOrNull("likedAt"),
        )
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    /** `JSONObject.put` deletes the key when handed a Kotlin null, so say `null` explicitly. */
    private fun JSONObject.putOrNull(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.longOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    /** Two spaces: the file is small, and a reader who opens it should be able to read it. */
    private const val INDENT = 2
}
