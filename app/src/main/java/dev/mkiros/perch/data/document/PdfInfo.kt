package dev.mkiros.perch.data.document

import java.time.Instant

data class PdfInfo(val title: String?, val creationDate: Instant?)

object PdfInfoReader {
    fun read(file: java.io.File): PdfInfo {
        return try {
            val bytes = file.readBytes()
            if (bytes.size < 5) return PdfInfo(null, null)

            val text = String(bytes, Charsets.ISO_8859_1)
            if (!text.startsWith("%PDF-")) return PdfInfo(null, null)
            if (text.contains("/Encrypt")) return PdfInfo(null, null)

            val infoNum = findInfoObjectNumber(text)
            val title = if (infoNum >= 0) {
                extractTitleFromObject(text, infoNum)?.let { cleanTitle(it, file.nameWithoutExtension) }
            } else null

            val date = if (infoNum >= 0) {
                extractDateFromObject(text, infoNum)
            } else null

            val finalTitle = title ?: extractFromXmp(text, file.nameWithoutExtension)
            PdfInfo(finalTitle, date)
        } catch (e: Exception) {
            PdfInfo(null, null)
        }
    }

    private fun findInfoObjectNumber(text: String): Int {
        // Find the last /Info N 0 R reference (in trailer or xref)
        val lastInfoMatch = text.lastIndexOf("/Info")
        if (lastInfoMatch < 0) return -1

        var idx = lastInfoMatch + 5
        while (idx < text.length && text[idx].isWhitespace()) idx++

        // Extract the object number N
        val numStart = idx
        while (idx < text.length && text[idx].isDigit()) idx++
        if (numStart == idx) return -1

        return text.substring(numStart, idx).toIntOrNull() ?: -1
    }

    private fun extractTitleFromObject(text: String, objNum: Int): String? {
        val objContent = getObjectContent(text, objNum) ?: return null

        // Look for /Title in the dictionary
        val titleIdx = objContent.indexOf("/Title")
        if (titleIdx < 0) return null

        var idx = titleIdx + 6
        while (idx < objContent.length && objContent[idx].isWhitespace()) idx++

        if (idx >= objContent.length) return null

        return when {
            objContent[idx] == '(' -> extractLiteralString(objContent, idx)
            objContent[idx] == '<' -> {
                val hex = extractHexString(objContent, idx)
                if (hex != null) decodeHex(hex) else null
            }
            objContent[idx].isDigit() -> {
                // Indirect reference like "12 0 R"
                val refNum = extractRefNumber(objContent, idx)
                if (refNum >= 0) extractTitleFromObject(text, refNum) else null
            }
            else -> null
        }
    }

    private fun extractDateFromObject(text: String, objNum: Int): Instant? {
        val objContent = getObjectContent(text, objNum) ?: return null
        val dateIdx = objContent.indexOf("/CreationDate")
        if (dateIdx < 0) return null

        var idx = dateIdx + 13
        while (idx < objContent.length && objContent[idx].isWhitespace()) idx++

        if (idx >= objContent.length) return null

        val dateStr = when {
            objContent[idx] == '(' -> extractLiteralString(objContent, idx)
            else -> null
        }

        return if (dateStr != null) parsePdfDate(dateStr) else null
    }

    private fun getObjectContent(text: String, objNum: Int): String? {
        val objStart = findObjectStart(text, objNum)
        if (objStart < 0) return findInObjectStreams(text, objNum)

        val endObjIdx = text.indexOf("endobj", objStart)
        if (endObjIdx < 0) return null

        return text.substring(objStart, endObjIdx)
    }

