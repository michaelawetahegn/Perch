package dev.mkiros.perch.ui.article.code

import dev.mkiros.perch.ui.theme.CodeToken

/** [token] applies over `code[start, end)`. Spans are ordered and never overlap. */
data class CodeSpan(val start: Int, val end: Int, val token: CodeToken)

/**
 * A single-pass lexer, shared by every language except markup, driven by a [Spec] (U11).
 *
 * It is not a parser and must never become one. A feed hands us a *fragment* — the middle
 * of a function, a diff hunk, a config file with the top cut off — so there is nothing to
 * parse and no error to report. Every loop below is written so that the scanner always
 * advances and an unclosed construct simply runs to the end of its line or of the text.
 * **A highlighter that throws is a blank article**, which is strictly worse than one that
 * colours the last four lines wrong.
 *
 * Only non-plain spans are emitted; whatever is not covered draws in `onSurface`.
 */
object CodeHighlighter {

    fun tokenize(code: String, language: CodeLanguage): List<CodeSpan> = when (language) {
        CodeLanguage.Plain -> emptyList()
        CodeLanguage.Markup -> markup(code)
        else -> scan(code, SPECS.getValue(language))
    }

    // ---- the generic scanner ----------------------------------------------------

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun scan(code: String, spec: Spec): List<CodeSpan> {
        val out = mutableListOf<CodeSpan>()
        var i = 0
        while (i < code.length) {
            val c = code[i]

            // A C preprocessor line is checked before comments so that `#` keeps meaning
            // "directive" in C and "comment" in Python without either language needing to
            // know about the other.
            if (spec.preprocessor && c == '#' && startsLine(code, i)) {
                val end = lineEnd(code, i)
                out += CodeSpan(i, end, CodeToken.Meta)
                i = end
                continue
            }
            if (spec.lineComments.any { code.startsWith(it, i) }) {
                val end = lineEnd(code, i)
                out += CodeSpan(i, end, CodeToken.Comment)
                i = end
                continue
            }

            val block = spec.blockComment
            if (block != null && code.startsWith(block.first, i)) {
                val end = blockCommentEnd(code, i, block, spec.nestedBlocks)
                out += CodeSpan(i, end, CodeToken.Comment)
                i = end
                continue
            }
            val long = spec.longStrings.firstOrNull { code.startsWith(it, i) }
            if (long != null) {
                val close = code.indexOf(long, i + long.length)
                val end = if (close < 0) code.length else close + long.length
                out += CodeSpan(i, end, spec.stringToken(code, end))
                i = end
                continue
            }
            if (c in spec.strings) {
                val end = stringEnd(code, i, c)
                out += CodeSpan(i, end, spec.stringToken(code, end))
                i = end
                continue
            }
            if (spec.annotations && c == '@' && isIdentStart(code.getOrNull(i + 1))) {
                val end = identEnd(code, i + 1)
                out += CodeSpan(i, end, CodeToken.Meta)
                i = end
                continue
            }
            if (spec.dollarVars && c == '$') {
                val end = variableEnd(code, i)
                if (end > i + 1) {
                    out += CodeSpan(i, end, CodeToken.Meta)
                    i = end
                    continue
                }
            }
            if (c.isDigit()) {
                val end = numberEnd(code, i)
                out += CodeSpan(i, end, CodeToken.Number)
                i = end
                continue
            }
            if (isIdentStart(c)) {
                val end = identEnd(code, i)
                val word = code.substring(i, end)
                if (spec.isKeyword(word)) out += CodeSpan(i, end, CodeToken.Keyword)
                i = end
                continue
            }
            i++
        }
        return out
    }

    /** Reads to the end of the current line, newline excluded. */
    private fun lineEnd(code: String, from: Int): Int =
        code.indexOf('\n', from).let { if (it < 0) code.length else it }

    /** True when only whitespace separates [at] from the start of its line. */
    private fun startsLine(code: String, at: Int): Boolean {
        var i = at - 1
        while (i >= 0 && code[i] != '\n') {
            if (!code[i].isWhitespace()) return false
            i--
        }
        return true
    }

    /** Unterminated is not an error: the comment simply owns the rest of the text. */
    private fun blockCommentEnd(
        code: String,
        from: Int,
        delims: Pair<String, String>,
        nested: Boolean,
    ): Int {
        val (open, close) = delims
        var depth = 1
        var i = from + open.length
        while (i < code.length) {
            if (nested && code.startsWith(open, i)) {
                depth++
                i += open.length
            } else if (code.startsWith(close, i)) {
                depth--
                i += close.length
                if (depth == 0) return i
            } else {
                i++
            }
        }
        return code.length
    }

    /** A one-line literal: it closes on its quote, and otherwise at the newline. */
    private fun stringEnd(code: String, from: Int, quote: Char): Int {
        var i = from + 1
        while (i < code.length) {
            when (code[i]) {
                '\\' -> i++
                '\n' -> return i
                quote -> return i + 1
            }
            i++
        }
        return code.length
    }

