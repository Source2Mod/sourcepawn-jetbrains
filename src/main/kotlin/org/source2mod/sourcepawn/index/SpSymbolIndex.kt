package org.source2mod.sourcepawn.index

import com.intellij.icons.AllIcons
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.IdFilter
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.source2mod.sourcepawn.SpFileType
import org.source2mod.sourcepawn.SpIncludeFileType
import org.source2mod.sourcepawn.lang.SpLexer
import java.io.DataInput
import java.io.DataOutput
import javax.swing.Icon
import org.source2mod.sourcepawn.lang.SpTokenTypes as T

enum class SpSymbolKind(val icon: Icon, val isType: Boolean = false) {
    METHODMAP(AllIcons.Nodes.Class, true),
    ENUM_STRUCT(AllIcons.Nodes.Class, true),
    ENUM(AllIcons.Nodes.Enum, true),
    TYPEDEF(AllIcons.Nodes.Type, true),
    FUNCTION(AllIcons.Nodes.Function),
    METHOD(AllIcons.Nodes.Method),
    PROPERTY(AllIcons.Nodes.Property),
    ENUM_MEMBER(AllIcons.Nodes.Constant),
    MACRO(AllIcons.Nodes.Constant),
}

data class SpSymbol(val name: String, val kind: SpSymbolKind, val offset: Int, val container: String?)

/**
 * Declarations of a .sp / .inc, read from the lexer's tokens: types (methodmap, enum,
 * enum struct, typedef / typeset), top-level functions, methods and properties of
 * methodmaps / enum structs, enum members and #defines. Enough for Go to Class /
 * Go to Symbol; sourcepawn-studio has no workspace/symbol.
 */
object SpDeclarations {
    private class Tok(val type: com.intellij.psi.tree.IElementType, val text: String, val start: Int)

    private val TYPE_KEYWORDS = mapOf(
        "methodmap" to SpSymbolKind.METHODMAP, "enumstruct" to SpSymbolKind.ENUM_STRUCT,
        "typedef" to SpSymbolKind.TYPEDEF, "typeset" to SpSymbolKind.TYPEDEF,
        "functag" to SpSymbolKind.TYPEDEF, "funcenum" to SpSymbolKind.TYPEDEF,
    )
    private val FUNCTION_INTRODUCERS = setOf("public", "stock", "native", "forward", "static")

    fun scan(text: CharSequence): List<SpSymbol> {
        val toks = ArrayList<Tok>()
        val lexer = SpLexer()
        lexer.start(text, 0, text.length, 0)
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != T.WHITE_SPACE && type !in T.COMMENTS) toks += Tok(type, text.subSequence(lexer.tokenStart, lexer.tokenEnd).toString(), lexer.tokenStart)
            lexer.advance()
        }

        val out = ArrayList<SpSymbol>()
        var depth = 0
        // type whose body is open at depth 1 (methodmap / enum struct / enum)
        var body: Pair<String, SpSymbolKind>? = null
        var pending: Pair<String, SpSymbolKind>? = null
        var expectEnumMember = false
        for ((i, t) in toks.withIndex()) {
            val prev = toks.getOrNull(i - 1)
            val next = toks.getOrNull(i + 1)
            when (t.type) {
                T.LBRACE -> {
                    depth++
                    if (depth == 1) { body = pending; expectEnumMember = body?.second == SpSymbolKind.ENUM }
                    pending = null
                }
                T.RBRACE -> {
                    depth = maxOf(0, depth - 1)
                    if (depth == 0) { body = null; expectEnumMember = false }
                }
                T.SEMICOLON -> if (depth == 0) pending = null
                T.COMMA -> if (depth == 1 && body?.second == SpSymbolKind.ENUM) expectEnumMember = true
                T.PREPROCESSOR -> if (t.text == "#define" && next?.type == T.IDENTIFIER) out += SpSymbol(next.text, SpSymbolKind.MACRO, next.start, null)
                T.IDENTIFIER -> {
                    if (prev?.type == T.PREPROCESSOR) continue
                    if (depth == 1 && body?.second == SpSymbolKind.ENUM) {
                        if (expectEnumMember) out += SpSymbol(t.text, SpSymbolKind.ENUM_MEMBER, t.start, body!!.first)
                        expectEnumMember = false
                        continue
                    }
                    val kind = typeKind(toks, i)
                    if (kind != null && depth == 0) {
                        out += SpSymbol(t.text, kind, t.start, null)
                        pending = t.text to kind
                        continue
                    }
                    if (depth == 1 && body != null && (prev?.type == T.IDENTIFIER || prev?.type == T.TYPE_KEYWORD) &&
                        toks.getOrNull(i - 2)?.text == "property") {
                        out += SpSymbol(t.text, SpSymbolKind.PROPERTY, t.start, body!!.first)
                        continue
                    }
                    if (next?.type == T.LPAREN && isDeclarationPrefix(prev)) {
                        when {
                            depth == 0 -> out += SpSymbol(t.text, SpSymbolKind.FUNCTION, t.start, null)
                            depth == 1 && body != null -> out += SpSymbol(t.text, SpSymbolKind.METHOD, t.start, body!!.first)
                        }
                    }
                }
            }
        }
        return out
    }

    /** `methodmap X`, `enum X`, `enum struct X`, `typedef X`, ... */
    private fun typeKind(toks: List<Tok>, i: Int): SpSymbolKind? {
        val prev = toks.getOrNull(i - 1) ?: return null
        if (prev.type != T.KEYWORD) return null
        TYPE_KEYWORDS[prev.text]?.let { return it }
        if (prev.text == "struct" && toks.getOrNull(i - 2)?.text == "enum") return SpSymbolKind.ENUM_STRUCT
        if (prev.text == "enum") return SpSymbolKind.ENUM
        return null
    }

    private fun isDeclarationPrefix(prev: Tok?): Boolean = prev != null && (
        prev.type == T.TYPE_KEYWORD || prev.type == T.IDENTIFIER || prev.type == T.RBRACKET ||
            (prev.type == T.KEYWORD && prev.text in FUNCTION_INTRODUCERS))
}

