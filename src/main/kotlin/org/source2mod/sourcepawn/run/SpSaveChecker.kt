package org.source2mod.sourcepawn.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import org.source2mod.sourcepawn.SpFileType
import org.source2mod.sourcepawn.isSourcePawn
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.SpToolchainService
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * spcomp on save: compiles the saved .sp (or, for a saved .inc, every open .sp)
 * into a scratch directory and shows the messages as editor annotations
 * ([SpCompileAnnotator]). Done here and not by the language server, see
 * SpLspServerDescriptor.serverConfig.
 */
class SpSaveListener : FileDocumentManagerListener {
    override fun beforeDocumentSaving(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isSourcePawn()) return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val scripts = if (file.fileType == SpFileType) listOf(file.path)
            else FileEditorManager.getInstance(project).openFiles.filter { it.fileType == SpFileType }.map { it.path }
            if (scripts.isNotEmpty()) SpSaveChecker.get(project).schedule(scripts)
        }
    }
}

/**
 * Same check when a .sp is opened (restored editors included), so a file shows its
 * errors without being edited first. An opened .inc rechecks the open .sp files.
 * Does nothing until spcomp is found (settings, environment or PATH).
 */
class SpOpenListener(private val project: Project) : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!file.isSourcePawn()) return
        val scripts = if (file.fileType == SpFileType) listOf(file.path)
        else source.openFiles.filter { it.fileType == SpFileType }.map { it.path }
        if (scripts.isNotEmpty()) SpSaveChecker.get(project).schedule(scripts)
    }
}

@Service(Service.Level.PROJECT)
class SpSaveChecker(private val project: Project) : Disposable {
    private val queued = LinkedHashSet<String>()
    private var pending: ScheduledFuture<*>? = null

    /** Debounced: the document is written to disk right after beforeDocumentSaving. */
    @Synchronized
    fun schedule(scripts: Collection<String>) {
        queued += scripts
        pending?.cancel(false)
        pending = AppExecutorUtil.getAppScheduledExecutorService().schedule({ run() }, 400, TimeUnit.MILLISECONDS)
    }

    private fun run() {
        val scripts = synchronized(this) { queued.toList().also { queued.clear() } }
        if (project.isDisposed) return
        val toolchain = SpToolchainService.get(project).get()
        if (!toolchain.settings.compileOnSave) return
        val c = toolchain.compiler
        if (c == null || !c.exists) return
        val out = when (val t = toolchain.target) {
            is SpTarget.Wsl -> t.toHost("/tmp/sourcepawn-jetbrains-check")
            SpTarget.Local -> File(FileUtil.getTempDirectory(), "sourcepawn-jetbrains-check").path
        }
        for (script in scripts) {
            if (!project.isOpen) return
            try {
                val result = SpCompiler.compile(toolchain, SpCompileRequest(script, out)) { _, _ -> }
                SpCompileResults.get(project).publish("spcomp " + File(script).name, result)
                File(result.smx).delete()
            } catch (e: Exception) {
                LOG.info("spcomp on save failed for $script", e)
            }
        }
    }

    @Synchronized
    override fun dispose() {
        pending?.cancel(false)
    }

    companion object {
        private val LOG = logger<SpSaveChecker>()
        fun get(project: Project): SpSaveChecker = project.service()
    }
}
