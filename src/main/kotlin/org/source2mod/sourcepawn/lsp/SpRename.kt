package org.source2mod.sourcepawn.lsp

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.rename.RenameHandler
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.source2mod.sourcepawn.isSourcePawn

/** Rename through textDocument/rename (the 2024.3 LSP API has no rename). */
class SpRenameHandler : RenameHandler {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext)
            ?: editor.virtualFile ?: return false
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return false
        return file.isSourcePawn() && SpLspUtil.server(project, file) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile?, dataContext: DataContext?) {
        val vf = file?.virtualFile ?: editor.virtualFile ?: return
        val server = SpLspUtil.server(project, vf) ?: return
        val doc = editor.document
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val pos = SpLspUtil.position(doc, editor.caretModel.offset)
        val id = SpLspUtil.docId(server, vf)

        val prepared = ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, Exception>({
            SpLspUtil.request(server) { it.textDocumentService.prepareRename(PrepareRenameParams(id, pos)) }?.let { r ->
                when {
                    r.isFirst -> doc.getText(range(doc, r.first))
                    r.isSecond -> r.second.placeholder ?: doc.getText(range(doc, r.second.range))
                    else -> null
                }
            }
        }, "Preparing rename", true, project)
        val current = prepared ?: wordAt(editor)
        if (current.isNullOrBlank()) {
            Messages.showErrorDialog(project, "Nothing to rename here.", "Rename")
            return
        }
        val newName = Messages.showInputDialog(project, "Rename '$current' to:", "Rename", null, current, null) ?: return
        if (newName == current) return

        val edit = ProgressManager.getInstance().runProcessWithProgressSynchronously<WorkspaceEdit?, Exception>({
            SpLspUtil.request(server, 10_000) { it.textDocumentService.rename(RenameParams(id, pos, newName)) }
        }, "Renaming", true, project)
        if (edit == null) {
            Messages.showErrorDialog(project, "The language server refused the rename.", "Rename")
            return
        }
        apply(project, server, edit)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val editor = dataContext?.let { CommonDataKeys.EDITOR.getData(it) } ?: return
        invoke(project, editor, dataContext.let { CommonDataKeys.PSI_FILE.getData(it) }, dataContext)
    }

    private fun apply(project: Project, server: com.intellij.platform.lsp.api.LspServer, edit: WorkspaceEdit) {
        val byFile = LinkedHashMap<VirtualFile, MutableList<TextEdit>>()
        edit.changes?.forEach { (uri, edits) ->
            SpLspUtil.fileByUri(server, uri)?.let { byFile.getOrPut(it) { mutableListOf() } += edits }
        }
        edit.documentChanges?.forEach { change ->
            val tde = change.left ?: return@forEach
            SpLspUtil.fileByUri(server, tde.textDocument.uri)?.let { byFile.getOrPut(it) { mutableListOf() } += tde.edits }
        }
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, "Rename", null, {
                for ((file, edits) in byFile) {
                    val doc = SpLspUtil.document(file) ?: continue
                    // apply bottom-up so earlier offsets stay valid
                    edits.map { range(doc, it.range) to it.newText }
                        .sortedByDescending { it.first.startOffset }
                        .forEach { (r, text) -> doc.replaceString(r.startOffset, r.endOffset, text) }
                }
            })
        }
    }

    private fun range(doc: com.intellij.openapi.editor.Document, r: org.eclipse.lsp4j.Range) =
        com.intellij.openapi.util.TextRange(SpLspUtil.offset(doc, r.start), SpLspUtil.offset(doc, r.end))

    private fun wordAt(editor: Editor): String? {
        val text = editor.document.charsSequence
        var s = editor.caretModel.offset
        var e = s
        while (s > 0 && (text[s - 1].isLetterOrDigit() || text[s - 1] == '_')) s--
        while (e < text.length && (text[e].isLetterOrDigit() || text[e] == '_')) e++
        return if (s < e) text.substring(s, e) else null
    }
}
