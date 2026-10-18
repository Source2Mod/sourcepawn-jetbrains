package org.source2mod.sourcepawn.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.source2mod.sourcepawn.toolchain.SpTarget
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Run = compile, then copy the .smx and send RCON commands, all in one console. */
class SpRunState(private val env: ExecutionEnvironment, private val config: SpRunConfiguration) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): ExecutionResult {
        val project = env.project
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.addMessageFilter(SpOutputFilter(project))
        val handler = StepsProcessHandler(project, config)
        console.attachToProcess(handler)
        return DefaultExecutionResult(console, handler)
    }
}

/** A "process" that is really a sequence of steps run on a pooled thread. */
private class StepsProcessHandler(private val project: Project, private val config: SpRunConfiguration) : ProcessHandler() {
    @Volatile private var spcomp: OSProcessHandler? = null
    @Volatile private var cancelled = false

    override fun startNotify() {
        super.startNotify()
        ApplicationManager.getApplication().executeOnPooledThread {
            val code = try {
                runSteps()
            } catch (e: Exception) {
                err((e.message ?: e.toString()) + "\n")
                1
            }
            notifyProcessTerminated(code)
        }
    }

    private fun out(s: String) = notifyTextAvailable(s, ProcessOutputTypes.STDOUT)
    private fun err(s: String) = notifyTextAvailable(s, ProcessOutputTypes.STDERR)
    private fun sys(s: String) = notifyTextAvailable(s, ProcessOutputTypes.SYSTEM)

    private fun runSteps(): Int {
        val toolchain = config.toolchain()
        val req = config.compileRequest(toolchain)
        val result = SpCompiler.compile(
            toolchain, req,
            onStart = { cmd, h ->
                spcomp = h
                sys(SpCompiler.describe(cmd) + "\n")
            },
            onLine = { line, type -> notifyTextAvailable(line, if (type == ProcessOutputTypes.STDERR) type else ProcessOutputTypes.STDOUT) },
        )
        SpCompileResults.get(project).publish(req.script, result)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(req.outputDir)?.refresh(true, false)
        if (!result.success || cancelled) {
            sys("spcomp failed (exit code ${result.exitCode})\n")
            return if (result.exitCode != 0) result.exitCode else 1
        }
        sys("Compiled ${req.smx}\n")

        val o = config.options
        if (o.copyEnabled && !cancelled) {
            val dir = toolchain.target.toHost(toolchain.userPathToTarget(o.copyToDir ?: ""))
            val dest = File(dir, req.smxName)
            dest.parentFile.mkdirs()
            Files.copy(File(req.smx).toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            sys("Copied to ${dest.path}\n")
        }
        if (o.rconEnabled && o.rconCommands.isNotEmpty() && !cancelled) {
            val host = o.rconHost ?: "127.0.0.1"
            val password = SpRunConfiguration.rconPassword(host, o.rconPort) ?: ""
            sys("RCON $host:${o.rconPort}\n")
            SpRcon(host, o.rconPort, password).use { rcon ->
                for (c in o.rconCommands) {
                    if (cancelled) break
                    sys("] ${toolchain.expand(c)}\n")
                    val reply = rcon.exec(toolchain.expand(c))
                    if (reply.isNotEmpty()) out(if (reply.endsWith("\n")) reply else reply + "\n")
                }
            }
        }
        return 0
    }

    override fun destroyProcessImpl() {
        cancelled = true
        spcomp?.destroyProcess()
    }

    override fun detachProcessImpl() = destroyProcessImpl()
    override fun detachIsDefault() = false
    override fun getProcessInput(): OutputStream? = null
}

/** Makes `file(line) : error N:` lines in consoles clickable. */
class SpOutputFilter(private val project: Project) : Filter {
    private val target: SpTarget = org.source2mod.sourcepawn.toolchain.SpToolchain.targetFor(project)

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val text = line.trimEnd()
        val m = SpCompiler.MESSAGE.find(text) ?: return null
        val file = m.groups[1]!!
        val vf = LocalFileSystem.getInstance().findFileByPath(target.toHost(file.value.trim())) ?: return null
        val lineStart = entireLength - line.length
        val close = text.indexOf(')', file.range.last)
        return Filter.Result(lineStart + file.range.first, lineStart + close + 1,
            OpenFileHyperlinkInfo(project, vf, m.groupValues[2].toInt() - 1))
    }
}

/** spcomp lines in any console (CMake builds of SourcePawn targets too) become links. */
class SpConsoleFilterProvider : com.intellij.execution.filters.ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> = arrayOf(SpOutputFilter(project))
}
