package dev.mkiros.perch.data.document

import dev.mkiros.perch.data.db.DocumentRow
import java.io.File
import java.util.UUID

/** A stored PDF document. The directory `filesDir/documents/` holds files and their thumbnails. */
class DocumentStore(private val directory: File) {

    init {
        directory.mkdirs()
    }

    /** A fresh `<uuid>.pdf` in the documents directory. */
    fun newDocument(): File = File(directory, "${UUID.randomUUID()}.pdf")

    /** The thumbnail file (PNG) for a document: `<uuid>-1.png` beside it. */
    fun thumbnailFor(document: File): File = File(directory, "${document.nameWithoutExtension}-1.png")

    /** The absolute file at a relative path (or null if it escapes the directory). */
    fun resolve(relativePath: String): File? {
        val file = File(directory, relativePath)
        return if (file.canonicalPath.startsWith(directory.canonicalPath)) file else null
    }

    /** The path of a file relative to [directory], or null if it is not under [directory]. */
    fun relativize(file: File): String? {
        val relative = file.canonicalPath.removePrefix(directory.canonicalPath + File.separator)
        return if (relative != file.canonicalPath) relative else null
    }

    /** Delete both the document and its thumbnail. Does nothing if the file does not exist. */
    fun delete(document: File) {
        document.delete()
        thumbnailFor(document).delete()
    }

    /**
     * Delete every file in the directory that no row names, and every file whose row is
     * neither saved nor liked. Return the ids whose row must forget the file.
     */
    fun sweep(rows: List<DocumentRow>): List<Long> {
        val byPath = rows.associateBy { it.documentPath }
        val toDelete = mutableListOf<Long>()

        directory.listFiles()?.forEach { file ->
            val path = relativize(file)
            val row = path?.let { byPath[it] }

            when {
                row == null -> delete(file) // file no row names
                !row.isSaved && !row.isStarred -> {
                    delete(file) // file whose row is neither saved nor liked
                    toDelete.add(row.id)
                }
            }
        }

        return toDelete
    }
}
