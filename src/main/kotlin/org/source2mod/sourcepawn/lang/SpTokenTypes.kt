package org.source2mod.sourcepawn.lang

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import org.source2mod.sourcepawn.SpLanguage

class SpTokenType(debugName: String) : IElementType(debugName, SpLanguage)

object SpTokenTypes {
    @JvmField val FILE = IFileElementType(SpLanguage)

    @JvmField val WHITE_SPACE: IElementType = TokenType.WHITE_SPACE
    @JvmField val BAD_CHARACTER: IElementType = TokenType.BAD_CHARACTER

    @JvmField val LINE_COMMENT = SpTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = SpTokenType("BLOCK_COMMENT")
    @JvmField val DOC_COMMENT = SpTokenType("DOC_COMMENT")

    @JvmField val IDENTIFIER = SpTokenType("IDENTIFIER")
    @JvmField val KEYWORD = SpTokenType("KEYWORD")
    @JvmField val TYPE_KEYWORD = SpTokenType("TYPE_KEYWORD")
    @JvmField val CONSTANT_KEYWORD = SpTokenType("CONSTANT_KEYWORD")
    @JvmField val PREPROCESSOR = SpTokenType("PREPROCESSOR")
    @JvmField val INCLUDE_PATH = SpTokenType("INCLUDE_PATH")

    @JvmField val NUMBER = SpTokenType("NUMBER")
    @JvmField val STRING = SpTokenType("STRING")
    @JvmField val CHAR = SpTokenType("CHAR")

    @JvmField val LBRACE = SpTokenType("{")
    @JvmField val RBRACE = SpTokenType("}")
    @JvmField val LPAREN = SpTokenType("(")
    @JvmField val RPAREN = SpTokenType(")")
    @JvmField val LBRACKET = SpTokenType("[")
    @JvmField val RBRACKET = SpTokenType("]")
    @JvmField val SEMICOLON = SpTokenType(";")
    @JvmField val COMMA = SpTokenType(",")
    @JvmField val DOT = SpTokenType(".")
    @JvmField val OPERATOR = SpTokenType("OPERATOR")

    @JvmField val COMMENTS = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)
    @JvmField val STRINGS = TokenSet.create(STRING, CHAR, INCLUDE_PATH)
    @JvmField val WHITESPACES = TokenSet.create(WHITE_SPACE)

    val KEYWORDS = setOf(
        "acquire", "as", "assert", "break", "builtin", "case", "cast_to", "catch", "const",
        "continue", "decl", "default", "defined", "delete", "do", "else", "enum", "exit",
        "explicit", "finally", "for", "foreach", "forward", "funcenum", "functag", "function",
        "get", "goto", "if", "implicit", "import", "in", "interface", "let", "methodmap",
        "namespace", "native", "new", "operator", "package", "private", "property", "protected",
        "public", "readonly", "return", "sealed", "set", "sizeof", "static", "stock", "struct",
        "switch", "this", "throw", "try", "typedef", "typeof", "typeset", "union", "using",
        "var", "variant", "view_as", "virtual", "volatile", "while", "with", "enumstruct",
        "__nullable__", "__intrinsics__",
    )
    val TYPE_KEYWORDS = setOf(
        "void", "int", "float", "bool", "char", "any", "Function", "String", "Float",
        "int8", "int16", "int32", "int64", "intn", "uint8", "uint16", "uint32", "uint64", "uintn",
        "double", "object",
    )
    val CONSTANT_KEYWORDS = setOf("true", "false", "null", "INVALID_FUNCTION", "cellmax", "cellmin")
}
