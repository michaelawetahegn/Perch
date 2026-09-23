package dev.mkiros.perch.data.document

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.DocumentRow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** PLAN-13 §0.2: one directory, and the startup sweep that keeps it honest. */
class DocumentStoreTest {

    @get:Rule
    val tmpDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the sweep keeps a kept document's thumbnail and drops an orphan's`() {
        val store = DocumentStore(tmpDir.root)
        val kept = store.newDocument().apply { writeText("%PDF-") }.aged()
        val keptThumbnail = store.thumbnailFor(kept).apply { writeText("png") }.aged()
        val orphan = store.newDocument().apply { writeText("%PDF-") }.aged()
        val orphanThumbnail = store.thumbnailFor(orphan).apply { writeText("png") }.aged()

        val forget = store.sweep(listOf(DocumentRow(1, store.relativize(kept)!!, isSaved = true, isStarred = false)))

        assertThat(forget).isEmpty()
        assertThat(kept.exists()).isTrue()
        assertThat(keptThumbnail.exists()).isTrue()
        assertThat(orphan.exists()).isFalse()
        assertThat(orphanThumbnail.exists()).isFalse()
    }

    @Test
    fun `the sweep drops the file and thumbnail of a row neither saved nor liked`() {
        val store = DocumentStore(tmpDir.root)
        val dropped = store.newDocument().apply { writeText("%PDF-") }.aged()
        val thumbnail = store.thumbnailFor(dropped).apply { writeText("png") }.aged()

        val forget = store.sweep(listOf(DocumentRow(7, store.relativize(dropped)!!, isSaved = false, isStarred = false)))

        assertThat(forget).containsExactly(7L)
        assertThat(dropped.exists()).isFalse()
        assertThat(thumbnail.exists()).isFalse()
    }

    /**
     * A save writes its file before its row names it, and a share can cold-start the app into
     * the very sweep that runs at start-up. A file that recent is a save in progress, not an
     * orphan — and a leftover from a crash is swept at a later launch instead.
     */
    @Test
    fun `the sweep leaves a file still being saved alone`() {
        val store = DocumentStore(tmpDir.root)
        val writing = store.newDocument().apply { writeText("%PDF-") }
        val thumbnail = store.thumbnailFor(writing).apply { writeText("png") }
        val unsaved = store.newDocument().apply { writeText("%PDF-") }

        val forget = store.sweep(listOf(DocumentRow(3, store.relativize(unsaved)!!, isSaved = false, isStarred = false)))

        assertThat(forget).isEmpty()
        assertThat(writing.exists()).isTrue()
        assertThat(thumbnail.exists()).isTrue()
        assertThat(unsaved.exists()).isTrue()
    }

    /** Written long enough ago that no save can still be writing it. */
    private fun File.aged(): File = apply { setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L) }
}
