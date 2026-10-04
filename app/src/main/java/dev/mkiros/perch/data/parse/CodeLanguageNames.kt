package dev.mkiros.perch.data.parse

/**
 * Every name a code block's language goes by, grouped by the lexer that colours it.
 *
 * It lives here rather than beside `CodeLanguage` because the sanitizer needs the plain set
 * too (J04): an unprefixed `highlight <token>` wrapper is a claim only when `<token>` is a
 * language, and `data/` must not import `ui/`. `CodeLanguage` maps each group to itself.
 */
object CodeLanguageNames {
    val KOTLIN: List<String> = listOf("kotlin", "kt", "kts")
    val JAVA: List<String> = listOf("java", "jsp")
    val C: List<String> = listOf(
        "c", "h", "cpp", "c++", "cc", "cxx", "hpp", "hxx", "cs", "objc",
        "objectivec", "csharp", "arduino",
    )
    val PYTHON: List<String> = listOf("python", "py", "python3", "python2", "ipython")
    val JAVASCRIPT: List<String> = listOf(
        "javascript", "js", "jsx", "mjs", "cjs", "node", "typescript", "ts",
        "tsx", "json5",
    )
    val RUST: List<String> = listOf("rust", "rs")
    val GO: List<String> = listOf("go", "golang")
    val SHELL: List<String> = listOf(
        "shell", "sh", "bash", "zsh", "ksh", "fish", "console", "shell-session",
        "shellsession", "terminal", "command", "cmd",
    )
    val MARKUP: List<String> = listOf(
        "xml", "html", "htm", "xhtml", "svg", "markup", "vue", "jsx-html", "rss",
        "atom", "plist",
    )
    val JSON: List<String> = listOf("json", "jsonc", "geojson")
    val SQL: List<String> = listOf(
        "sql", "mysql", "postgres", "postgresql", "psql", "sqlite", "plsql",
        "tsql",
    )

    val ALL: Set<String> = listOf(KOTLIN, JAVA, C, PYTHON, JAVASCRIPT, RUST, GO, SHELL, MARKUP, JSON, SQL)
        .flatten().toSet()
}
