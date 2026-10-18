package org.source2mod.sourcepawn.run

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Messages of the last spcomp run per compiled script, shown as editor
 * annotations until that script is compiled again.
 */
@Service(Service.Level.PROJECT)
class SpCompileResults(private val project: Project) {
    private val byScript = ConcurrentHashMap<String, List<SpMessage>>()

    fun publish(script: String, result: SpCompileResult) {
        byScript[norm(script)] = result.messages
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }

    fun messagesFor(file: String): List<SpMessage> {
        val f = norm(file)
        return byScript.values.flatMap { list -> list.filter { norm(it.hostFile) == f } }
    }

    private fun norm(p: String) = p.replace('\\', '/').lowercase()

    companion object {
        fun get(project: Project): SpCompileResults = project.service()
    }
}

class SpCompileAnnotator : ExternalAnnotator<PsiFile, List<SpMessage>>() {
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): PsiFile = file
    override fun collectInformation(file: PsiFile): PsiFile = file

    override fun doAnnotate(file: PsiFile): List<SpMessage> {
        val path = file.virtualFile?.path ?: return emptyList()
        return SpCompileResults.get(file.project).messagesFor(path)
    }

    override fun apply(file: PsiFile, messages: List<SpMessage>, holder: AnnotationHolder) {
        val doc = file.viewProvider.document ?: return
        for (m in messages) {
            val line = (m.line - 1).coerceIn(0, maxOf(0, doc.lineCount - 1))
            if (doc.lineCount == 0) continue
            var start = doc.getLineStartOffset(line)
            val end = doc.getLineEndOffset(line)
            val text = doc.charsSequence
            while (start < end && text[start].isWhitespace()) start++
            val severity = if (m.severity == SpMessage.Severity.ERROR) HighlightSeverity.ERROR else HighlightSeverity.WARNING
            holder.newAnnotation(severity, "spcomp ${m.severity.name.lowercase()} ${m.code}: ${m.text}")
                .range(TextRange(start, maxOf(start, end)))
                .create()
        }
    }
}
