package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.extract.ArticleFixtures
import java.io.File
import org.jsoup.Jsoup
import org.junit.Test

/**
 * The five Zero Day Initiative advisories (U11a), through the path they really take:
 * sanitize → lower.
 *
 * ZDI's feed ships the whole body, tables and all, so these fixtures are the article HTML
 * as published rather than the surrounding page — U10's extractor never runs on a source
 * like this one.
 *
 * ZDI is the corpus source for tables because it publishes them constantly and in three
 * different dialects — a hand-written `<thead>` table, a Word/Excel export whose header
 * row is seven `<td>`s that merely *look* like a header, and a styled marketing table
 * carrying its own colours and column widths. The contract this file defends is that a
 * table survives the pipeline **rectangular**: every row the same width, so the renderer
 * can give a column one width and have it mean the same thing in every row. A dropped or
 * unpadded cell shifts every column to its right by one and turns an advisory into
 * nonsense, which reads as a rendering bug and is really a lowering one.
 */
class TableCorpusTest {

    @Test
    fun `every harvested advisory lowers to rectangular tables`() {
        for (page in PAGES) {
            val tables = tablesOf(page)

            assertThat(tables).isNotEmpty()
            for (table in tables) {
                val widths = table.rows.map { it.size }.distinct()
                assertThat(widths).hasSize(1)
                if (table.header.isNotEmpty()) {
                    assertThat(widths.single()).isEqualTo(table.header.size)
                }
            }
        }
    }

    @Test
    fun `no cell is lost between the markup and the blocks`() {
        for (page in PAGES) {
            val tables = tablesOf(page)
            val lowered = tables.sumOf { it.header.size + it.rows.sumOf { row -> row.size } }
            val filled = tables.sumOf { table ->
                table.header.count { it.text.isNotBlank() } +
                    table.rows.sumOf { row -> row.count { it.text.isNotBlank() } }
            }

            // No source table here spans a cell, so the grid is exactly what the markup
            // says: every cell present and not one of them invented. Counting the *written*
            // cells separately is what keeps the first assertion honest — a lowering that
            // padded the whole table out of thin air would satisfy the total alone.
            assertThat(lowered).isEqualTo(page.cells)
            assertThat(filled).isEqualTo(page.writtenCells)
        }
    }

    @Test
    fun `a header of th is promoted and a first row of td stays a body row`() {
        for (page in PAGES) {
            val first = tablesOf(page).first()

            if (page.header != null) {
                assertThat(first.header.map { it.text }).containsAtLeastElementsIn(page.header)
            } else {
                assertThat(first.header).isEmpty()
                assertThat(first.rows.first().map { it.text }).contains(page.firstBodyCell)
            }
        }
    }

    @Test
    fun `the advisory rows themselves come through`() {
        for (page in PAGES) {
            val texts = tablesOf(page).flatMap { it.rows.flatten() }.map { it.text }

            assertThat(texts).contains(page.sample)
        }
    }

    // ---- the pipeline under test ------------------------------------------------

    private fun tablesOf(page: ZdiPage): List<ArticleBlock.Table> {
        val html = File(ArticleFixtures.dir(), "${page.slug}.html").readText()
        val sanitized = HtmlSanitizer.sanitize(html, page.url)
        return ArticleLowering.toBlocks(sanitized).filterIsInstance<ArticleBlock.Table>()
    }

    /**
     * `cells` and `writtenCells` are counted straight off the markup by jsoup rather than
     * written down here, so the expectation cannot drift away from the fixture.
     *
     * @param header the header row's texts, or null for a table whose first row is `td`.
     */
    private data class ZdiPage(
        val slug: String,
        val url: String,
        val header: List<String>?,
        val firstBodyCell: String,
        val sample: String,
    ) {
        private fun sourceCells() =
            Jsoup.parse(File(ArticleFixtures.dir(), "$slug.html").readText())
                .select("table").flatMap { it.select("td, th") }

        val cells: Int get() = sourceCells().size

        /** The cells with something in them: ZDI leaves a handful genuinely empty. */
        val writtenCells: Int get() = sourceCells().count { it.text().isNotBlank() }
    }

    private companion object {
        const val ZDI = "https://www.thezdi.com/blog"

        val PAGES = listOf(
            ZdiPage(
                slug = "zdi-june-2026-apple-update-review",
                url = "$ZDI/2026/6/30/the-june-2026-apple-security-update-review",
                header = listOf("CVE ID", "Component", "Impact"),
                firstBodyCell = "",
                sample = "CVE-2026-43743",
            ),
            ZdiPage(
                slug = "zdi-july-2026-apple-update-review",
                url = "$ZDI/2026/7/29/the-july-2026-apple-security-update-review",
                header = listOf("CVE ID", "Component", "Impact"),
                firstBodyCell = "",
                sample = "IOGPUFamily",
            ),
            ZdiPage(
                slug = "zdi-april-2026-update-review",
                url = "$ZDI/2026/4/14/the-april-2026-security-update-review",
                header = listOf("Bulletin ID", "Product"),
                firstBodyCell = "",
                sample = "Important",
            ),
            ZdiPage(
                slug = "zdi-apple-macos-update-review",
                url = "$ZDI/2026/5/12/the-apple-macos-security-update-review",
                header = listOf("CVE ID", "Component"),
                firstBodyCell = "",
                sample = "Kernel",
            ),
            // The Excel export: seven `td`s that read as a header and are not one.
            ZdiPage(
                slug = "zdi-march-2026-update-review",
                url = "$ZDI/2026/3/10/the-march-2026-security-update-review",
                header = null,
                firstBodyCell = "CVE",
                sample = "CVE-2026-26127",
            ),
        )
    }
}