    /**
     * ISO 32000-1 §7.5.7: a `/Type /ObjStm` stream holds `/N` objects; its inflated bytes open
     * with `N` pairs of `objnum offset`, offsets counted from `/First`.
     */
    private fun findInObjectStreams(text: String, objNum: Int): String? {
        for (match in Regex("/Type\\s*/ObjStm\\b").findAll(text)) {
            val dictStart = text.lastIndexOf("obj", match.range.first)
            val streamKeyword = text.indexOf("stream", match.range.last)
            if (dictStart < 0 || streamKeyword < 0) continue
            val dict = text.substring(dictStart, streamKeyword)
            val count = Regex("/N\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val first = Regex("/First\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: continue

            var dataStart = streamKeyword + "stream".length
            if (dataStart < text.length && text[dataStart] == '\r') dataStart++
            if (dataStart < text.length && text[dataStart] == '\n') dataStart++
            val dataEnd = text.indexOf("endstream", dataStart).takeIf { it >= 0 } ?: continue
            val body = inflate(text.substring(dataStart, dataEnd).toByteArray(Charsets.ISO_8859_1)) ?: continue
            if (first > body.length) continue

            val header = body.substring(0, first).trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            if (header.size < count * 2) continue
            for (i in 0 until count) {
                if (header[i * 2] != objNum) continue
                val start = first + header[i * 2 + 1]
                val end = if (i + 1 < count) first + header[(i + 1) * 2 + 1] else body.length
                if (start > body.length || end < start) return null
                return body.substring(start, minOf(end, body.length))
            }
        }
        return null
    }

    private fun inflate(bytes: ByteArray): String? {
        val inflater = java.util.zip.Inflater()
        return try {
            inflater.setInput(bytes)
            val out = java.io.ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            String(out.toByteArray(), Charsets.ISO_8859_1)
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun findObjectStart(text: String, objNum: Int): Int {
        // Look for "N 0 obj" with word boundaries
        val pattern = Regex("(?:^|\\s)$objNum\\s+0\\s+obj", RegexOption.MULTILINE)
        val match = pattern.find(text) ?: return -1

        var pos = match.range.last + 1
        // Skip to the end of the line
        while (pos < text.length && text[pos] != '\n' && text[pos] != '\r') pos++
        if (pos < text.length && text[pos] == '\r') pos++
        if (pos < text.length && text[pos] == '\n') pos++
        return pos
    }

    private fun extractRefNumber(text: String, startIdx: Int): Int {
        var idx = startIdx
        val numStart = idx
        // Extract digits for the object number
        while (idx < text.length && text[idx].isDigit()) idx++
        if (idx == numStart) return -1

        val refNum = text.substring(numStart, idx).toIntOrNull() ?: return -1

        // Skip whitespace
        while (idx < text.length && text[idx].isWhitespace()) idx++
        if (idx >= text.length || text[idx] != '0') return -1
        idx++

        // Skip whitespace
        while (idx < text.length && text[idx].isWhitespace()) idx++
        if (idx >= text.length || text[idx] != 'R') return -1

        return refNum
    }


    private fun extractLiteralString(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '(') return null

        val sb = StringBuilder()
        var i = start + 1
        var depth = 0

        while (i < text.length) {
            when {
                text[i] == '\\' && i + 1 < text.length -> {
                    when (text[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '(' -> sb.append('(')
                        ')' -> sb.append(')')
                        '\\' -> sb.append('\\')
                        else -> sb.append(text[i + 1])
                    }
                    i += 2
                }
                text[i] == '(' -> {
                    depth++
                    sb.append('(')
                    i++
                }
                text[i] == ')' && depth > 0 -> {
                    depth--
                    sb.append(')')
                    i++
                }
                text[i] == ')' && depth == 0 -> {
                    val result = sb.toString()
                    // Check for UTF-16BE BOM (FE FF as two bytes in ISO-8859-1)
                    if (result.length >= 2 && result[0].code == 0xFE && result[1].code == 0xFF) {
                        return try {
                            val bytes = result.toByteArray(Charsets.ISO_8859_1)
                            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).trim().ifEmpty { null }
                        } catch (e: Exception) {
                            result.trim().ifEmpty { null }
                        }
                    }
                    return result.trim().ifEmpty { null }
                }
                else -> {
                    sb.append(text[i])
                    i++
                }
            }
        }
        return null
    }

    private fun extractHexString(text: String, start: Int): String? {
        if (start >= text.length || text[start] != '<') return null
        var i = start + 1
        while (i < text.length && text[i] != '>') i++
        return if (i < text.length) text.substring(start + 1, i) else null
    }

    private fun decodeHex(hex: String): String? {
        val clean = hex.replace(Regex("\\s"), "").lowercase()
        if (clean.isEmpty()) return null

        return try {
            val bytes = ByteArray((clean.length + 1) / 2)
            for (i in bytes.indices) {
                val idx = i * 2
                val part = if (idx + 1 < clean.length) clean.substring(idx, idx + 2) else clean[idx].toString() + "0"
                bytes[i] = part.toInt(16).toByte()
            }

            // Check UTF-16BE BOM
            if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFE && (bytes[1].toInt() and 0xFF) == 0xFF) {
                // Skip the BOM bytes (2 bytes) and decode the rest as UTF-16BE
                String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE).trim().ifEmpty { null }
            } else {
                String(bytes, Charsets.ISO_8859_1).trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** ISO 32000-1 §14.3.2: the first `<dc:title>` only — a boilerplate one is no title, not a cue to look further. */
    private fun extractFromXmp(text: String, fileNameWithoutExtension: String): String? {
        val regex = Regex("<dc:title>.*?<rdf:li[^>]*>([^<]+)</rdf:li>.*?</dc:title>", RegexOption.DOT_MATCHES_ALL)
        val first = regex.find(text) ?: return null
        return cleanTitle(unescapeXml(first.groupValues[1]), fileNameWithoutExtension)
    }

    private fun unescapeXml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun parsePdfDate(s: String): Instant? {
        return try {
            // Format: D:YYYYMMDDHHmmSSOHH'mm' or D:YYYYMMDDHHmmSSZ
            val clean = s.replace("D:", "").replace("'", "")
            if (clean.length < 8) return null

            val year = clean.substring(0, 4).toInt()
            val month = clean.substring(4, 6).toInt()
            val day = clean.substring(6, 8).toInt()
            val hour = if (clean.length >= 10) clean.substring(8, 10).toInt() else 0
            val minute = if (clean.length >= 12) clean.substring(10, 12).toInt() else 0
            val second = if (clean.length >= 14) clean.substring(12, 14).toInt() else 0

            val offset = if (clean.length > 14) {
                when (clean[14]) {
                    'Z' -> java.time.ZoneOffset.UTC
                    '+', '-' -> {
                        val sign = if (clean[14] == '-') -1 else 1
                        val tzH = if (clean.length >= 17) clean.substring(15, 17).toInt() else 0
                        val tzM = if (clean.length >= 19) clean.substring(17, 19).toInt() else 0
                        java.time.ZoneOffset.ofHoursMinutes(sign * tzH, sign * tzM)
                    }
                    else -> java.time.ZoneOffset.UTC
                }
            } else {
                java.time.ZoneOffset.UTC
            }

            val ldt = java.time.LocalDateTime.of(year, month, day, hour, minute, second)
            ldt.atOffset(offset).toInstant()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Producer boilerplate is not a title (§0.3): a `Microsoft Word - ` prefix and an authoring-file
     * suffix are stripped, then `Print`, `Untitled`, `PowerPoint Presentation`, `Slide 1` and the
     * file's own name are rejected.
     */
    private fun cleanTitle(raw: String, fileNameWithoutExtension: String): String? {
        val title = raw.replace(Regex("\\s+"), " ").trim()
            .removePrefix("Microsoft Word - ")
            .replace(Regex("\\.(docx?|indd|tex|dvi)$", RegexOption.IGNORE_CASE), "")
            .trim()
        val lower = title.lowercase()
        val rejected = title.isEmpty() ||
            lower in setOf("print", "untitled", "powerpoint presentation", "slide 1") ||
            lower == fileNameWithoutExtension.lowercase()
        return title.takeUnless { rejected }
    }
}
