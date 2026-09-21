package dev.mkiros.perch.data.repo

import android.net.Uri
import java.io.InputStream

/**
 * Opens a content URI and returns its stream (PLAN-13 §0.8).
 *
 * A seam for testing — [AppContainer] wires the real `ContentResolver` implementation,
 * while tests inject a map.
 */
fun interface DocumentOpener {
    fun open(uri: Uri): InputStream?
}
