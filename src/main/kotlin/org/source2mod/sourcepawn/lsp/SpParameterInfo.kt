package org.source2mod.sourcepawn.lsp

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureHelpParams
import org.eclipse.lsp4j.SignatureInformation

/** Parameter info (Ctrl+P and after typing '(') from textDocument/signatureHelp. */
class SpParameterInfoHandler : ParameterInfoHandler<PsiElement, SignatureInformation> {
    private var active = 0

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val help = request(context.file, context.editor.document, context.offset) ?: return null
        if (help.signatures.isNullOrEmpty()) return null
        context.itemsToShow = help.signatures.toTypedArray()
        active = help.activeParameter ?: 0
        // the PSI is flat and leaves are replaced on every keystroke, which would
        // close the hint: the file is the one owner that stays valid
        return context.file
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) =
        context.showHint(element, context.offset, this)

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? = context.file

    // runs on the EDT, where a synchronous LSP request is not possible: the active
    // parameter is the number of top-level commas since the call's '('
    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        val index = argumentIndex(context.editor.document.charsSequence, context.offset)
        if (index < 0) {
            context.removeHint()
            return
        }
        context.setCurrentParameter(index)
    }

    override fun updateUI(p: SignatureInformation, context: ParameterInfoUIContext) {
        val label = p.label
        val params = p.parameters.orEmpty()
        val idx = if (context.currentParameterIndex >= 0) context.currentParameterIndex else active
        var start = -1
        var end = -1
        params.getOrNull(idx)?.label?.let { l ->
            if (l.isLeft) {
                start = label.indexOf(l.left)
                if (start >= 0) end = start + l.left.length
            } else {
                start = l.right.first
                end = l.right.second
            }
        }
        context.setupUIComponentPresentation(label, start, end, false, false, false, context.defaultParameterColor)
    }

    /** Index of the argument at [offset], or -1 when [offset] is not inside a call's parentheses. */
    internal fun argumentIndex(text: CharSequence, offset: Int): Int {
        var depth = 0
        var commas = 0
        var i = minOf(offset, text.length) - 1
        while (i >= 0) {
            when (val c = text[i]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> {
                    if (depth == 0) return if (c == '(') commas else -1
                    depth--
                }
                ',' -> if (depth == 0) commas++
                ';' -> return -1
                '"', '\'' -> {
                    // skip back over a string or char literal
                    i--
                    while (i >= 0 && !(text[i] == c && (i == 0 || text[i - 1] != '\\'))) i--
                }
            }
            i--
        }
        return -1
    }

    private fun request(file: PsiFile, document: Document, offset: Int): SignatureHelp? {
        val vf = file.virtualFile ?: return null
        val server = SpLspUtil.server(file.project, vf) ?: return null
        val params = SignatureHelpParams(SpLspUtil.docId(server, vf), SpLspUtil.position(document, offset))
        return SpLspUtil.request(server, 1500) { it.textDocumentService.signatureHelp(params) }
    }
}
