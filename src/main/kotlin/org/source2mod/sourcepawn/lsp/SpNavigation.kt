package org.source2mod.sourcepawn.lsp

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.source2mod.sourcepawn.isSourcePawn
import org.source2mod.sourcepawn.toolchain.SpToolchainService
import org.source2mod.sourcepawn.lang.SpTokenTypes as T

/**
 * Ctrl+click the platform does not do itself:
 * - `#include <x>` / `"x"` opens the file, resolved like spcomp does (the server has no definition for it);
 * - symbols in files outside the project content (the SDK includes): the 2024.3 LSP client only
 *   talks to the server about project files, so the definition request is sent here.
 */
class SpGotoDeclarationHandler : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(source: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val element = source ?: return null
        val file = element.containingFile?.virtualFile ?: return null
        if (!file.isSourcePawn()) return null
        return when (element.node.elementType) {
            T.INCLUDE_PATH -> include(element, file)
            T.IDENTIFIER -> identifier(element, file, offset, editor)
            else -> null
        }
    }

    /**
     * On a declaration (the definition is the identifier itself) Ctrl+click lists the usages,
     * like for C++; the platform's LSP client would just stay put. Elsewhere: the definition,
     * done here only for files outside the project content (the platform does the rest).
     */
    private fun identifier(element: PsiElement, file: VirtualFile, offset: Int, editor: Editor): Array<PsiElement>? {
        val inContent = ProjectFileIndex.getInstance(element.project).isInContent(file)
        val targets = definitions(element, file, offset) ?: return null
        val range = element.textRange
        val self = targets.any { (vf, off) -> vf == file && range.containsOffset(off) }
        if (self) return arrayOf(SpUsagesTarget(element, file, editor))
        if (inContent) return null
        return targets.mapNotNull { (vf, off) -> PsiManager.getInstance(element.project).findFile(vf)?.findElementAt(off) }
            .toTypedArray().takeIf { it.isNotEmpty() }
    }

    private fun include(element: PsiElement, file: VirtualFile): Array<PsiElement>? {
        val raw = element.text
        if (raw.length < 2) return null
        val name = raw.substring(1, raw.length - 1).trim().takeIf { it.isNotEmpty() } ?: return null
        val dirs = ArrayList<VirtualFile>()
        val here = file.parent
        if (raw.startsWith("\"") && here != null) dirs += here
        SpToolchainService.get(element.project).getOrSchedule {}?.let { tc ->
            val fs = LocalFileSystem.getInstance()
            tc.includeDirectories.mapNotNullTo(dirs) { fs.findFileByPath(tc.target.toHost(it.path).replace('\\', '/')) }
        }
        // spcomp also looks next to the source and in its include/ folder
        if (here != null) { dirs += here; here.findChild("include")?.let { dirs += it } }
        val target = dirs.asSequence()
            .flatMap { dir -> sequenceOf("$name.inc", "$name.sp", name).mapNotNull { dir.findFileByRelativePath(it) } }
            .firstOrNull { !it.isDirectory } ?: return null
        val psi = PsiManager.getInstance(element.project).findFile(target) ?: return null
        return arrayOf(psi)
    }

    /** textDocument/definition as (file, offset) pairs; null if the server has nothing. */
    private fun definitions(element: PsiElement, file: VirtualFile, offset: Int): List<Pair<VirtualFile, Int>>? {
        val server = SpLspUtil.server(element.project, file) ?: return null
        val document = SpLspUtil.document(file) ?: return null
        val params = DefinitionParams(SpLspUtil.docId(server, file), SpLspUtil.position(document, offset))
        val result = SpLspUtil.request(server) { it.textDocumentService.definition(params) } ?: return null
        val targets = if (result.isLeft) result.left.map { it.uri to it.range.start }
        else result.right.map { it.targetUri to it.targetSelectionRange.start }
        return targets.mapNotNull { (uri, pos) ->
            val vf = SpLspUtil.fileByUri(server, uri) ?: return@mapNotNull null
            val doc = SpLspUtil.document(vf) ?: return@mapNotNull null
            vf to SpLspUtil.offset(doc, pos)
        }.takeIf { it.isNotEmpty() }
    }
}

/**
 * Goto-declaration target for a declaration: navigating to it asks the server for the
 * references and lists them (one usage: straight there). Only navigate() does work, so
 * Ctrl+hover, which also collects targets, stays cheap.
 */
class SpUsagesTarget(private val declaration: PsiElement, private val file: VirtualFile, private val editor: Editor) : FakePsiElement() {
    private class Usage(val file: VirtualFile, val offset: Int, val line: Int, val text: String)

    override fun getParent(): PsiElement = declaration
    override fun getName(): String = declaration.text
    override fun getPresentableText() = "Usages of '${declaration.text}'"
    override fun canNavigate() = true

    override fun navigate(requestFocus: Boolean) {
        val project = declaration.project
        val name = declaration.text
        val offset = declaration.textRange.startOffset
        ApplicationManager.getApplication().executeOnPooledThread {
            val usages = ReadAction.compute<List<Usage>?, RuntimeException> { references(offset) }
            ApplicationManager.getApplication().invokeLater({
                when {
                    usages == null -> HintManager.getInstance().showErrorHint(editor, "SourcePawn language server is not ready")
                    usages.isEmpty() -> HintManager.getInstance().showInformationHint(editor, "No usages of '$name' found")
                    usages.size == 1 -> OpenFileDescriptor(project, usages[0].file, usages[0].offset).navigate(true)
                    else -> show(project, name, usages)
                }
            }, project.disposed)
        }
    }

    private fun references(offset: Int): List<Usage>? {
        val project = declaration.project
        val server = SpLspUtil.server(project, file) ?: return null
        val document = SpLspUtil.document(file) ?: return null
        val params = ReferenceParams(SpLspUtil.docId(server, file), SpLspUtil.position(document, offset), ReferenceContext(false))
        val locations = SpLspUtil.request(server, 10_000) { it.textDocumentService.references(params) } ?: return null
        return locations.mapNotNull { loc ->
            val vf = SpLspUtil.fileByUri(server, loc.uri) ?: return@mapNotNull null
            val doc = SpLspUtil.document(vf) ?: return@mapNotNull null
            val start = SpLspUtil.offset(doc, loc.range.start)
            if (vf == file && start == offset) return@mapNotNull null
            val line = doc.getLineNumber(start)
            val text = doc.getText(TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim()
            Usage(vf, start, line, text)
        }.distinctBy { it.file to it.offset }.sortedWith(compareBy({ it.file != file }, { it.file.path }, { it.offset }))
    }

    private fun show(project: com.intellij.openapi.project.Project, name: String, usages: List<Usage>) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(usages)
            .setTitle("Usages of '$name' (${usages.size})")
            .setRenderer(object : ColoredListCellRenderer<Usage>() {
                override fun customizeCellRenderer(list: JList<out Usage>, value: Usage, index: Int, selected: Boolean, hasFocus: Boolean) {
                    icon = value.file.fileType.icon
                    append(value.file.name)
                    append(" (${value.line + 1})  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append(value.text.take(120))
                }
            })
            .setNamerForFiltering { "${it.file.name} ${it.text}" }
            .setItemChosenCallback { OpenFileDescriptor(project, it.file, it.offset).navigate(true) }
            .createPopup()
            .showInBestPositionFor(editor)
    }
}
