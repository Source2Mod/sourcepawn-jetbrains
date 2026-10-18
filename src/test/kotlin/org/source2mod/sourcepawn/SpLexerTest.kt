package org.source2mod.sourcepawn

import com.intellij.psi.tree.IElementType
import org.junit.Assert.assertEquals
import org.junit.Test
import org.source2mod.sourcepawn.lang.SpLexer
import org.source2mod.sourcepawn.lang.SpTokenTypes as T

class SpLexerTest {
    private fun lex(s: String): List<Pair<IElementType, String>> {
        val l = SpLexer()
        l.start(s, 0, s.length, 0)
        val out = ArrayList<Pair<IElementType, String>>()
        while (l.tokenType != null) {
            if (l.tokenType != T.WHITE_SPACE) out += l.tokenType!! to s.substring(l.tokenStart, l.tokenEnd)
            l.advance()
        }
        return out
    }

    @Test fun include() {
        assertEquals(
            listOf(T.PREPROCESSOR to "#include", T.INCLUDE_PATH to "<source2mod>", T.PREPROCESSOR to "#define",
                T.IDENTIFIER to "X", T.OPERATOR to "<", T.NUMBER to "1"),
            lex("#include <source2mod>\n#define X <1"),
        )
    }

    @Test fun declarations() {
        assertEquals(
            listOf(T.KEYWORD to "public", T.TYPE_KEYWORD to "void", T.IDENTIFIER to "OnPluginStart", T.LPAREN to "(",
                T.RPAREN to ")", T.LBRACE to "{", T.TYPE_KEYWORD to "float", T.IDENTIFIER to "f", T.OPERATOR to "=",
                T.NUMBER to "1.5e3", T.SEMICOLON to ";", T.TYPE_KEYWORD to "char", T.IDENTIFIER to "c", T.OPERATOR to "=",
                T.CHAR to "'\\''", T.SEMICOLON to ";", T.RBRACE to "}"),
            lex("public void OnPluginStart() { float f = 1.5e3; char c = '\\''; }"),
        )
    }

    @Test fun commentsAndStrings() {
        assertEquals(
            listOf(T.DOC_COMMENT to "/** doc */", T.BLOCK_COMMENT to "/**/", T.LINE_COMMENT to "// x",
                T.STRING to "\"a\\\"b\"", T.NUMBER to "0x1F", T.CONSTANT_KEYWORD to "null"),
            lex("/** doc */ /**/ // x\n\"a\\\"b\" 0x1F null"),
        )
    }

    @Test fun unterminated() {
        // must not hang or run past the end
        assertEquals(listOf(T.BLOCK_COMMENT to "/* x"), lex("/* x"))
        assertEquals(listOf(T.STRING to "\"abc"), lex("\"abc"))
    }
}

class SpParameterIndexTest {
    private val h = org.source2mod.sourcepawn.lsp.SpParameterInfoHandler()
    private fun idx(s: String) = h.argumentIndex(s.replace("|", ""), s.indexOf('|'))

    @Test fun indexes() {
        assertEquals(0, idx("Foo(|"))
        assertEquals(1, idx("Foo(\"a, b\", |)"))
        assertEquals(3, idx("Foo(a, Bar(1, 2), x[1], |"))
        assertEquals(1, idx("Foo(',', |"))
        assertEquals(-1, idx("Foo(a); |"))
        assertEquals(-1, idx("x = {1, |"))
    }
}
