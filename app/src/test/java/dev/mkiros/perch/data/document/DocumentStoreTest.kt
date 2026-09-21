package dev.mkiros.perch.data.document

import com.google.common.truth.Truth.assertThat
import dev.mkiros.perch.data.db.DocumentRow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PLAN-13 §0.2: one directory, and the startup sweep that keeps it honest. */
class DocumentStoreTest {

    @get:Rule
    val tmpDir: TemporaryFolder = TemporaryFolder()

    @Test
    fun `the sweep keeps a kept document's thumbnail and drops an orphan's`() {
        val store = DocumentStore(tmpDir.root)
        val kept = store.newDocument().apply { writeText("%PDF-") }
        val keptThumbnail = store.thumbnailFor(kept).apply { writeText("png") }
        val orphan = store.newDocument().apply { writeText("%PDF-") }
        val orphanThumbnail = store.thumbnailFor(orphan).apply { writeText("png") }

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
        val dropped = store.newDocument().apply { writeText("%PDF-") }
        val thumbnail = store.thumbnailFor(dropped).apply { writeText("png") }

        val forget = store.sweep(listOf(DocumentRow(7, store.relativize(dropped)!!, isSaved = false, isStarred = false)))

        assertThat(forget).containsExactly(7L)
        assertThat(dropped.exists()).isFalse()
        assertThat(thumbnail.exists()).isFalse()
    }
}
