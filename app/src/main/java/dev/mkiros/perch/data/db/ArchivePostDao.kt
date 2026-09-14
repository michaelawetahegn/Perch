package dev.mkiros.perch.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.mkiros.perch.data.db.entity.ArchivePostEntity

/** The remembered archive plan, per source (PLAN-12 §0.4, #68). */
@Dao
abstract class ArchivePostDao {

    /** A discovery adds what it found and touches nothing already remembered. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun upsertIgnore(posts: List<ArchivePostEntity>)

    /**
     * The next batch: newest first by the sitemap's date, undated rows last in the order
     * they were discovered — the order `BackfillRepository.plan` has always fetched in.
     */
    @Query(
        """
        SELECT * FROM archive_posts WHERE feedId = :feedId AND fetchedAt IS NULL
        ORDER BY lastmod IS NULL, lastmod DESC, rowid
        LIMIT :limit
        """,
    )
    abstract suspend fun unfetched(feedId: Long, limit: Int): List<ArchivePostEntity>

    @Query("SELECT COUNT(*) FROM archive_posts WHERE feedId = :feedId AND fetchedAt IS NULL")
    abstract suspend fun countUnfetched(feedId: Long): Int

    /** When this source's archive was last discovered; null when it never has been. */
    @Query("SELECT MAX(discoveredAt) FROM archive_posts WHERE feedId = :feedId")
    abstract suspend fun newestDiscoveredAt(feedId: Long): Long?

    /** Stamps [urls] as dealt with, in slices that stay under SQLite's bound-variable limit. */
    @Transaction
    open suspend fun markFetched(feedId: Long, urls: Collection<String>, fetchedAt: Long) {
        urls.chunked(MARK_CHUNK).forEach { markFetchedChunk(feedId, it, fetchedAt) }
    }

    @Query("UPDATE archive_posts SET fetchedAt = :fetchedAt WHERE feedId = :feedId AND url IN (:urls)")
    protected abstract suspend fun markFetchedChunk(feedId: Long, urls: List<String>, fetchedAt: Long)

    private companion object {
        const val MARK_CHUNK = 500
    }
}
