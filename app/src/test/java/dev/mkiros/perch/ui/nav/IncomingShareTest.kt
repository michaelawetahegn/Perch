package dev.mkiros.perch.ui.nav

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.model.Incoming
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IncomingShareTest {

    @Test
    fun `a shared sentence with a URL in it is the URL`() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, "Check this out https://example.com/post/123")
        val result = incomingFrom(intent)
        assertThat(result).isInstanceOf(Incoming.Link::class.java)
        assertThat((result as Incoming.Link).url).isEqualTo("https://example.com/post/123")
    }

    @Test
    fun `a share with no URL is nothing`() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, "Just some text")
        val result = incomingFrom(intent)
        assertThat(result).isNull()
    }

    @Test
    fun `a shared PDF stream is a document with its display name`() {
        val uri = Uri.parse("content://com.example/document/123.pdf")
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "application/pdf"
        intent.putExtra(Intent.EXTRA_STREAM, uri)
        val result = incomingFrom(intent)
        assertThat(result).isInstanceOf(Incoming.Document::class.java)
        assertThat((result as Incoming.Document).uri).isEqualTo(uri)
        assertThat(result.displayName).isEqualTo("123.pdf")
    }

    @Test
    fun `a viewed PDF is a document`() {
        val uri = Uri.parse("content://com.example/document/paper.pdf")
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
        val result = incomingFrom(intent)
        assertThat(result).isEqualTo(Incoming.Document(uri, "paper.pdf"))
    }

    /**
     * The manifest's filter matched the resolver's type, but `Intent.getType()` is only a
     * type the sender set explicitly: a file manager that sends `ACTION_VIEW` with the data
     * alone reaches Perch with no type on the intent (§0.8: a `VIEW` with a `data` URI).
     */
    @Test
    fun `a viewed file whose sender set no type is still a document`() {
        val uri = Uri.parse("content://com.example/document/paper.pdf")
        val result = incomingFrom(Intent(Intent.ACTION_VIEW, uri))
        assertThat(result).isEqualTo(Incoming.Document(uri, "paper.pdf"))
    }

    @Test
    fun `a plain launch is nothing`() {
        val intent = Intent(Intent.ACTION_MAIN)
        val result = incomingFrom(intent)
        assertThat(result).isNull()
    }

    @Test
    fun `null intent is nothing`() {
        val result = incomingFrom(null)
        assertThat(result).isNull()
    }

    /** Reopened from Recents, a share's intent comes back as it was; it was taken the first time. */
    @Test
    fun `a share reopened from Recents is nothing`() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, "https://example.com/post/123")
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertThat(incomingFrom(intent)).isNull()
    }

    /** A rotation recreates the activity with the intent that started it; that is not a second share. */
    @Test
    fun `a share the activity is restored with is nothing`() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, "https://example.com/post/123")
        assertThat(incomingFrom(intent, restored = true)).isNull()
        assertThat(incomingFrom(intent, restored = false)).isEqualTo(Incoming.Link("https://example.com/post/123"))
    }
}
