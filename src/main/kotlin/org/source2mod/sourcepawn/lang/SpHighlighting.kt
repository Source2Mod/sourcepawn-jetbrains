package org.source2mod.sourcepawn.lang

import com.intellij.codeInsight.editorActions.SimpleTokenSetQuoteHandler
import com.intellij.lang.BracePair
import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.lang.PairedBraceMatcher
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as D
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import org.source2mod.sourcepawn.SpIcons
import org.source2mod.sourcepawn.lang.SpTokenTypes as T

object SpColors {
    val KEYWORD = createTextAttributesKey("SP_KEYWORD", D.KEYWORD)
    val TYPE = createTextAttributesKey("SP_TYPE", D.KEYWORD)
    val CONSTANT = createTextAttributesKey("SP_CONSTANT", D.CONSTANT)
    val PREPROCESSOR = createTextAttributesKey("SP_PREPROCESSOR", D.METADATA)
    val INCLUDE_PATH = createTextAttributesKey("SP_INCLUDE_PATH", D.STRING)
    val NUMBER = createTextAttributesKey("SP_NUMBER", D.NUMBER)
    val STRING = createTextAttributesKey("SP_STRING", D.STRING)
    val CHAR = createTextAttributesKey("SP_CHAR", D.STRING)
    val LINE_COMMENT = createTextAttributesKey("SP_LINE_COMMENT", D.LINE_COMMENT)
    val BLOCK_COMMENT = createTextAttributesKey("SP_BLOCK_COMMENT", D.BLOCK_COMMENT)
    val DOC_COMMENT = createTextAttributesKey("SP_DOC_COMMENT", D.DOC_COMMENT)
    val BRACES = createTextAttributesKey("SP_BRACES", D.BRACES)
    val PARENS = createTextAttributesKey("SP_PARENS", D.PARENTHESES)
    val BRACKETS = createTextAttributesKey("SP_BRACKETS", D.BRACKETS)
    val SEMICOLON = createTextAttributesKey("SP_SEMICOLON", D.SEMICOLON)
    val COMMA = createTextAttributesKey("SP_COMMA", D.COMMA)
    val DOT = createTextAttributesKey("SP_DOT", D.DOT)
    val OPERATOR = createTextAttributesKey("SP_OPERATOR", D.OPERATION_SIGN)
    val IDENTIFIER = createTextAttributesKey("SP_IDENTIFIER", D.IDENTIFIER)
}

class SpSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer() = SpLexer()

    override fun getTokenHighlights(type: IElementType?): Array<TextAttributesKey> = pack(MAP[type])

    companion object {
        private val MAP: Map<IElementType, TextAttributesKey> = mapOf(
            T.KEYWORD to SpColors.KEYWORD,
            T.TYPE_KEYWORD to SpColors.TYPE,
            T.CONSTANT_KEYWORD to SpColors.CONSTANT,
            T.PREPROCESSOR to SpColors.PREPROCESSOR,
            T.INCLUDE_PATH to SpColors.INCLUDE_PATH,
            T.NUMBER to SpColors.NUMBER,
            T.STRING to SpColors.STRING,
            T.CHAR to SpColors.CHAR,
            T.LINE_COMMENT to SpColors.LINE_COMMENT,
            T.BLOCK_COMMENT to SpColors.BLOCK_COMMENT,
            T.DOC_COMMENT to SpColors.DOC_COMMENT,
            T.LBRACE to SpColors.BRACES, T.RBRACE to SpColors.BRACES,
            T.LPAREN to SpColors.PARENS, T.RPAREN to SpColors.PARENS,
            T.LBRACKET to SpColors.BRACKETS, T.RBRACKET to SpColors.BRACKETS,
            T.SEMICOLON to SpColors.SEMICOLON,
            T.COMMA to SpColors.COMMA,
            T.DOT to SpColors.DOT,
            T.OPERATOR to SpColors.OPERATOR,
            T.IDENTIFIER to SpColors.IDENTIFIER,
        )
    }
}

class SpSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?) = SpSyntaxHighlighter()
}

class SpBraceMatcher : PairedBraceMatcher {
    override fun getPairs() = PAIRS
    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?) = true
    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int) = openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(T.LBRACE, T.RBRACE, true),
            BracePair(T.LPAREN, T.RPAREN, false),
            BracePair(T.LBRACKET, T.RBRACKET, false),
        )
    }
}

class SpCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix() = "//"
    override fun getBlockCommentPrefix() = "/*"
    override fun getBlockCommentSuffix() = "*/"
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun getLineCommentTokenType(): IElementType = T.LINE_COMMENT
    override fun getBlockCommentTokenType(): IElementType = T.BLOCK_COMMENT
    override fun getDocumentationCommentTokenType(): IElementType = T.DOC_COMMENT
    override fun getDocumentationCommentPrefix() = "/**"
    override fun getDocumentationCommentLinePrefix() = "*"
    override fun getDocumentationCommentSuffix() = "*/"
    override fun isDocumentationComment(element: PsiComment?) = element?.tokenType == T.DOC_COMMENT
}

class SpQuoteHandler : SimpleTokenSetQuoteHandler(TokenSet.create(T.STRING, T.CHAR))

class SpColorSettingsPage : ColorSettingsPage {
    override fun getIcon() = SpIcons.FILE
    override fun getHighlighter() = SpSyntaxHighlighter()
    override fun getDisplayName() = "SourcePawn"
    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY
    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null
    override fun getAttributeDescriptors() = arrayOf(
        AttributesDescriptor("Keyword", SpColors.KEYWORD),
        AttributesDescriptor("Built-in type", SpColors.TYPE),
        AttributesDescriptor("Constant (true/false/null)", SpColors.CONSTANT),
        AttributesDescriptor("Preprocessor directive", SpColors.PREPROCESSOR),
        AttributesDescriptor("Include path", SpColors.INCLUDE_PATH),
        AttributesDescriptor("Number", SpColors.NUMBER),
        AttributesDescriptor("String", SpColors.STRING),
        AttributesDescriptor("Character", SpColors.CHAR),
        AttributesDescriptor("Comments//Line comment", SpColors.LINE_COMMENT),
        AttributesDescriptor("Comments//Block comment", SpColors.BLOCK_COMMENT),
        AttributesDescriptor("Comments//Doc comment", SpColors.DOC_COMMENT),
        AttributesDescriptor("Braces and Operators//Braces", SpColors.BRACES),
        AttributesDescriptor("Braces and Operators//Parentheses", SpColors.PARENS),
        AttributesDescriptor("Braces and Operators//Brackets", SpColors.BRACKETS),
        AttributesDescriptor("Braces and Operators//Semicolon", SpColors.SEMICOLON),
        AttributesDescriptor("Braces and Operators//Comma", SpColors.COMMA),
        AttributesDescriptor("Braces and Operators//Dot", SpColors.DOT),
        AttributesDescriptor("Braces and Operators//Operator", SpColors.OPERATOR),
        AttributesDescriptor("Identifier", SpColors.IDENTIFIER),
    )

    override fun getDemoText() = """
        #include <sourcemod>
        #pragma newdecls required

        /** Plugin info. */
        public Plugin myinfo = {
            name = "Example",
            version = "1.0.0",
        };

        // Line comment
        public void OnPluginStart()
        {
            int count = 0x1F + 2;
            float ratio = 1.5e3;
            char name[MAX_NAME_LENGTH] = "player";
            if (count > 0 && name[0] != '\0')
                PrintToServer("count %d %f", count, ratio);
        }
    """.trimIndent()
}