class SpSymbolIndex : FileBasedIndexExtension<String, List<SpSymbol>>() {
    override fun getName(): ID<String, List<SpSymbol>> = NAME
    override fun getVersion() = 1
    override fun dependsOnFileContent() = true
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getInputFilter() = DefaultFileTypeSpecificInputFilter(SpFileType, SpIncludeFileType)

    override fun getIndexer() = DataIndexer<String, List<SpSymbol>, FileContent> { content ->
        SpDeclarations.scan(content.contentAsText).groupBy { it.name }
    }

    override fun getValueExternalizer() = object : DataExternalizer<List<SpSymbol>> {
        override fun save(out: DataOutput, value: List<SpSymbol>) {
            out.writeInt(value.size)
            for (s in value) {
                out.writeUTF(s.name)
                out.writeByte(s.kind.ordinal)
                out.writeInt(s.offset)
                out.writeUTF(s.container ?: "")
            }
        }

        override fun read(input: DataInput): List<SpSymbol> = List(input.readInt()) {
            SpSymbol(input.readUTF(), SpSymbolKind.entries[input.readByte().toInt()], input.readInt(), input.readUTF().ifEmpty { null })
        }
    }

    companion object {
        @JvmField val NAME = ID.create<String, List<SpSymbol>>("sourcepawn.symbols")
    }
}

class SpSymbolItem(private val project: Project, private val file: VirtualFile, val symbol: SpSymbol) : NavigationItem {
    override fun getName() = symbol.name
    override fun navigate(requestFocus: Boolean) = OpenFileDescriptor(project, file, symbol.offset).navigate(requestFocus)
    override fun canNavigate() = file.isValid
    override fun canNavigateToSource() = file.isValid
    override fun getPresentation() = object : ItemPresentation {
        override fun getPresentableText() = symbol.container?.let { "${symbol.name} ($it)" } ?: symbol.name
        override fun getLocationString() = file.name
        override fun getIcon(unused: Boolean) = symbol.kind.icon
    }
}

/** Ctrl+Alt+Shift+N (Symbols): every declaration. */
open class SpGotoSymbolContributor : ChooseByNameContributorEx {
    protected open fun accepts(kind: SpSymbolKind) = true

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        FileBasedIndex.getInstance().processAllKeys(SpSymbolIndex.NAME, processor, scope, filter)
    }

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
        val project = parameters.project
        FileBasedIndex.getInstance().processValues(SpSymbolIndex.NAME, name, null, { file, symbols ->
            symbols.filter { accepts(it.kind) }.all { processor.process(SpSymbolItem(project, file, it)) }
        }, parameters.searchScope, parameters.idFilter)
    }
}

/** Ctrl+N (Classes): methodmaps, enum structs, enums, typedefs. */
class SpGotoClassContributor : SpGotoSymbolContributor(), GotoClassContributor {
    override fun accepts(kind: SpSymbolKind) = kind.isType
    override fun getQualifiedName(item: NavigationItem): String? = item.name
    override fun getQualifiedNameSeparator() = "."
}
