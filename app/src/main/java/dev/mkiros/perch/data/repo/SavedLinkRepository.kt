package dev.mkiros.perch.data.repo

import android.graphics.Bitmap
import android.net.Uri
import dev.mkiros.perch.data.db.EntryDao
import dev.mkiros.perch.data.db.FeedDao
import dev.mkiros.perch.data.db.entity.FeedEntity
import dev.mkiros.perch.data.document.DocumentStore
import dev.mkiros.perch.data.document.PageRasterizer
import dev.mkiros.perch.data.document.PageSource
import dev.mkiros.perch.data.document.PdfInfoReader
import dev.mkiros.perch.data.extract.PageContentExtractor
import dev.mkiros.perch.data.extract.toEntry
import dev.mkiros.perch.data.net.FeedFetcher
import dev.mkiros.perch.data.net.FetchResult
import dev.mkiros.perch.data.parse.FeedParser
import dev.mkiros.perch.data.parse.ParseResult
import dev.mkiros.perch.data.parse.normalizePastedUrl
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Clock

/**
 * Why a pasted link did not become a saved entry (PLAN-6 §0.4) — every reason
 * [SavedLinkRepository.saveLink] can fail is a value in [Result.failure], copying
 * [SourceResolution]'s shape rather than an exception the caller has to catch.
 */
sealed class SaveLinkFailure(message: String) : Exception(message) {

    /** Reachable, and it parses as a feed — the other feature, not an error (§0.4). */
    class IsFeed(val url: String) : SaveLinkFailure("That address is a feed, not an article.")

    /** Could not be fetched, or was too large or not an article; already phrased for the user. */
    class Unreachable(message: String) : SaveLinkFailure(message)

    /** A shared or local file that is not a PDF (PLAN-13 §0.8). */
    class NotDocument : SaveLinkFailure("That file is not a PDF Perch can read")
}

/**
 * A link the reader pastes, saved without ever subscribing to its site (PLAN-6 §0.3/§0.4,
 * issue #23).
 *
 * Everything [ArticleTextRepository] does for an entry it already has, done once for a page
 * it does not: fetch, extract title/date/body/image with [PageContentExtractor] — the same
 * value object [ArticleTextRepository] reads, so a page is read the same way whether Perch
 * already knew about it or a reader just pasted it — then file the result on the synthetic
 * `perch:saved-links` feed (§0.3), which satisfies the same foreign key and the same joins
 * every other entry does.
 *
 * Identity is `(feedId, guid)`, same as a fed entry, with the **final URL as the guid**
 * (§0.3) — pasting the same link twice lands on the same row through [EntryDao.upsertAll]
 * rather than duplicating it.
 */