    private fun numberEnd(code: String, from: Int): Int {
        var i = from + 1
        while (i < code.length) {
            val c = code[i]
            val continues = c.isLetterOrDigit() || c == '_' ||
                // `1.5` is one number and `1..10` is two — a dot only continues a literal
                // when a digit follows it.
                (c == '.' && code.getOrNull(i + 1)?.isDigit() == true)
            if (!continues) break
            i++
        }
        return i
    }

    private fun identEnd(code: String, from: Int): Int {
        var i = from
        while (i < code.length && isIdentPart(code[i])) i++
        return i
    }

    /** `$NAME` or `${NAME}`; a bare `$` (a shell prompt, mostly) is left alone. */
    private fun variableEnd(code: String, from: Int): Int = when {
        code.getOrNull(from + 1) == '{' ->
            code.indexOf('}', from + 2).let { if (it < 0) from else it + 1 }

        isIdentStart(code.getOrNull(from + 1)) -> identEnd(code, from + 1)
        else -> from
    }

    private fun isIdentStart(c: Char?): Boolean = c != null && (c.isLetter() || c == '_')

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    // ---- markup -----------------------------------------------------------------

    /**
     * XML and HTML are their own scanner because their words are structural: a tag name is
     * not a keyword the generic lexer could look up, and an attribute name is only an
     * attribute name because of where it sits.
     */
    private fun markup(code: String): List<CodeSpan> {
        val out = mutableListOf<CodeSpan>()
        var i = 0
        while (i < code.length) {
            if (code.startsWith("<!--", i)) {
                val close = code.indexOf("-->", i + "<!--".length)
                val end = if (close < 0) code.length else close + "-->".length
                out += CodeSpan(i, end, CodeToken.Comment)
                i = end
                continue
            }
            if (code[i] != '<') {
                i++
                continue
            }
            if (code.startsWith("<!", i) || code.startsWith("<?", i)) {
                val close = code.indexOf('>', i)
                val end = if (close < 0) code.length else close + 1
                out += CodeSpan(i, end, CodeToken.Meta)
                i = end
                continue
            }
            i = markupTag(code, i, out)
        }
        return out
    }

    /** Scans one `<tag …>` from its `<`, returning where the scanner should resume. */
    private fun markupTag(code: String, from: Int, out: MutableList<CodeSpan>): Int {
        var i = from + 1
        if (code.getOrNull(i) == '/') i++
        val nameEnd = identEnd(code, i)
        if (nameEnd == i) return from + 1
        out += CodeSpan(from, nameEnd, CodeToken.Keyword)
        i = nameEnd

        while (i < code.length) {
            val c = code[i]
            when {
                c == '>' -> {
                    out += CodeSpan(i, i + 1, CodeToken.Keyword)
                    return i + 1
                }

                c == '/' && code.getOrNull(i + 1) == '>' -> {
                    out += CodeSpan(i, i + 2, CodeToken.Keyword)
                    return i + 2
                }

                c == '"' || c == '\'' -> {
                    val end = stringEnd(code, i, c)
                    out += CodeSpan(i, end, CodeToken.StringLit)
                    i = end
                }

                isIdentStart(c) -> {
                    val end = attributeEnd(code, i)
                    out += CodeSpan(i, end, CodeToken.Meta)
                    i = end
                }

                else -> i++
            }
        }
        return code.length
    }

    /** Attribute names carry punctuation an identifier does not: `xml:lang`, `data-id`. */
    private fun attributeEnd(code: String, from: Int): Int {
        var i = from
        while (i < code.length && (isIdentPart(code[i]) || code[i] == '-' || code[i] == ':')) i++
        return i
    }

    // ---- the language table -----------------------------------------------------

    private class Spec(
        keywords: Set<String>,
        val caseInsensitive: Boolean = false,
        val lineComments: List<String> = listOf("//"),
        val blockComment: Pair<String, String>? = "/*" to "*/",
        val nestedBlocks: Boolean = false,
        val strings: Set<Char> = setOf('"', '\''),
        val longStrings: List<String> = emptyList(),
        val preprocessor: Boolean = false,
        val annotations: Boolean = false,
        val dollarVars: Boolean = false,
        /** JSON only: a string that a `:` follows is a key, which reads as [CodeToken.Meta]. */
        val keyStrings: Boolean = false,
    ) {
        private val words = if (caseInsensitive) keywords.map { it.lowercase() }.toSet() else keywords

        fun isKeyword(word: String): Boolean =
            words.contains(if (caseInsensitive) word.lowercase() else word)

        fun stringToken(code: String, end: Int): CodeToken {
            if (!keyStrings) return CodeToken.StringLit
            var i = end
            while (i < code.length && code[i].isWhitespace()) i++
            return if (code.getOrNull(i) == ':') CodeToken.Meta else CodeToken.StringLit
        }
    }

