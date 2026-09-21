package dev.mkiros.perch.ui.nav

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.mkiros.perch.model.Incoming

/**
 * Extracts the incoming value from an intent (PLAN-13 §0.8).
 *
 * Pure over the intent: no context needed. [displayName] is the URI's last
 * segment; the caller with context uses [OpenableColumns.DISPLAY_NAME] through
 * the resolver if they need a better name.
 *
 * @return [Incoming.Link] if the intent is a SEND + text/plain with a URL,
 *   [Incoming.Document] if it is a SEND with EXTRA_STREAM or a VIEW with a PDF URI,
 *   null otherwise (MAIN launch, or no extractable value).
 */
fun incomingFrom(intent: Intent?): Incoming? {
    if (intent == null) return null

    val action = intent.action
    val type = intent.type

    return when (action) {
        Intent.ACTION_SEND -> {
            if (type == "text/plain") {
                // Extract the first http(s):// URL from the text
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val urlRegex = """https?://[^\s]+""".toRegex()
                val url = urlRegex.find(text)?.value
                if (url != null) Incoming.Link(url) else null
            } else if (type == "application/pdf") {
                // PDF stream was shared
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) Incoming.Document(uri, uri.lastPathSegment) else null
            } else {
                null
            }
        }
        Intent.ACTION_VIEW -> {
            // Opening a PDF with Perch
            if (type == "application/pdf") {
                val uri = intent.data
                if (uri != null) Incoming.Document(uri, uri.lastPathSegment) else null
            } else {
                null
            }
        }
        else -> null
    }
}
