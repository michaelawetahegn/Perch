package dev.mkiros.perch.model

/**
 * Applies a chosen interval to the background schedule. A seam rather than a direct call
 * so that the ViewModel does not hold a `Context`, and so a test can assert scheduling
 * without WorkManager if it only cares about persistence.
 */
fun interface RefreshScheduler {
    fun schedule(interval: RefreshInterval)
}
