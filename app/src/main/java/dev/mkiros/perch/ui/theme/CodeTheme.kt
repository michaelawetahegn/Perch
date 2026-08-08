package dev.mkiros.perch.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The only meanings a code block is allowed to colour (U11).
 *
 * Deliberately five, and deliberately here rather than in the tokeniser: this is the same
 * bargain `SpanStyle` strikes for prose in DESIGN.md §8 — the source gets a vocabulary,
 * the theme owns what it looks like. A highlighter with twenty token classes is a
 * highlighter whose author has to pick twenty colours that work on two backgrounds, and
 * the result is confetti. Five is enough to read a program by.
 */
enum class CodeToken {
    /** Reserved words, and the literals the language itself defines (`true`, `nil`). */
    Keyword,

    /** String, char and template literals, quotes included. */
    StringLit,

    Number,

    Comment,

    /**
     * Everything that talks *about* the code rather than being it: a C preprocessor line,
     * a Kotlin annotation, a shell variable, an XML attribute name, a JSON key.
     */
    Meta,
}

/**
 * One colour per [CodeToken], for one background.
 *
 * These are the only hues in the app that are not drawn from the `#3F6E5A` tonal palettes,
 * and they have to be: three of the five must be told apart at 13sp by someone skimming,
 * which a single hue's tone stops cannot do. They stay as close to the palette as that
 * allows — keyword is the primary green, string is the brand amber (§2's tertiary), and
 * only number and meta reach outside for a violet and a blue.
 */
@Immutable
data class CodeColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val meta: Color,
) {
    fun of(token: CodeToken): Color = when (token) {
        CodeToken.Keyword -> keyword
        CodeToken.StringLit -> string
        CodeToken.Number -> number
        CodeToken.Comment -> comment
        CodeToken.Meta -> meta
    }
}

/** On `surfaceContainer` at tone 94 — every hue darkened until it holds against near-white. */
internal val PerchCodeColorsLight = CodeColors(
    keyword = Color(0xFF00614A),
    string = Color(0xFF7A4E00),
    number = Color(0xFF553C93),
    comment = Color(0xFF5A6B63),
    meta = Color(0xFF14548C),
)

/** On `surfaceContainer` at tone 12 — the night-reading case §2 assumes is the default. */
internal val PerchCodeColorsDark = CodeColors(
    keyword = Color(0xFF71DAAF),
    string = Color(0xFFEDBF48),
    number = Color(0xFFC6B4F2),
    comment = Color(0xFF8A9C93),
    meta = Color(0xFF93C8EC),
)

/**
 * Provided by [PerchTheme] so a code block never has to ask which theme it is in. Static
 * because it changes only with the theme, and a code block re-tokenising on recomposition
 * is the one thing in the article renderer that is not cheap.
 */
val LocalCodeColors = staticCompositionLocalOf { PerchCodeColorsDark }
