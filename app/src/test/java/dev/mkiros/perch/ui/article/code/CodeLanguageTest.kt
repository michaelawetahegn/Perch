package dev.mkiros.perch.ui.article.code

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Which highlighter a block gets (U11).
 *
 * Two rules carry the whole file. A **declared** language is the author's word and is
 * final — including when it declares a language we do not support, which means "leave it
 * alone", not "guess again". Sniffing only ever runs when nothing was declared, because
 * `language-plaintext` on a block of C is a deliberate choice by whoever wrote the post.
 */
class CodeLanguageTest {

    @Test
    fun `a declared language wins over anything the code looks like`() {
        assertThat(CodeLanguage.of("python", "int main(void) { return 0; }"))
            .isEqualTo(CodeLanguage.Python)
    }

    @Test
    fun `the common spellings of each language all resolve`() {
        val expected = mapOf(
            "kotlin" to CodeLanguage.Kotlin, "kt" to CodeLanguage.Kotlin,
            "java" to CodeLanguage.Java,
            "c" to CodeLanguage.C, "cpp" to CodeLanguage.C, "c++" to CodeLanguage.C,
            "h" to CodeLanguage.C, "objc" to CodeLanguage.C,
            "py" to CodeLanguage.Python, "python3" to CodeLanguage.Python,
            "js" to CodeLanguage.JavaScript, "typescript" to CodeLanguage.JavaScript,
            "tsx" to CodeLanguage.JavaScript,
            "rs" to CodeLanguage.Rust, "rust" to CodeLanguage.Rust,
            "go" to CodeLanguage.Go, "golang" to CodeLanguage.Go,
            "sh" to CodeLanguage.Shell, "bash" to CodeLanguage.Shell,
            "console" to CodeLanguage.Shell,
            "html" to CodeLanguage.Markup, "xml" to CodeLanguage.Markup,
            "svg" to CodeLanguage.Markup,
            "json" to CodeLanguage.Json,
            "sql" to CodeLanguage.Sql, "postgresql" to CodeLanguage.Sql,
        )

        for ((id, language) in expected) {
            assertThat(CodeLanguage.of(id, "")).isEqualTo(language)
        }
    }

    @Test
    fun `case and surrounding whitespace in the declaration do not matter`() {
        assertThat(CodeLanguage.of("  Kotlin ", "")).isEqualTo(CodeLanguage.Kotlin)
    }

    @Test
    fun `a language we do not support is left unstyled rather than guessed at`() {
        assertThat(CodeLanguage.of("plaintext", "fun main() { val x = 1 }"))
            .isEqualTo(CodeLanguage.Plain)
        assertThat(CodeLanguage.of("haskell", "fun main() { val x = 1 }"))
            .isEqualTo(CodeLanguage.Plain)
    }

    @Test
    fun `an undeclared block is sniffed from its own shape`() {
        val samples = mapOf(
            "fun main() {\n    val greeting = \"hi\"\n}" to CodeLanguage.Kotlin,
            "#include <stdio.h>\nint main(void) { return 0; }" to CodeLanguage.C,
            "def total(rows):\n    return sum(rows)" to CodeLanguage.Python,
            "fn main() {\n    let x: u32 = 1;\n}" to CodeLanguage.Rust,
            "package main\n\nfunc main() {\n}" to CodeLanguage.Go,
            "public class Main {\n    System.out.println(1);\n}" to CodeLanguage.Java,
            "const add = (a, b) => a + b;" to CodeLanguage.JavaScript,
            "SELECT id FROM users WHERE id = 1;" to CodeLanguage.Sql,
            "#!/bin/sh\nrm -rf /tmp/x" to CodeLanguage.Shell,
            "<html>\n  <body>hi</body>\n</html>" to CodeLanguage.Markup,
            "{\n  \"name\": \"perch\"\n}" to CodeLanguage.Json,
        )

        for ((code, language) in samples) {
            assertThat(CodeLanguage.of(null, code)).isEqualTo(language)
        }
    }

    @Test
    fun `prose that is not code at all sniffs to plain`() {
        assertThat(CodeLanguage.of(null, "The quick brown fox jumped over the lazy dog."))
            .isEqualTo(CodeLanguage.Plain)
        assertThat(CodeLanguage.of(null, "")).isEqualTo(CodeLanguage.Plain)
    }
}
