package dev.mkiros.perch.support

import android.graphics.Bitmap
import android.graphics.Color
import dev.mkiros.perch.data.document.PageRasterizer
import dev.mkiros.perch.data.document.PageSize
import dev.mkiros.perch.data.document.PageSource
import dev.mkiros.perch.data.document.DocumentFixtures
import dev.mkiros.perch.data.document.DocumentFixture
import java.io.File
import java.security.MessageDigest

/** Test fixture rasterizer that uses pre-rendered PDFs. Matches files by SHA-256. */
class FixtureRasterizer : PageRasterizer {
    private val fixtures = DocumentFixtures.manifest()
        .filter { it.pages > 0 }
        .associateBy { it.sha256 }

    override fun open(file: File): PageSource? {
        if (!file.exists()) return null
        val sha256 = try {
            file.sha256()
        } catch (e: Exception) {
            return null
        }
        val fixture = fixtures[sha256] ?: return null
        return FixturePageSource(fixture)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private class FixturePageSource(private val fixture: DocumentFixture) : PageSource {
    private val renderedDir = File(File(repoRoot(), "fixtures/documents"), "rendered")

    override val pageCount: Int = fixture.pages

    override fun size(index: Int): PageSize {
        // Special case for mixed-sizes: return different sizes for each page
        if (fixture.slug == "mixed-sizes") {
            return when (index) {
                0 -> PageSize(595, 842)  // A4
                1 -> PageSize(300, 200)  // Small
                2 -> PageSize(595, 842)  // A4
                else -> PageSize(595, 842)
            }
        }

        val sizeStr = fixture.page1Pt
        val parts = sizeStr.split("x")
        return if (parts.size == 2) {
            PageSize(parts[0].toInt(), parts[1].toInt())
        } else {
            PageSize(612, 792) // fallback
        }
    }

    override fun render(index: Int, widthPx: Int): Bitmap {
        val pngFile = File(renderedDir, "${fixture.slug}-${index + 1}.png")

        return if (pngFile.exists()) {
            val fullBitmap = android.graphics.BitmapFactory.decodeFile(pngFile.absolutePath)
            if (fullBitmap != null) {
                // Scale from 600 px wide to requested width
                val scaleFactor = widthPx.toFloat() / 600f
                val newHeight = (fullBitmap.height * scaleFactor).toInt()
                Bitmap.createScaledBitmap(fullBitmap, widthPx, newHeight, true)
            } else {
                createPlaceholderBitmap(widthPx)
            }
        } else {
            createPlaceholderBitmap(widthPx)
        }
    }

    override fun close() {
    }

    private fun createPlaceholderBitmap(widthPx: Int): Bitmap {
        val size = size(0)
        val aspect = size.width.toFloat() / size.height.toFloat()
        val heightPx = (widthPx / aspect).toInt()

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        // Fill with white
        bitmap.eraseColor(Color.WHITE)
        // Draw a grey rule across the middle
        val greyColor = Color.rgb(200, 200, 200)
        for (x in 0 until widthPx) {
            bitmap.setPixel(x, heightPx / 2, greyColor)
        }
        return bitmap
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("Could not find repo root")
    }
}
