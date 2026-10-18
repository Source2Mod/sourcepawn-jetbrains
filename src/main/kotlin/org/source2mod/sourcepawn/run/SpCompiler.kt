package org.source2mod.sourcepawn.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.util.Key
import com.intellij.util.execution.ParametersListUtil
import org.source2mod.sourcepawn.toolchain.BuildSystem
import org.source2mod.sourcepawn.toolchain.SpBuildDir
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.SpToolchain
import java.io.File

/** One `file(line) : error N: text` line of spcomp output, with the file as a host path. */
data class SpMessage(
    val hostFile: String,
    val line: Int, // 1-based
    val severity: Severity,
    val code: String,
    val text: String,
) {
    enum class Severity { ERROR, WARNING }
}

/** What to compile, all paths as host paths. */
data class SpCompileRequest(
    val script: String,
    val outputDir: String,
    val extraArgs: List<String> = emptyList(),
) {
    val smxName get() = File(script).name.substringBeforeLast('.') + ".smx"
    val smx get() = File(outputDir, smxName).path
}

/** A ready-to-run build step: spcomp itself, or `cmake --build` / `ambuild`. Paths are host paths. */
class SpBuildPlan(
    val commandLine: GeneralCommandLine,
    /** Where relative paths in spcomp's messages are resolved. */
    val workDir: String?,
    /** The .smx files this step produces. */
    val outputs: List<String>,
    val title: String,
    /** Directories to create first. */
    val makeDirs: List<String> = emptyList(),
)

class SpCompileResult(val exitCode: Int, val messages: List<SpMessage>, val outputs: List<String>) {
    val smx: String get() = outputs.firstOrNull() ?: ""
    val success get() = exitCode == 0 && messages.none { it.severity == SpMessage.Severity.ERROR }
}

object SpCompiler {
    // spcomp: "/x/a.sp(12) : error 017: undefined symbol" / "a.sp(3 -- 5) : warning 204: ..." / "fatal error 183: ..."
    val MESSAGE = Regex("""^(.+?)\((\d+)(?:\s*--\s*\d+)?\)\s*:\s*(fatal error|error|warning)\s+(\d+)\s*:\s*(.*)$""")

    fun parse(line: String, target: SpTarget, workDir: String?): SpMessage? {
        val m = MESSAGE.find(line.trim()) ?: return null
        val (file, ln, kind, code, text) = m.destructured
        var host = target.toHost(file)
        if (!File(host).isAbsolute && !host.startsWith("//") && workDir != null) host = File(workDir, host).path
        val sev = if (kind == "warning") SpMessage.Severity.WARNING else SpMessage.Severity.ERROR
        return SpMessage(host.replace('\\', '/'), ln.toInt(), sev, code, text)
    }

    /** Default output dir for a script: ../plugins next to a "scripting" dir, else the script's dir. */
    fun defaultOutputDir(toolchain: SpToolchain, script: String): String {
        toolchain.settings.outputDirectory?.takeIf { it.isNotBlank() }?.let {
            return toolchain.target.toHost(toolchain.userPathToTarget(it))
        }
        val dir = File(script).parentFile ?: return "."
        return if (dir.name == "scripting") File(dir.parentFile, "plugins").path else dir.path
    }

    fun commandLine(toolchain: SpToolchain, req: SpCompileRequest): GeneralCommandLine {
        val target = toolchain.target
        val compiler = toolchain.compiler
            ?: throw ExecutionException("spcomp not found. Set it in Settings | Languages & Frameworks | SourcePawn, or set \$SPCOMP.")
        if (!compiler.exists) throw ExecutionException("spcomp '${compiler.path}' (from ${compiler.source}) does not exist")
        val scriptDir = File(req.script).parent
        val args = mutableListOf<String>()
        args += target.toTarget(req.script)
        args += "-o" + target.toTarget(req.smx)
        for (inc in toolchain.includeDirectories) args += "-i" + inc.path
        // the script's own dir and its include/ subdir, like SourceMod's compile.sh
        args += "-i" + target.toTarget(scriptDir)
        if (File(scriptDir, "include").isDirectory) args += "-i" + target.toTarget(File(scriptDir, "include").path)
        args += toolchain.compilerArguments
        args += req.extraArgs
        return target.commandLine(compiler.path, args, scriptDir)
    }

