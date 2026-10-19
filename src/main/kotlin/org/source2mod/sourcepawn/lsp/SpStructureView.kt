package org.source2mod.sourcepawn.lsp

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.SymbolKind
import javax.swing.Icon

/** Structure view from textDocument/documentSymbol. */
class SpStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder =
        object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                object : StructureViewModelBase(psiFile, editor, FileElement(psiFile)) {
                    override fun getSorters() = arrayOf(Sorter.ALPHA_SORTER)
                }

            override fun isRootNodeShown() = false
        }
}

private class FileElement(private val file: PsiFile) : StructureViewTreeElement {
    override fun getValue() = file
    override fun getPresentation(): ItemPresentation = file.presentation ?: Presentation(file.name, null, null)
    override fun getChildren(): Array<TreeElement> {
        val vf = file.virtualFile ?: return emptyArray()
        val server = SpLspUtil.server(file.project, vf) ?: return emptyArray()
        val result = SpLspUtil.request(server) {
            it.textDocumentService.documentSymbol(DocumentSymbolParams(SpLspUtil.docId(server, vf)))
        } ?: return emptyArray()
        val symbols = result.mapNotNull { it.right }
        return symbols.map { SymbolElement(file, it) }.toTypedArray()
    }

    override fun navigate(requestFocus: Boolean) = file.navigate(requestFocus)
    override fun canNavigate() = true
    override fun canNavigateToSource() = true
}

private class SymbolElement(private val file: PsiFile, private val symbol: DocumentSymbol) : StructureViewTreeElement {
    override fun getValue() = symbol
    override fun getPresentation(): ItemPresentation = Presentation(symbol.name, symbol.detail, icon(symbol.kind))
    override fun getChildren(): Array<TreeElement> =
        (symbol.children ?: emptyList()).map { SymbolElement(file, it) }.toTypedArray()

    override fun navigate(requestFocus: Boolean) {
        val vf = file.virtualFile ?: return
        val doc = SpLspUtil.document(vf) ?: return
        OpenFileDescriptor(file.project, vf, SpLspUtil.offset(doc, symbol.selectionRange.start)).navigate(requestFocus)
    }

    override fun canNavigate() = true
    override fun canNavigateToSource() = true

    private fun icon(kind: SymbolKind?): Icon = when (kind) {
        SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor -> AllIcons.Nodes.Method
        SymbolKind.Class, SymbolKind.Struct, SymbolKind.Interface -> AllIcons.Nodes.Class
        SymbolKind.Enum -> AllIcons.Nodes.Enum
        SymbolKind.EnumMember, SymbolKind.Constant -> AllIcons.Nodes.Constant
        SymbolKind.Property -> AllIcons.Nodes.Property
        SymbolKind.Field -> AllIcons.Nodes.Field
        SymbolKind.Variable -> AllIcons.Nodes.Variable
        SymbolKind.TypeParameter -> AllIcons.Nodes.Type
        else -> AllIcons.Nodes.Tag
    }
}

private class Presentation(private val text: String, private val location: String?, private val icon: Icon?) : ItemPresentation {
    override fun getPresentableText() = text
    override fun getLocationString() = location
    override fun getIcon(unused: Boolean) = icon
}
