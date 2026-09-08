package dev.mkiros.perch.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey
import dev.mkiros.perch.data.parse.HtmlSanitizer

/**
 * The search index (S08, #28) — one row per article, keyed to [EntryEntity.id].
 *
 * **Standalone, not `@Fts4(contentEntity = EntryEntity::class)`.** An external-content
 * table keeps no copy of the text and leans on triggers to stay in step with its source;
 * Room does not reliably generate those, and the failure mode is an index that is silently
 * stale, which reads to the reader as an article that has simply vanished. So Perch owns
 * the writes: [dev.mkiros.perch.data.db.EntryDao.upsertAll] and
 * `ArticleTextRepository.loadFullText` put rows in, and a SQL trigger on `entries` takes
 * them out — the delete has to be SQL because a source removal reaches its articles by
 * `ON DELETE CASCADE` and never passes through Kotlin at all.
 *
 * It is also not an extra column on `entries`: this table *is* the plain-text store, so the
 * body is held once, not twice.
 *
 * @param body plain text, never markup. SQLite's tokenizer is happy to index tag names,
 *   class names and URLs, so an index built from HTML answers `class`, `img` or `https`
 *   with the whole database.
 */
@Fts4
@Entity(tableName = "entries_fts")
data class EntryFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    val title: String,
    val body: String,
)

/**
 * This article as it is indexed.
 *
 * The body falls back to [EntryEntity.summary] because `contentHtml` is patchy by design —
 * it is null until a feed ships one and is only replaced when Perch fetches the page — and
 * a never-opened article should still be findable by the excerpt the reader has actually
 * seen. It does not invent one: an entry with neither indexes an empty body.
 */
fun EntryEntity.toFtsRow(): EntryFtsEntity = EntryFtsEntity(
    rowid = id,
    title = title,
    body = HtmlSanitizer.flatten(contentHtml) ?: summary.orEmpty(),
)
