package dev.mkiros.perch.ui.home

import android.content.Intent
import androidx.core.content.IntentCompat
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PLAN-4 §0 (#16): Perch never draws its own share sheet — it hands the OS an
 * `ACTION_SEND` through a chooser and lets the OS decide what is on it. What Perch owns
 * is the *contents* of that intent, so the intent is built by a pure function and this is
 * where it is pinned: no activity is launched here, nothing is on screen, and the two
 * screens that share an entry only fire what this function returns.
 */
@RunWith(RobolectricTestRunner::class)
class ShareIntentTest {

    @Test
    fun `a shared entry goes out as a chooser wrapping a plain-text send`() {
        val chooser = shareIntent(title = "An Async Runtime in C", link = "https://nullprogram.com/1/")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("text/plain")
    }

    @Test
    fun `the link is what gets sent and the title is the subject`() {
        val send = shareIntent(title = "An Async Runtime in C", link = "https://nullprogram.com/1/")
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!

        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("An Async Runtime in C")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("https://nullprogram.com/1/")
    }

    /** An entry with no link at all still shares as *something*, rather than as nothing. */
    @Test
    fun `an entry with no link falls back to sending its title`() {
        val send = shareIntent(title = "An Async Runtime in C", link = null)
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!

        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).isEqualTo("An Async Runtime in C")
    }
}