    private val SPECS: Map<CodeLanguage, Spec> = mapOf(
        CodeLanguage.Kotlin to Spec(
            keywords = words(
                "as break by class companion const constructor continue crossinline data do " +
                    "dynamic else enum expect actual external false finally for fun get if " +
                    "import in infix init inline interface internal is lateinit noinline null " +
                    "object open operator out override package private protected public " +
                    "reified return sealed set super suspend tailrec this throw true try " +
                    "typealias val value var vararg when where while annotation abstract final " +
                    "Boolean Byte Char Double Float Int Long Short String Unit Any Nothing",
            ),
            annotations = true,
        ),
        CodeLanguage.Java to Spec(
            keywords = words(
                "abstract assert boolean break byte case catch char class const continue " +
                    "default do double else enum extends final finally float for goto if " +
                    "implements import instanceof int interface long native new package " +
                    "private protected public return short static strictfp super switch " +
                    "synchronized this throw throws transient try var void volatile while " +
                    "true false null record sealed permits yield String Integer Object",
            ),
            annotations = true,
        ),
        CodeLanguage.C to Spec(
            keywords = words(
                "alignas alignof auto bool break case catch char class const consteval " +
                    "constexpr continue decltype default delete do double else enum explicit " +
                    "extern false float for friend goto if inline int long mutable namespace " +
                    "new noexcept nullptr operator private protected public register " +
                    "reinterpret_cast return short signed sizeof static static_cast struct " +
                    "switch template this throw true try typedef typename union unsigned " +
                    "using virtual void volatile while NULL size_t uint8_t uint16_t uint32_t " +
                    "uint64_t int8_t int16_t int32_t int64_t id nil self BOOL YES NO",
            ),
            preprocessor = true,
        ),
        CodeLanguage.Python to Spec(
            keywords = words(
                "and as assert async await break class continue def del elif else except " +
                    "finally for from global if import in is lambda None nonlocal not or " +
                    "pass raise return True False try while with yield self match case",
            ),
            lineComments = listOf("#"),
            blockComment = null,
            longStrings = listOf("\"\"\"", "'''"),
            annotations = true,
        ),
        CodeLanguage.JavaScript to Spec(
            keywords = words(
                "abstract any as async await boolean break case catch class const constructor " +
                    "continue debugger declare default delete do else enum export extends " +
                    "false finally for from function get if implements import in instanceof " +
                    "interface is keyof let new null number of private protected public " +
                    "readonly return set static string super switch symbol this throw true " +
                    "try type typeof undefined var void while with yield namespace",
            ),
            longStrings = listOf("`"),
        ),
        CodeLanguage.Rust to Spec(
            keywords = words(
                "as async await box break const continue crate dyn else enum extern false fn " +
                    "for if impl in let loop match mod move mut pub ref return self Self " +
                    "static struct super trait true type union unsafe use where while " +
                    "bool char f32 f64 i8 i16 i32 i64 i128 isize str u8 u16 u32 u64 u128 " +
                    "usize String Vec Option Some None Result Ok Err",
            ),
            nestedBlocks = true,
            annotations = true,
        ),
        CodeLanguage.Go to Spec(
            keywords = words(
                "break case chan const continue default defer else fallthrough for func go " +
                    "goto if import interface map package range return select struct switch " +
                    "type var nil true false bool byte error float32 float64 int int8 int16 " +
                    "int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr make " +
                    "new len cap append copy delete panic recover",
            ),
            strings = setOf('"', '\''),
            longStrings = listOf("`"),
        ),
        CodeLanguage.Shell to Spec(
            keywords = words(
                "if then elif else fi for while until do done case esac in function return " +
                    "local export readonly declare unset shift source alias exit trap set " +
                    "break continue echo cd printf read test true false eval exec",
            ),
            lineComments = listOf("#"),
            blockComment = null,
            dollarVars = true,
        ),
        CodeLanguage.Json to Spec(
            keywords = words("true false null"),
            lineComments = emptyList(),
            blockComment = null,
            strings = setOf('"'),
            keyStrings = true,
        ),
        CodeLanguage.Sql to Spec(
            keywords = words(
                "add all alter and any as asc begin between by case cast check column commit " +
                    "constraint create cross database default delete desc distinct drop else " +
                    "end exists foreign from full group having if in index inner insert " +
                    "intersect into is join key left like limit not null offset on or order " +
                    "outer primary references replace returning right rollback select set " +
                    "table then transaction union unique update using values view when where " +
                    "with integer text real blob varchar boolean timestamp",
            ),
            caseInsensitive = true,
            lineComments = listOf("--"),
            strings = setOf('\'', '"'),
        ),
    )

    private fun words(spaceSeparated: String): Set<String> =
        spaceSeparated.split(' ').filter { it.isNotEmpty() }.toSet()
}
