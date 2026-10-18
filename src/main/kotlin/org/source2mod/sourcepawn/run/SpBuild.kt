package org.source2mod.sourcepawn.run

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.FileMessageEventImpl
import com.intellij.build.events.impl.FinishBuildEventImpl
import com.intellij.build.events.impl.OutputBuildEventImpl
import com.intellij.build.events.impl.StartBuildEventImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.RunManager
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.task.ExecuteRunConfigurationTask
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import org.source2mod.sourcepawn.isSourcePawn
import org.source2mod.sourcepawn.toolchain.SpToolchain
import java.io.File

/** Compiles with the output in the Build tool window (clickable messages, error tree). */
object SpBuild {
    fun compileInBuildView(project: Project, title: String, prepare: () -> Pair<SpToolchain, SpCompileRequest>, done: (Boolean) -> Unit = {}) {
        // ProjectTaskRunner.run comes on a pooled thread; saving needs the EDT
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app.isDispatchThread) FileDocumentManager.getInstance().saveAllDocuments()
        else app.invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val view = project.getService(BuildViewManager::class.java)
                val id = Any()
                val start = System.currentTimeMillis()
                var ok = false
                try {
                    val (toolchain, req) = prepare()
                    val descriptor = DefaultBuildDescriptor(id, "SourcePawn: ${File(req.script).name}", File(req.script).parent, start)
                        .withExecutionFilter(SpOutputFilter(project))
                    view.onEvent(id, StartBuildEventImpl(descriptor, "Compiling ${File(req.script).name}"))
                    showBuildWindow(project)
                    val result = SpCompiler.compile(
                        toolchain, req,
                        onStart = { cmd, _ -> view.onEvent(id, OutputBuildEventImpl(id, SpCompiler.describe(cmd) + "\n", true)) },
                        onLine = { line, type -> view.onEvent(id, OutputBuildEventImpl(id, line, type != ProcessOutputTypes.STDERR)) },
                    )
                    for (m in result.messages) {
                        val kind = if (m.severity == SpMessage.Severity.ERROR) MessageEvent.Kind.ERROR else MessageEvent.Kind.WARNING
                        view.onEvent(id, FileMessageEventImpl(id, kind, "spcomp", "${m.code}: ${m.text}", null,
                            FilePosition(File(m.hostFile), m.line - 1, 0)))
                    }
                    SpCompileResults.get(project).publish(req.script, result)
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(req.outputDir)?.refresh(true, false)
                    ok = result.success
                    view.onEvent(id, FinishBuildEventImpl(id, null, System.currentTimeMillis(),
                        if (ok) "Compiled ${result.smx}" else "spcomp failed",
                        if (ok) SuccessResultImpl() else FailureResultImpl()))
                } catch (e: Exception) {
                    val descriptor = DefaultBuildDescriptor(id, "SourcePawn", project.basePath ?: ".", start)
                    view.onEvent(id, StartBuildEventImpl(descriptor, title))
                    view.onEvent(id, OutputBuildEventImpl(id, (e.message ?: e.toString()) + "\n", false))
                    view.onEvent(id, FinishBuildEventImpl(id, null, System.currentTimeMillis(), e.message ?: "failed", FailureResultImpl(e)))
                } finally {
                    done(ok)
                }
            }
        }.queue()
    }

    private fun showBuildWindow(project: Project) =
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow("Build")?.activate(null, false)
            }
        }

    fun compileConfiguration(project: Project, config: SpRunConfiguration, done: (Boolean) -> Unit = {}) =
        compileInBuildView(project, "Compiling ${config.name}", {
            val t = config.toolchain()
            t to config.compileRequest(t)
        }, done)

    fun selectedConfiguration(project: Project): SpRunConfiguration? =
        RunManager.getInstance(project).selectedConfiguration?.configuration as? SpRunConfiguration
}

/**
 * Build (the toolbar hammer, Ctrl+F9) while a SourcePawn configuration is selected
 * compiles that configuration; with any other selection the IDE's own runner (CMake...) builds.
 */
class SpProjectTaskRunner : ProjectTaskRunner() {
    override fun canRun(task: ProjectTask): Boolean = when (task) {
        is ExecuteRunConfigurationTask -> task.runProfile is SpRunConfiguration
        is ModuleBuildTask -> SpBuild.selectedConfiguration(task.module.project) != null
        else -> false
    }

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> {
        val promise = AsyncPromise<Result>()
        val configs = LinkedHashSet<SpRunConfiguration>()
        for (t in tasks) when (t) {
            is ExecuteRunConfigurationTask -> (t.runProfile as? SpRunConfiguration)?.let { configs += it }
            is ModuleBuildTask -> SpBuild.selectedConfiguration(project)?.let { configs += it }
        }
        if (configs.isEmpty()) {
            promise.setResult(TaskRunnerResults.SUCCESS)
            return promise
        }
        var remaining = configs.size
        var failed = false
        for (c in configs) {
            SpBuild.compileConfiguration(project, c) { ok ->
                synchronized(this) {
                    if (!ok) failed = true
                    if (--remaining == 0) promise.setResult(if (failed) TaskRunnerResults.FAILURE else TaskRunnerResults.SUCCESS)
                }
            }
        }
        return promise
    }
}

/** Compile the .sp in the editor (or selected in the project view) without a run configuration. */
class SpCompileFileAction : AnAction("Compile SourcePawn File", "Compile this file with spcomp", AllIcons.Actions.Compile), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val f = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && f != null && f.isSourcePawn() && f.extension == "sp"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        SpBuild.compileInBuildView(project, "Compiling ${file.name}", {
            val t = SpToolchain.of(project)
            t to SpCompileRequest(file.path, SpCompiler.defaultOutputDir(t, file.path))
        })
    }
}

/**
 * The hammer next to the run configuration selector while a SourcePawn
 * configuration is selected. CLion's own one (CidrBuildRunToolbar) only knows its
 * CMake-style build targets and stays disabled for other configuration types.
 */
class SpBuildSelectedAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val config = e.project?.let { SpBuild.selectedConfiguration(it) }
        e.presentation.isEnabledAndVisible = config != null
        if (config != null) e.presentation.text = "Build '${config.name}'"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val config = SpBuild.selectedConfiguration(project) ?: return
        SpBuild.compileConfiguration(project, config)
    }
}
