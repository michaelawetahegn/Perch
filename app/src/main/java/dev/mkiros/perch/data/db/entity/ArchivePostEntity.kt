package dev.mkiros.perch.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * One post a source's archive is known to hold — the remembered plan (PLAN-12 §0.4, #68).
 *
 * `ArchiveDiscovery` finds a site's whole history at once and used to store none of it, so
 * every backfill re-read up to fifty sitemaps and the cursor into the archive was the
 * `entries` table itself — which retention prunes, so a later batch re-downloaded posts the
 * reader had already read. A row here is the memory: discovered once, stamped [fetchedAt]
 * when a run has dealt with it, never deleted by discovery (a sitemap that shrinks does not
 * forget history). What is left unstamped is what the next batch fetches.
 *
 * Keyed on the URL as the sitemap spelt it — this table remembers what the site *listed*,
 * `entries` holds what was actually stored, and the two are only ever matched through
 * [dev.mkiros.perch.data.parse.urlKey]. Cascades with the source; not carried by a profile.
 */
@Entity(
    tableName = "archive_posts",
    primaryKeys = ["feedId", "url"],
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArchivePostEntity(
    val feedId: Long,
    val url: String,
    /** The sitemap's `<lastmod>` (epoch millis), when it gave one — the batch order. */
    val lastmod: Long?,
    val discoveredAt: Long,
    /** When a run fetched it, found it already stored, or gave up on it for good; null until then. */
    val fetchedAt: Long?,
)
