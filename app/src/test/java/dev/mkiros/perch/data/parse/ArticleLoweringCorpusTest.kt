package dev.mkiros.perch.data.parse

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The lowering half of the standing corpus contract (T25a).
 *
 * [FeedCorpusTest] proves every harvested feed parses; this proves every harvested feed
 * *renders* — one real entry from each of the 39 snapshots goes through the exact pipeline
 * the app uses (parse → [HtmlSanitizer] → [ArticleLowering]) and must come out as blocks
 * the article screen can draw. Every failure is collected and reported together, so one
 * broken source names itself instead of hiding the other 38.
 */
class ArticleLoweringCorpusTest {

    @Test
    fun `every corpus feed lowers its meatiest entry into renderable blocks`() {
        val failures = mutableListOf<String>()
        val unsupported = mutableListOf<String>()
        var lowered = 0
        var total = 0

        for (snapshot in snapshots()) {
            val slug = snapshot.nameWithoutExtension
            val result = FeedParser().parse(snapshot.readBytes(), null, feedUrls()[slug])
            val feed = (result as? ParseResult.Success)?.feed ?: continue
            val entry = feed.entries.maxByOrNull { it.contentHtml?.length ?: 0 } ?: continue

            val body = HtmlSanitizer.sanitize(entry.contentHtml, entry.link)
            val blocks = try {
                ArticleLowering.toBlocks(body)
            } catch (t: Throwable) {
                failures += "$slug: toBlocks threw $t"
                continue
            }

            if (body != null && blocks.isEmpty()) {
                failures += "$slug: a ${body.length}-char body lowered to zero blocks"
            }
            if (blocks.isNotEmpty()) lowered++

            flatten(blocks).forEach { block ->
                total++
                if (block is ArticleBlock.Paragraph && block.text.text.isBlank()) {
                    failures += "$slug: emitted a bare empty paragraph"
                }
                if (block is ArticleBlock.Unsupported) unsupported += block.label
            }
        }

        // Without this the assertions below could pass on a corpus that lowered nothing.
        assertThat(lowered).isAtLeast(30)
        assertThat(failures).isEmpty()

        // T32 gate 2's threshold, held early: the answer to a common `Unsupported` is to
        // extend the mapper, so the distinct labels are named rather than merely counted.
        assertThat(
            "${unsupported.size}/$total blocks unsupported: ${unsupported.distinct().sorted()}",
        ).isEqualTo("0/$total blocks unsupported: []")
    }

    /** Quote is the one block that holds blocks; its contents are held to the same rules. */
    private fun flatten(blocks: List<ArticleBlock>): List<ArticleBlock> =
        blocks.flatMap { if (it is ArticleBlock.Quote) listOf(it) + flatten(it.blocks) else listOf(it) }

    private fun snapshots(): List<File> {
        val files = File(repoRoot(), "fixtures/snapshots").listFiles()
            ?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        check(files.size >= 35) { "expected ≥35 snapshots, found ${files.size}" }
        return files
    }

    private fun feedUrls(): Map<String, String> =
        File(repoRoot(), "fixtures/manifest.tsv").readLines()
            .mapNotNull { line -> line.split('\t').takeIf { it.size >= 2 } }
            .associate { it[0] to it[1] }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "fixtures/snapshots").isDirectory) return dir
            dir = dir.parentFile
        }
        error("fixtures/snapshots not found")
    }
}
