package org.source2mod.sourcepawn.lang

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import org.source2mod.sourcepawn.lang.SpTokenTypes as T

/**
 * Hand-written SourcePawn lexer. It only has to be good enough for highlighting,
 * brace matching and commenting; everything semantic (functions, methodmaps,
 * macros, ...) comes from the language server's semantic tokens.
 */
class SpLexer : LexerBase() {
    private var buf: CharSequence = ""
    private var end = 0
    private var tokStart = 0
    private var tokEnd = 0
    private var tokType: IElementType? = null
    private var state = 0 // STATE_* below, valid at tokStart

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        end = endOffset
        tokEnd = startOffset
        state = initialState
        advance()
    }

    override fun getState() = state
    override fun getTokenType() = tokType
    override fun getTokenStart() = tokStart
    override fun getTokenEnd() = tokEnd
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    override fun advance() {
        tokStart = tokEnd
        if (tokStart >= end) {
            tokType = null
            return
        }
        var i = tokStart
        val c = buf[i]
        // state carried to the token after this one
        var next = if (state == STATE_INCLUDE && c != '\n') STATE_INCLUDE else STATE_NORMAL

        when {
            c == '\n' || c.isWhitespace() -> {
                while (i < end && buf[i].isWhitespace()) {
                    if (buf[i] == '\n') next = STATE_NORMAL
                    i++
                }
                tokType = T.WHITE_SPACE
            }
            c == '/' && peek(i + 1) == '/' -> {
                while (i < end && buf[i] != '\n') i++
                tokType = T.LINE_COMMENT
            }
            c == '/' && peek(i + 1) == '*' -> {
                val doc = peek(i + 2) == '*' && peek(i + 3) != '/'
                i += 2
                while (i < end && !(buf[i] == '*' && peek(i + 1) == '/')) i++
                i = minOf(end, i + 2)
                tokType = if (doc) T.DOC_COMMENT else T.BLOCK_COMMENT
            }
            c == '#' -> {
                i++
                while (i < end && (buf[i] == ' ' || buf[i] == '\t')) i++
                val ws = i
                while (i < end && buf[i].isLetter()) i++
                val word = buf.subSequence(ws, i).toString()
                tokType = T.PREPROCESSOR
                if (word == "include" || word == "tryinclude") next = STATE_INCLUDE
            }
            state == STATE_INCLUDE && c == '<' -> {
                while (i < end && buf[i] != '>' && buf[i] != '\n') i++
                if (i < end && buf[i] == '>') i++
                tokType = T.INCLUDE_PATH
            }
            c == '"' -> {
                i = quoted(i, '"')
                tokType = if (state == STATE_INCLUDE) T.INCLUDE_PATH else T.STRING
            }
            c == '\'' -> {
                i = quoted(i, '\'')
                tokType = T.CHAR
            }
            c.isDigit() -> {
                i = number(i)
                tokType = T.NUMBER
            }
            c.isLetter() || c == '_' || c == '@' -> {
                i++
                while (i < end && (buf[i].isLetterOrDigit() || buf[i] == '_')) i++
                val word = buf.subSequence(tokStart, i).toString()
                tokType = when (word) {
                    in T.KEYWORDS -> T.KEYWORD
                    in T.TYPE_KEYWORDS -> T.TYPE_KEYWORD
                    in T.CONSTANT_KEYWORDS -> T.CONSTANT_KEYWORD
                    else -> T.IDENTIFIER
                }
            }
            else -> {
                i++
                tokType = when (c) {
                    '{' -> T.LBRACE
                    '}' -> T.RBRACE
                    '(' -> T.LPAREN
                    ')' -> T.RPAREN
                    '[' -> T.LBRACKET
                    ']' -> T.RBRACKET
                    ';' -> T.SEMICOLON
                    ',' -> T.COMMA
                    '.' -> if (peek(i) == '.' && peek(i + 1) == '.') { i += 2; T.OPERATOR } else T.DOT
                    in OPERATOR_CHARS -> {
                        while (i < end && buf[i] in OPERATOR_CHARS) i++
                        T.OPERATOR
                    }
                    else -> T.BAD_CHARACTER
                }
            }
        }
        tokEnd = i
        state = next
    }

    private fun peek(i: Int): Char = if (i < end) buf[i] else '\u0000'

    /** Skips a "..." or '...' literal; SourcePawn's escape char is a backslash. Stops at the line end. */
    private fun quoted(from: Int, quote: Char): Int {
        var i = from + 1
        while (i < end) {
            val ch = buf[i]
            if (ch == '\\' && i + 1 < end) {
                // a backslash before a newline continues the string on the next line
                i += 2
                continue
            }
            if (ch == '\n') return i
            i++
            if (ch == quote) return i
        }
        return i
    }

    private fun number(from: Int): Int {
        var i = from
        if (buf[i] == '0' && (peek(i + 1) == 'x' || peek(i + 1) == 'X' || peek(i + 1) == 'b' || peek(i + 1) == 'o')) {
            i += 2
            while (i < end && (buf[i].isLetterOrDigit() || buf[i] == '_')) i++
            return i
        }
        while (i < end && (buf[i].isDigit() || buf[i] == '_')) i++
        if (peek(i) == '.' && peek(i + 1).isDigit()) {
            i++
            while (i < end && (buf[i].isDigit() || buf[i] == '_')) i++
            if (peek(i) == 'e' || peek(i) == 'E') {
                var j = i + 1
                if (peek(j) == '-' || peek(j) == '+') j++
                if (peek(j).isDigit()) {
                    i = j
                    while (i < end && buf[i].isDigit()) i++
                }
            }
        }
        return i
    }

    companion object {
        const val STATE_NORMAL = 0
        const val STATE_INCLUDE = 1
        private const val OPERATOR_CHARS = "+-*/%=<>!&|^~?:"
    }
}
