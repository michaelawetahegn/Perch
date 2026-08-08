package dev.mkiros.perch.data.profile

import java.time.Instant

/**
 * A folder as the file carries it (U14). Ids do not survive leaving the app, so a folder is
 * its name — the same rule OPML forced on U13, adopted here deliberately rather than
 * reluctantly: it is what lets a profile and an OPML file agree about the same folder.
 */
data class ProfileFolder(val name: String, val sortIndex: Int)

/**
 * A subscription as the file carries it.
 *
 * [title] is the feed's own name and [customTitle] the reader's rename, kept apart for the
 * same reason `FeedEntity` keeps them apart: the next refresh overwrites one and must never
 * touch the other. Carrying [title] costs a line and means a restored library reads as
 * itself immediately, instead of showing forty blank rows until the first refresh returns.
 *
 * @param folder the folder's *name*, or null for Uncategorized — which is Perch's word for
 *   "no folder", not a folder anyone chose, and so is never written as one.
 */
data class ProfileSource(
    val feedUrl: String,
    val title: String,
    val siteUrl: String?,
    val customTitle: String?,
    val folder: String?,
)

/**
 * What the reader did to one article, addressed by `(feedUrl, guid)` — the only name for an
 * article that means the same thing on two different installs.
 *
 * *Liked* rather than *starred*: the column has been `isStarred` since T12, but the file is
 * read by people and the app has called it Liked since U04 (PLAN-2 §0).
 */
data class ProfileEntryState(
    val feedUrl: String,
    val guid: String,
    val isRead: Boolean,
    val readAt: Long?,
    val isSaved: Boolean,
    val savedAt: Long?,
    val isLiked: Boolean,
    val likedAt: Long?,
)

/**
 * A whole reading identity in one value (U14): the folders, the sources, and the state that
 * makes a library *yours* rather than merely a list of addresses.
 *
 * Not entry bodies. This is state, not an archive — the articles are on the web and the
 * next refresh will fetch them, whereas nothing anywhere can reconstruct which of them you
 * had read. A profile is therefore kilobytes, and stays that way as the library grows.
 */
data class Profile(
    val folders: List<ProfileFolder> = emptyList(),
    val sources: List<ProfileSource> = emptyList(),
    val entryState: List<ProfileEntryState> = emptyList(),
    /** When the file was written. Informational; nothing restores from it. */
    val exportedAt: Instant? = null,
)

/** What a file offered as a profile turned out to be. */
sealed interface ProfileParse {

    data class Success(val profile: Profile) : ProfileParse

    /** Not a Perch profile at all; [message] is already phrased for the user. */
    data class Malformed(val message: String) : ProfileParse

    /**
     * A profile written by a later version of Perch.
     *
     * Refused whole rather than read for the fields this version recognises. A half-applied
     * restore is worse than none: the reader is told it worked, and has no way to find out
     * which half of their reading identity is missing.
     */
    data class Unsupported(val schemaVersion: Int) : ProfileParse
}