class SavedLinkRepository(
    private val feedDao: FeedDao,
    private val entryDao: EntryDao,
    private val fetcher: FeedFetcher,
    private val clock: Clock,
    private val documents: DocumentStore,
    private val rasterizer: PageRasterizer,
    private val parser: FeedParser = FeedParser(),
    private val documentOpener: DocumentOpener? = null,
) {

    suspend fun saveLink(url: String): Result<Long> {
        val normalized = normalizePastedUrl(url)
        val file = documents.newDocument()

        val downloaded = when (val result = fetcher.download(normalized, file)) {
            is dev.mkiros.perch.data.net.DownloadResult.Success -> result
            is dev.mkiros.perch.data.net.DownloadResult.Failure -> {
                file.delete()
                return Result.failure(SaveLinkFailure.Unreachable(result.message))
            }
        }

        // Check if it's a PDF by sniffing the first 1 KiB
        val sniff = file.inputStream().use { stream ->
            ByteArray(minOf(1024, file.length().toInt())).apply { stream.read(this) }
        }
        val isPdf = sniff.decodeToString(throwOnInvalidSequence = false).contains("%PDF-")
            || downloaded.contentType?.startsWith("application/pdf") == true

        if (isPdf) {
            return storeDocument(
                file = file,
                link = downloaded.finalUrl,
                guid = downloaded.finalUrl,
                nameHint = extractFilename(downloaded.contentDisposition)
            )
        }

        // Not a PDF, process as HTML page using the downloaded bytes
        val bytes = file.readBytes()
        file.delete()

        // Non-PDF pages still have the 8 MiB cap (SPEC.md §6)
        if (bytes.size > 8 * 1024 * 1024) {
            return Result.failure(SaveLinkFailure.Unreachable("Feed is too large (over 8 MiB)"))
        }

        // A pasted feed address is not an error — it is the other feature (§0.4). Checked
        // against the bytes we already have, not through discovery: a blog *post* routinely
        // declares its site's feed via autodiscovery, and that must not make every post look
        // like a feed.
        if (parser.parse(bytes, downloaded.contentType, downloaded.finalUrl) is ParseResult.Success) {
            return Result.failure(SaveLinkFailure.IsFeed(downloaded.finalUrl))
        }

        val document = PageContentExtractor.parse(bytes, downloaded.finalUrl)
            ?: return Result.failure(SaveLinkFailure.Unreachable("$normalized is not a readable page."))

        val savedFeedId = feedDao.findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)?.id
            ?: error("The saved-links feed is missing; every database is seeded with it (Y02).")

        val content = PageContentExtractor.extract(document, downloaded.finalUrl)
        val now = clock.millis()
        // A page that declares no date at all is dated *now*, and says so: a link saved
        // today belongs at the top of To-Read, not at the bottom under EPOCH.
        val entity = content.toEntry(
            feedId = savedFeedId,
            finalUrl = downloaded.finalUrl,
            publishedAt = content.metadata.publishedAt?.toEpochMilli() ?: now,
            publishedIsEstimated = content.metadata.publishedAt == null,
            fetchedAt = now,
            isSaved = true,
            savedAt = now,
        )

        // Idempotent on (feedId, guid), same as a refetched feed entry — pasting the same
        // link twice lands on the row this already wrote rather than duplicating it.
        entryDao.upsertAll(listOf(entity))
        val saved = entryDao.findByGuid(savedFeedId, downloaded.finalUrl)
            ?: error("upsertAll just wrote this row.")
        // D04: `upsertAll` carries the existing row's reader flags forward (U04), because a
        // *feed* must never overwrite what the reader did. A pasted link is the one caller
        // where the incoming flag is the reader's intent: re-pasting a link they had taken
        // off To-Read means putting it back. Set it here, on the row that came out.
        if (!saved.isSaved) {
            entryDao.setSaved(saved.id, isSaved = true, savedAt = now)
        }
        return Result.success(saved.id)
    }

    suspend fun saveDocument(uri: Uri, displayName: String?): Result<Long> {
        if (documentOpener == null) {
            return Result.failure(SaveLinkFailure.Unreachable("Document opener not available"))
        }

        val file = documents.newDocument()
        val stream = documentOpener.open(uri)
        if (stream == null) {
            file.delete()
            return Result.failure(SaveLinkFailure.Unreachable("Cannot open file"))
        }

        // Copy the stream to the file, hashing it, and stop at the cap rather than after it:
        // a share is whatever the sending app hands over (§0.8).
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var copied = 0L
        stream.use { input ->
            file.outputStream().use { output ->
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    copied += bytesRead
                    if (copied > FeedFetcher.MAX_DOCUMENT_BYTES) break
                    output.write(buffer, 0, bytesRead)
                    digest.update(buffer, 0, bytesRead)
                }
            }
        }
        if (copied > FeedFetcher.MAX_DOCUMENT_BYTES) {
            file.delete()
            return Result.failure(SaveLinkFailure.Unreachable("File is too large (over 40 MiB)"))
        }

        // Check if it's a PDF by sniffing the first 1 KiB
        val sniff = file.inputStream().use { input ->
            ByteArray(minOf(1024L, copied).toInt()).also { input.read(it) }
        }.decodeToString(throwOnInvalidSequence = false)
        if (!sniff.contains("%PDF-")) {
            file.delete()
            return Result.failure(SaveLinkFailure.NotDocument())
        }

        // Hash in hex format, for guid
        val sha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        val guid = "perch:document:$sha256Hex"

        return storeDocument(
            file = file,
            link = null,
            guid = guid,
            nameHint = displayName?.substringBeforeLast(".")?.replace("_", " "),
            isSaved = true
        )
    }

    private suspend fun storeDocument(
        file: File,
        link: String?,
        guid: String,
        nameHint: String?,
        isSaved: Boolean = true
    ): Result<Long> {
        // Verify the file is a readable PDF
        val source = rasterizer.open(file)
        if (source == null || source.pageCount == 0) {
            file.delete()
            return Result.failure(SaveLinkFailure.Unreachable("$guid is not a readable page."))
        }
        val thumbnail = documents.thumbnailFor(file)
        source.use { writeThumbnail(it, thumbnail) }

        val savedFeedId = feedDao.findByUrl(FeedEntity.SAVED_LINKS_FEED_URL)?.id
            ?: error("The saved-links feed is missing; every database is seeded with it (Y02).")

        val pdfInfo = PdfInfoReader.read(file)
        val title = pdfInfo.title ?: nameHint ?: "Document"
        val publishedAt = pdfInfo.creationDate?.toEpochMilli() ?: clock.millis()
        val publishedIsEstimated = pdfInfo.creationDate == null

        val now = clock.millis()
        val entity = dev.mkiros.perch.data.db.entity.EntryEntity(
            feedId = savedFeedId,
            guid = guid,
            link = link,
            title = title,
            author = null,
            summary = null,
            contentHtml = null,
            imageUrl = thumbnail.toURI().toString(),
            publishedAt = publishedAt,
            publishedIsEstimated = publishedIsEstimated,
            readAt = null,
            savedAt = if (isSaved) now else null,
            starredAt = null,
            fetchedAt = now,
            bodyIsExcerpt = false,
            fullTextAt = null,
            scrollPosition = 0,
            documentPath = documents.relativize(file),
        )

        entryDao.upsertAll(listOf(entity))
        val saved = entryDao.findByGuid(savedFeedId, guid)
            ?: error("upsertAll just wrote this row.")

        if (!saved.isSaved && isSaved) {
            entryDao.setSaved(saved.id, isSaved = true, savedAt = now)
        }
        return Result.success(saved.id)
    }

    /** §0.4 step 4: page one at [THUMBNAIL_PX] wide, cropped to its top square, as PNG. */
    private fun writeThumbnail(source: PageSource, into: File) {
        val page = source.render(0, THUMBNAIL_PX)
        val square = Bitmap.createBitmap(page, 0, 0, THUMBNAIL_PX, minOf(THUMBNAIL_PX, page.height))
        into.outputStream().use { square.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun extractFilename(contentDisposition: String?): String? {
        if (contentDisposition == null) return null
        // Extract filename from Content-Disposition header
        // Format: attachment; filename="example.pdf" or attachment; filename*=UTF-8''...
        val filenameMatch = Regex("filename\\*?=(?:UTF-8'')?\"?([^\";\n]+)\"?").find(contentDisposition)
        return filenameMatch?.groupValues?.get(1)?.trim()
    }

}

/** A document row's thumbnail edge, in pixels (PLAN-13 §0.4). */
private const val THUMBNAIL_PX = 256
