package io.github.jambolo.transmogrifier.ui.workspace

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle

data class SyntaxColors(
    val comment: Color,
    val string: Color,
    val keyword: Color,
    val tag: Color,
    val number: Color
)

private enum class Token { COMMENT, STRING, KEYWORD, TAG, NUMBER }

private val blockComment = """/\*[\s\S]*?\*/"""
private val strings = """"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`(?:\\.|[^`\\])*`"""
private val number = """\b\d+(?:\.\d+)?\b"""
private val jsKeywords = listOf(
    "async", "await", "break", "case", "catch", "class", "const", "continue", "default", "else",
    "export", "false", "for", "from", "function", "if", "import", "in", "let", "new", "null", "of",
    "return", "switch", "this", "throw", "true", "try", "undefined", "var", "while"
).joinToString("|", """\b(?:""", """)\b""")

// Alternation order matters: comments and strings win over anything that starts inside them.
private val rules: Map<String, List<Pair<Token, String>>> = run {
    val js = listOf(
        Token.COMMENT to "$blockComment|//[^\\n]*",
        Token.STRING to strings,
        Token.KEYWORD to jsKeywords,
        Token.NUMBER to number
    )
    val css = listOf(
        Token.COMMENT to blockComment,
        Token.STRING to strings,
        Token.KEYWORD to """[\w-]+(?=\s*:[^{;]*;)""",
        Token.NUMBER to number
    )
    val html = listOf(
        Token.COMMENT to "<!--[\\s\\S]*?-->",
        Token.STRING to strings,
        Token.TAG to """</?[A-Za-z][\w-]*|/?>"""
    )
    mapOf("js" to js, "mjs" to js, "json" to js, "css" to css, "html" to html, "svg" to html)
}

private val patterns = rules.mapValues { (_, rules) ->
    Regex(rules.joinToString("|") { (_, pattern) -> "($pattern)" })
}

/** Lightweight regex highlighting for the read-only file viewer; not a parser. */
fun highlight(code: String, path: String, colors: SyntaxColors): AnnotatedString {
    val extension = path.substringAfterLast('.', "").lowercase()
    val pattern = patterns[extension] ?: return AnnotatedString(code)
    val tokens = rules.getValue(extension).map { it.first }
    return buildAnnotatedString {
        append(code)
        for (match in pattern.findAll(code)) {
            val group = match.groups.drop(1).indexOfFirst { it != null }
            val style = when (tokens[group]) {
                Token.COMMENT -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                Token.STRING -> SpanStyle(color = colors.string)
                Token.KEYWORD -> SpanStyle(color = colors.keyword)
                Token.TAG -> SpanStyle(color = colors.tag)
                Token.NUMBER -> SpanStyle(color = colors.number)
            }
            addStyle(style, match.range.first, match.range.last + 1)
        }
    }
}
