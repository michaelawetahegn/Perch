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

            val title = extractTitle(text)
            val date = extractDate(text)
            PdfInfo(title, date)
        } catch (e: Exception) {
            PdfInfo(null, null)
        }
    }

    private fun extractTitle(text: String): String? {
        // Try Info dictionary (/Title) - handle escaped parentheses
        var titleMatch = text.indexOf("/Title")
        while (titleMatch >= 0) {
            var idx = titleMatch + 6
            while (idx < text.length && text[idx].isWhitespace()) idx++

            if (idx < text.length) {
                if (text[idx] == '(') {
                    // Extract literal string, handling escaped parens
                    val str = extractLiteralString(text, idx)
                    if (str != null && str.isNotEmpty() && !isBoilerplate(str)) return str
                } else if (text[idx] == '<') {
                    // Extract hex string
                    val hex = extractHexString(text, idx)
                    if (hex != null) {
                        val decoded = decodeHex(hex)
                        if (decoded != null && !isBoilerplate(decoded)) return decoded
                    }
                }
            }

            titleMatch = text.indexOf("/Title", titleMatch + 6)
        }

        // Try XMP - get the last non-boilerplate one
        var title = extractFromXmp(text)
        if (title != null && !isBoilerplate(title)) return title

        return null
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
                    return sb.toString().trim().ifEmpty { null }
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

    private fun extractDate(text: String): Instant? {
        val dateStr = extractViaRegex(text, "/CreationDate\\s*\\(([^)]+)\\)") ?: return null
        return parsePdfDate(dateStr)
    }

    private fun extractViaRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeHex(hex: String): String? {
        val clean = hex.replace(Regex("\\s"), "")
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
                String(bytes, Charsets.UTF_16BE).trim().ifEmpty { null }
            } else {
                String(bytes, Charsets.ISO_8859_1).trim().ifEmpty { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractFromXmp(text: String): String? {
        // Extract all dc:title entries and return the last non-boilerplate one
        val regex = Regex("<dc:title>.*?<rdf:li[^>]*>([^<]+)</rdf:li>.*?</dc:title>", RegexOption.DOT_MATCHES_ALL)
        var lastTitle: String? = null
        for (match in regex.findAll(text)) {
            val title = unescapeXml(match.groupValues[1].trim())
            if (title.isNotEmpty()) lastTitle = title
        }
        return lastTitle
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

    private fun isBoilerplate(title: String): Boolean {
        val lower = title.lowercase().trim()
        return lower in setOf("print", "untitled") ||
            lower.startsWith("microsoft word - ") ||
            lower.endsWith(".doc") ||
            lower.endsWith(".docx") ||
            lower.endsWith(".indd") ||
            lower.endsWith(".tex") ||
            lower.endsWith(".dvi")
    }
}
