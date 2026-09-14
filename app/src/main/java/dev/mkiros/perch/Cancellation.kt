package dev.mkiros.perch

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` catches a `CancellationException` like any other failure, which turns a job
 * the caller stopped into "the fetch failed" and lets the coroutine run on past its own
 * cancellation, writing state nobody should see. Every `runCatching` in Perch that folds a
 * failure into a result calls this first, so a cancellation unwinds exactly as it would have
 * without the `runCatching` (#38 fixed the repositories, #60 the ViewModels above them).
 */
fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { if (it is CancellationException) throw it }
