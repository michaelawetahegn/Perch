package dev.mkiros.perch.ui.article.code

import dev.mkiros.perch.data.parse.CodeLanguageNames

/**
 * The languages [CodeHighlighter] can colour, plus [Plain] — which is not a failure but a
 * destination: a block whose language we do not know renders in the mono face with no
 * colour at all, which is exactly what it looked like before U11.
 *
 * `C` covers C, C++ and Objective-C; `JavaScript` covers TypeScript; `Markup` covers HTML,
 * XML and SVG. Splitting those would buy a handful of extra keywords and cost three more
 * lexers to keep total.
 */
enum class CodeLanguage {
    Kotlin,
    Java,
    C,
    Python,
    JavaScript,
    Rust,
    Go,
    Shell,
    Markup,
    Json,
    Sql,
    Plain,
    ;

    companion object {

        /**
         * [declared] is whatever survived `class="language-*"` through `HtmlSanitizer`;
         * [code] is the block's own text.
         *
         * **A declaration is final, including when it names something we cannot colour.**
         * `language-plaintext` on a block of C — which is exactly what nullprogram.com
         * ships for its shell transcripts — is a decision by the person who wrote the
         * post, and re-deciding it from a keyword count would be the app overruling the
         * author. Sniffing runs only when nothing was declared at all.
         */
        fun of(declared: String?, code: String): CodeLanguage {
            val id = declared?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return sniff(code)
            return ALIASES[id] ?: Plain
        }

        private val ALIASES: Map<String, CodeLanguage> = buildMap {
            putAll(CodeLanguageNames.KOTLIN, Kotlin)
            putAll(CodeLanguageNames.JAVA, Java)
            putAll(CodeLanguageNames.C, C)
            putAll(CodeLanguageNames.PYTHON, Python)
            putAll(CodeLanguageNames.JAVASCRIPT, JavaScript)
            putAll(CodeLanguageNames.RUST, Rust)
            putAll(CodeLanguageNames.GO, Go)
            putAll(CodeLanguageNames.SHELL, Shell)
            putAll(CodeLanguageNames.MARKUP, Markup)
            putAll(CodeLanguageNames.JSON, Json)
            putAll(CodeLanguageNames.SQL, Sql)
        }

        private fun MutableMap<String, CodeLanguage>.putAll(ids: List<String>, to: CodeLanguage) {
            ids.forEach { put(it, to) }
        }

        /**
         * Ordered most-distinctive first. Every rule needs **two** pieces of evidence,
         * because one keyword is not a language: `func` alone appears in PHP and Swift
         * posts, and `let` alone appears in half the JavaScript ever written.
         */
        @Suppress("ReturnCount")
        private fun sniff(code: String): CodeLanguage {
            val text = code.trim()
            if (text.isEmpty()) return Plain

            if (text.startsWith("<?xml") || text.startsWith("<!DOCTYPE", ignoreCase = true) ||
                (text.startsWith("<") && MARKUP_TAG.containsMatchIn(text))
            ) {
                return Markup
            }
            if ((text.startsWith("{") || text.startsWith("[")) && JSON_PAIR.containsMatchIn(text)) {
                return Json
            }
            if (text.startsWith("#!") && SHELL_SHEBANG.containsMatchIn(text)) return Shell
            if (text.contains("#include") || text.contains("int main(")) return C
            if (SQL_SELECT.containsMatchIn(text)) return Sql
            if (KOTLIN_FUN.containsMatchIn(text)) return Kotlin
            if (RUST_FN.containsMatchIn(text)) return Rust
            if (GO_PACKAGE.containsMatchIn(text)) return Go
            if (JAVA_CLASS.containsMatchIn(text)) return Java
            if (PYTHON_DEF.containsMatchIn(text)) return Python
            if (JS_DECL.containsMatchIn(text)) return JavaScript
            if (SHELL_PROMPT.containsMatchIn(text)) return Shell
            return Plain
        }

        private val MARKUP_TAG = Regex("""<[a-zA-Z][\w:-]*[^<>]*>""")
        private val JSON_PAIR = Regex(""""[^"]*"\s*:""")
        private val SHELL_SHEBANG = Regex("""#!\s*\S*/(?:env\s+)?(?:ba|z|k)?sh\b""")
        private val SQL_SELECT = Regex("""\bselect\b[\s\S]*\bfrom\b""", RegexOption.IGNORE_CASE)
        private val KOTLIN_FUN = Regex("""\bfun\s+\w+\s*\([\s\S]*\b(?:val|var)\b""")
        private val RUST_FN = Regex("""\bfn\s+\w+\s*\([\s\S]*\b(?:let|impl|mut|->)""")
        private val GO_PACKAGE = Regex("""\bpackage\s+\w+[\s\S]*\bfunc\b""")
        private val JAVA_CLASS = Regex("""\b(?:public|private)\s+(?:final\s+)?class\b|\bSystem\.out\b""")
        private val PYTHON_DEF = Regex("""\bdef\s+\w+\s*\([^)]*\)\s*(?:->[^:]+)?:|^\s*import\s+\w+""")
        private val JS_DECL = Regex("""\b(?:const|let|var|function)\b[\s\S]*(?:=>|function\s*\()""")
        private val SHELL_PROMPT = Regex("""^\s*[$#]\s+\S|\b(?:sudo|apt-get|chmod|grep)\s""")
    }
}
