package dev.mkiros.perch.model

import android.net.Uri

/**
 * Something shared to Perch or a PDF opened with it (PLAN-13 G09 §0.8).
 *
 * [Link] is a URL pasted to the share sheet; [Document] is a file the reader
 * chose to open or shared from the browser or Files app.
 */
sealed interface Incoming {
    data class Link(val url: String) : Incoming
    data class Document(val uri: Uri, val displayName: String?) : Incoming
}
