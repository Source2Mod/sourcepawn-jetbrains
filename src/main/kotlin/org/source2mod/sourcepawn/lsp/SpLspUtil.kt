package org.source2mod.sourcepawn.lsp

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerState
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.source2mod.sourcepawn.isSourcePawn

/**
 * Helpers for the requests the 2024.3 LSP API does not wire up itself
 * (document symbols, rename, signature help): they go to the server directly.
 */
object SpLspUtil {
    fun server(project: Project, file: VirtualFile?): LspServer? {
        if (file == null || !file.isSourcePawn()) return null
        return SpLspServerSupportProvider.servers(project).firstOrNull { it.state == LspServerState.Running }
    }

    fun docId(server: LspServer, file: VirtualFile): TextDocumentIdentifier = server.getDocumentIdentifier(file)

    fun position(document: Document, offset: Int): Position {
        val line = document.getLineNumber(offset)
        return Position(line, offset - document.getLineStartOffset(line))
    }

    fun offset(document: Document, pos: Position): Int {
        if (document.lineCount == 0) return 0
        val line = pos.line.coerceIn(0, document.lineCount - 1)
        return (document.getLineStartOffset(line) + pos.character).coerceAtMost(document.getLineEndOffset(line))
    }

    fun document(file: VirtualFile): Document? = FileDocumentManager.getInstance().getDocument(file)

    fun fileByUri(server: LspServer, uri: String): VirtualFile? = server.descriptor.findFileByUri(uri)

    /** Synchronous request with a short timeout; null on any failure (server busy, not started...). */
    fun <T> request(server: LspServer, timeoutMs: Int = 3000, call: (org.eclipse.lsp4j.services.LanguageServer) -> java.util.concurrent.CompletableFuture<T>): T? =
        runCatching { server.sendRequestSync(timeoutMs) { call(it) } }.getOrNull()
}
