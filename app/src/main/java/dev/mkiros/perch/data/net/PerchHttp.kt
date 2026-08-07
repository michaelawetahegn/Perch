package dev.mkiros.perch.data.net

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * The one HTTP client the app shares, configured per SPEC.md §6.
 *
 * The disk cache does not compete with [FeedFetcher]'s conditional GET: OkHttp skips its
 * cache entirely for a request that already carries `If-None-Match`/`If-Modified-Since`,
 * so a feed refresh is always the explicit exchange we store validators for, and the cache
 * is left to serve everything else (discovery pages, images).
 */
object PerchHttp {

    const val USER_AGENT = "Perch/1.0 (Android; +local-first RSS reader)"

    private const val CACHE_BYTES = 10L * 1024L * 1024L

    /** [cacheDir] is the app's `cacheDir`; null disables the disk cache (tests). */
    fun client(cacheDir: File?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(UserAgent)
        .apply { cacheDir?.let { cache(Cache(File(it, "http"), CACHE_BYTES)) } }
        .build()

    private object UserAgent : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response =
            chain.proceed(
                chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
            )
    }
}