    fun plan(toolchain: SpToolchain, req: SpCompileRequest) = SpBuildPlan(
        commandLine(toolchain, req), File(req.script).parent, listOf(req.smx),
        "spcomp ${File(req.script).name}", listOf(req.outputDir),
    )

    /** Runs spcomp, passing every output line to [onLine]; blocks until it exits. */
    fun compile(
        toolchain: SpToolchain,
        req: SpCompileRequest,
        onStart: (GeneralCommandLine, OSProcessHandler) -> Unit = { _, _ -> },
        onLine: (String, Key<*>) -> Unit,
    ): SpCompileResult = run(toolchain, plan(toolchain, req), onStart, onLine)

    /** Runs a plan; spcomp's messages are collected from its output whatever runs it. */
    fun run(
        toolchain: SpToolchain,
        plan: SpBuildPlan,
        onStart: (GeneralCommandLine, OSProcessHandler) -> Unit = { _, _ -> },
        onLine: (String, Key<*>) -> Unit,
    ): SpCompileResult {
        plan.makeDirs.forEach { File(it).mkdirs() }
        val cmd = plan.commandLine
        val messages = mutableListOf<SpMessage>()
        val handler = OSProcessHandler(cmd)
        val workDir = plan.workDir
        val pending = StringBuilder()
        handler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == com.intellij.execution.process.ProcessOutputTypes.SYSTEM) return
                pending.append(event.text)
                while (true) {
                    val nl = pending.indexOf("\n")
                    if (nl < 0) break
                    val line = pending.substring(0, nl).trimEnd('\r')
                    pending.delete(0, nl + 1)
                    parse(line, toolchain.target, workDir)?.let { messages += it }
                    onLine(line + "\n", outputType)
                }
            }
        })
        onStart(cmd, handler)
        handler.startNotify()
        handler.waitFor()
        if (pending.isNotEmpty()) {
            parse(pending.toString(), toolchain.target, workDir)?.let { messages += it }
            onLine(pending.toString() + "\n", com.intellij.execution.process.ProcessOutputTypes.STDOUT)
        }
        return SpCompileResult(handler.exitCode ?: -1, messages.distinct(), plan.outputs)
    }

    /**
     * Build through the project's build system: `cmake --build <dir> [--target t]`
     * or `ambuild <objdir>`. [script] (host path) narrows the outputs to its .smx.
     */
    fun buildSystemPlan(toolchain: SpToolchain, dir: SpBuildDir, cmakeTarget: String?, script: String?): SpBuildPlan {
        val target = toolchain.target
        val tDir = target.toTarget(dir.hostDir)
        val cmd = when (dir.kind) {
            BuildSystem.CMAKE -> {
                val cmake = toolchain.cmake() ?: throw ExecutionException("cmake not found on the PATH (${target.displayName})")
                val args = mutableListOf("--build", tDir)
                if (!cmakeTarget.isNullOrBlank()) args += listOf("--target", cmakeTarget)
                target.commandLine(cmake, args, dir.hostDir)
            }
            BuildSystem.AMBUILD -> {
                val ambuild = target.which("ambuild") ?: throw ExecutionException("ambuild not found on the PATH (${target.displayName})")
                target.commandLine(ambuild, listOf(tDir), dir.hostDir)
            }
            else -> throw ExecutionException("no build system")
        }
        val tScript = script?.let { target.toTarget(it) }
        val outputs = dir.commands
            .filter { tScript == null || it.source == tScript }
            .mapNotNull { it.output?.let(target::toHost) }
        val what = if (dir.kind == BuildSystem.CMAKE) "cmake --build" else "ambuild"
        return SpBuildPlan(cmd, dir.hostDir, outputs, "$what ${File(dir.hostDir).name}")
    }

    fun describe(cmd: GeneralCommandLine) = ParametersListUtil.join(cmd.getCommandLineList(null))
}
