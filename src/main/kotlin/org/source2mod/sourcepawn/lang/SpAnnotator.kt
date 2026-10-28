package org.source2mod.sourcepawn.lang

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.source2mod.sourcepawn.lang.SpTokenTypes as T

/**
 * Colors identifiers from the tokens around them, the way the VS Code grammar does:
 * the language server's semantic tokens only cover symbols of the file itself, so
 * calls into includes (natives, methodmaps, their methods and properties) would
 * otherwise stay plain text.
 */
object SpIdentifierColors {
    private val TYPE_INTRODUCERS = setOf("methodmap", "enum", "struct", "enumstruct", "typedef", "typeset", "functag", "funcenum")
    private val FUNCTION_INTRODUCERS = setOf("public", "stock", "native", "forward", "static")
    private val MACRO_NAME = Regex("[A-Z][A-Z0-9_]*[A-Z0-9]")

    /** prev / next: the significant tokens around the identifier (no whitespace or comments). */
    fun classify(name: String, prevType: IElementType?, prevText: String?, nextType: IElementType?): TextAttributesKey? {
        if (prevType == T.DOT) return if (nextType == T.LPAREN) SpColors.METHOD_CALL else SpColors.FIELD
        if (prevType == T.KEYWORD && prevText in TYPE_INTRODUCERS) return SpColors.TYPE_NAME
        if (nextType == T.LPAREN) {
            val declaration = prevType == T.TYPE_KEYWORD || prevType == T.IDENTIFIER || prevType == T.RBRACKET ||
                (prevType == T.KEYWORD && prevText in FUNCTION_INTRODUCERS)
            return if (declaration) SpColors.FUNCTION_DECLARATION else SpColors.FUNCTION_CALL
        }
        if (MACRO_NAME.matches(name)) return SpColors.MACRO
        if (name.startsWith("g_")) return SpColors.GLOBAL_VARIABLE
        if (name[0].isUpperCase()) {
            // Methodmap.Static(...), Type variable, view_as<Type>(...)
            if (nextType == T.DOT || nextType == T.IDENTIFIER) return SpColors.TYPE_NAME
            if (prevType == T.OPERATOR && prevText == "<") return SpColors.TYPE_NAME
        }
        return null
    }

    /** Token types the highlighting lexer gives classified identifiers, one per color. */
    val TOKENS: Map<TextAttributesKey, IElementType> = listOf(
        SpColors.FUNCTION_DECLARATION, SpColors.FUNCTION_CALL, SpColors.METHOD_CALL, SpColors.MACRO,
        SpColors.TYPE_NAME, SpColors.FIELD, SpColors.GLOBAL_VARIABLE,
    ).associateWith { SpTokenType("IDENTIFIER:" + it.externalName) }
}

class SpAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.node.elementType != T.IDENTIFIER) return
        val prev = PsiTreeUtil.skipWhitespacesAndCommentsBackward(element)
        val next = PsiTreeUtil.skipWhitespacesAndCommentsForward(element)
        val key = SpIdentifierColors.classify(element.text, prev?.node?.elementType, prev?.text, next?.node?.elementType) ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).textAttributes(key).create()
    }
}

/**
 * [SpLexer] with identifiers retyped by [SpIdentifierColors], for the editor highlighter
 * and the code blocks of quick documentation (LSP hover), which only use the lexer.
 * Lexes the whole range up front to see the token after each identifier.
 */
class SpHighlightingLexer : LexerBase() {
    private class Tok(val start: Int, val end: Int, var type: IElementType, val state: Int)

    private var buf: CharSequence = ""
    private var end = 0
    private var toks = ArrayList<Tok>()
    private var i = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        end = endOffset
        val inner = SpLexer()
        inner.start(buffer, startOffset, endOffset, initialState)
        val list = ArrayList<Tok>()
        while (true) {
            val type = inner.tokenType ?: break
            list += Tok(inner.tokenStart, inner.tokenEnd, type, inner.state)
            inner.advance()
        }
        var prev: Tok? = null
        for ((n, t) in list.withIndex()) {
            if (t.type in SKIPPED) continue
            if (t.type == T.IDENTIFIER) {
                val next = list.subList(n + 1, list.size).firstOrNull { it.type !in SKIPPED }
                val key = SpIdentifierColors.classify(text(t), prev?.type, prev?.let(::text), next?.type)
                if (key != null) SpIdentifierColors.TOKENS[key]?.let { t.type = it }
                prev = Tok(t.start, t.end, T.IDENTIFIER, t.state)
            } else {
                prev = t
            }
        }
        toks = list
        i = 0
    }

    private fun text(t: Tok) = buf.subSequence(t.start, t.end).toString()

    override fun getState() = toks.getOrNull(i)?.state ?: 0
    override fun getTokenType() = toks.getOrNull(i)?.type
    override fun getTokenStart() = toks.getOrNull(i)?.start ?: end
    override fun getTokenEnd() = toks.getOrNull(i)?.end ?: end
    override fun advance() { i++ }
    override fun getBufferSequence() = buf
    override fun getBufferEnd() = end

    companion object {
        private val SKIPPED = setOf(T.WHITE_SPACE, T.LINE_COMMENT, T.BLOCK_COMMENT, T.DOC_COMMENT)
    }
}
