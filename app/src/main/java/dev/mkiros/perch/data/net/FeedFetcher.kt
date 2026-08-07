package dev.mkiros.perch.data.net

import dev.mkiros.perch.data.parse.FetchedPage
import dev.mkiros.perch.data.parse.PageFetcher
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** What a single conditional GET of a feed can come back as. */
sealed interface FetchResult {

    /** The feed changed (or we had nothing to compare against) and here it is. */
    class Success(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        /** The URL after redirects — the address to poll from now on. */
        val finalUrl: String,
        val contentType: String?,
    ) : FetchResult

    /** The server recognised our validators. Touch `lastFetchedAt` and stop. */
    data object NotModified : FetchResult

    /** A per-source problem, phrased for the drawer's `⚠`. Never an exception. */
    data class Failure(val message: String) : FetchResult
}

/**
 * Fetches feed bytes over HTTP per SPEC.md §6.
 *
 * Two things it is careful about. A `304` returns without touching the body, because the
 * point of a conditional GET is that an unchanged feed costs nothing — the tests assert
 * zero bytes come off the wire. And the 8 MiB cap is enforced against the declared
 * `Content-Length` first and the stream second, so a server that lies about the length,
 * or omits it, still cannot make us buffer an unbounded body.
 *
 * It doubles as T11's [PageFetcher] so discovery and refresh share one client and one set
 * of limits; that adapter drops the failure message, which is why the richer [fetch]
 * overload is what the repository calls.
 */
class FeedFetcher(
    private val client: OkHttpClient,
    private val maxBodyBytes: Long = MAX_BODY_BYTES,
) : PageFetcher {

    /** Fetches [url], offering [etag]/[lastModified] as conditional-GET validators. */
    suspend fun fetch(url: String, etag: String?, lastModified: String?): FetchResult {
        val request = runCatching {
            Request.Builder().url(url).apply {
                etag?.let { header("If-None-Match", it) }
                lastModified?.let { header("If-Modified-Since", it) }
            }.build()
        }.getOrElse { return FetchResult.Failure("Not a usable address: $url") }

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response -> read(response) }
            } catch (e: IOException) {
                FetchResult.Failure(unreachable(request.url.host, e))
            }
        }
    }

    /** The [PageFetcher] view: bytes or nothing, for discovery. */
    override suspend fun fetch(url: String): FetchedPage? =
        when (val result = fetch(url, etag = null, lastModified = null)) {
            is FetchResult.Success ->
                FetchedPage(result.bytes, result.contentType, result.finalUrl)
            else -> null
        }

    private fun read(response: Response): FetchResult {
        if (response.code == NOT_MODIFIED) return FetchResult.NotModified
        if (!response.isSuccessful) return FetchResult.Failure(httpFailure(response))

        val body = response.body ?: return FetchResult.Failure("Empty response")
        if (body.contentLength() > maxBodyBytes) return FetchResult.Failure(tooLarge())

        val buffer = Buffer()
        while (buffer.size <= maxBodyBytes) {
            if (body.source().read(buffer, READ_CHUNK) == -1L) break
        }
        if (buffer.size > maxBodyBytes) return FetchResult.Failure(tooLarge())

        return FetchResult.Success(
            bytes = buffer.readByteArray(),
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            finalUrl = response.request.url.toString(),
            contentType = response.header("Content-Type"),
        )
    }

    private fun httpFailure(response: Response) = when (response.code) {
        404, 410 -> "Feed not found (HTTP ${response.code})"
        in 500..599 -> "The server is having trouble (HTTP ${response.code})"
        else -> "Fetch failed (HTTP ${response.code})"
    }

    private fun tooLarge() = "Feed is too large (over ${maxBodyBytes / MIB} MiB)"

    private fun unreachable(host: String, e: IOException) = when (e) {
        is SocketTimeoutException -> "Timed out contacting $host"
        is UnknownHostException -> "Could not find $host"
        else -> "Could not reach $host: ${e.message ?: e.javaClass.simpleName}"
    }

    private companion object {
        const val NOT_MODIFIED = 304
        const val MIB = 1024L * 1024L
        const val MAX_BODY_BYTES = 8L * MIB
        const val READ_CHUNK = 64L * 1024L
    }
}
