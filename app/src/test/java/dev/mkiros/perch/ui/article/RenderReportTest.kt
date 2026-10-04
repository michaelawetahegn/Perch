package dev.mkiros.perch.ui.article

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * J07/#86 — the Report link, built, encoded and capped in one pure function. Robolectric only
 * because `Uri` needs it; every assertion reads a parsed parameter, never the raw encoding.
 */
@RunWith(RobolectricTestRunner::class)
class RenderReportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun report(
        link: String = "https://www.spectrum.ieee.org/x",
        source: String? = "IEEE Spectrum",
        problem: RenderProblem = RenderProblem.ImagesBroken,
        note: String = "",
        version: String = "0.10.0",
    ): Uri = Uri.parse(
        renderReportUrl(link, source, context.getString(problem.label), note, version),
    )

    private fun Uri.body(): String = getQueryParameter("body")!!

    @Test
    fun `the report opens a new issue on Perch's own repository from the render template`() {
        val uri = report()

        assertThat(uri.scheme).isEqualTo("https")
        assertThat(uri.host).isEqualTo("github.com")
        assertThat(uri.path).isEqualTo("/michaelawetahegn/Perch/issues/new")
        assertThat(uri.getQueryParameter("template")).isEqualTo("render-report.md")
    }

    @Test
    fun `the title names the host without www and the problem`() {
        assertThat(report().getQueryParameter("title"))
            .isEqualTo("Render: spectrum.ieee.org — Images broken")
    }

    @Test
    fun `the body carries the link, the source, the problem, the note and the version`() {
        val body = report(note = "The photos are grey boxes").body()

        assertThat(body).contains("https://www.spectrum.ieee.org/x")
        assertThat(body).contains("IEEE Spectrum")
        assertThat(body).contains("Images broken")
        assertThat(body).contains("The photos are grey boxes")
        assertThat(body).contains("Perch 0.10.0")
    }

    @Test
    fun `a blank note leaves no note line`() {
        assertThat(report(note = "   ").body()).doesNotContain("Note")
    }

    @Test
    fun `a note longer than 500 characters is cut to 500`() {
        val body = report(note = "a".repeat(499) + "bc" + "d".repeat(99)).body()

        assertThat(body).contains("a".repeat(499) + "b")
        assertThat(body).doesNotContain("bc")
    }

    @Test
    fun `a note with reserved characters and a newline round-trips intact`() {
        val note = "Q&A #2 lost?\nsee=here"

        assertThat(report(note = note).body()).contains(note)
    }

    @Test
    fun `a missing source reads unknown`() {
        assertThat(report(source = null).body()).contains("unknown")
    }

    @Test
    fun `the five problems read as the sheet names them`() {
        assertThat(RenderProblem.entries.map { context.getString(it.label) }).containsExactly(
            "Text missing or cut off",
            "Images broken",
            "Code or tables wrong",
            "Layout off",
            "Something else",
        ).inOrder()
    }
}
