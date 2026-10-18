package org.source2mod.sourcepawn.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.SystemInfo
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Where spcomp and the language server run: on the IDE's own machine, or inside a
 * WSL distro through `wsl.exe --exec`. "Host paths" are what the IDE sees
 * (C:\x, //wsl.localhost/<distro>/x); "target paths" are what the tool sees (/x).
 */
sealed class SpTarget {
    abstract val displayName: String

    /** Host path -> path the tool understands. */
    abstract fun toTarget(hostPath: String): String

    /** Path printed by the tool -> host path. */
    abstract fun toHost(targetPath: String): String

    /** Command line that runs [exe] (a target path) with [args] on this target. */
    abstract fun commandLine(exe: String, args: List<String>, workDir: String? = null): GeneralCommandLine

    /** Environment of a login shell on the target (for WSL), or of the IDE process. */
    abstract fun env(): Map<String, String>

    /** Looks up a program on the target's PATH; returns a target path or null. */
    abstract fun which(name: String): String?

    /** Does the target path exist (checked through the host path)? */
    open fun exists(targetPath: String) = File(toHost(targetPath)).exists()

    object Local : SpTarget() {
        override val displayName = "local"
        override fun toTarget(hostPath: String) = hostPath
        override fun toHost(targetPath: String) = targetPath
        override fun commandLine(exe: String, args: List<String>, workDir: String?) =
            GeneralCommandLine(listOf(exe) + args).withCharset(StandardCharsets.UTF_8)
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .also { if (workDir != null) it.withWorkDirectory(workDir) }

        override fun env(): Map<String, String> = System.getenv()

        override fun which(name: String): String? {
            val names = if (SystemInfo.isWindows && !name.contains('.')) listOf("$name.exe", "$name.cmd", "$name.bat", name) else listOf(name)
            for (n in names) PathEnvironmentVariableUtil.findInPath(n)?.let { return it.absolutePath }
            return null
        }
    }

    data class Wsl(val distro: String) : SpTarget() {
        override val displayName get() = "WSL ($distro)"
        override fun toTarget(hostPath: String) = WslPaths.toLinux(hostPath)
        override fun toHost(targetPath: String) = WslPaths.toHost(targetPath, distro)

        // --exec runs the program directly: no shell, so backslashes and quotes in
        // arguments arrive untouched (plain `wsl.exe -- cmd` goes through a shell).
        override fun commandLine(exe: String, args: List<String>, workDir: String?): GeneralCommandLine {
            val cmd = mutableListOf("wsl.exe", "-d", distro)
            if (workDir != null) cmd += listOf("--cd", toTarget(workDir))
            cmd += listOf("--exec", exe)
            cmd += args
            return GeneralCommandLine(cmd).withCharset(StandardCharsets.UTF_8)
        }

        override fun env(): Map<String, String> = ENV_CACHE.getOrPut(distro) { loadEnv() }

        /** \\wsl.localhost cannot follow absolute symlinks; ask the distro when the cheap check says no. */
        override fun exists(targetPath: String): Boolean {
            if (super.exists(targetPath)) return true
            if (!targetPath.startsWith("/")) return false
            return try {
                CapturingProcessHandler(GeneralCommandLine("wsl.exe", "-d", distro, "--exec", "test", "-e", targetPath))
                    .runProcess(10_000).exitCode == 0
            } catch (e: Exception) {
                false
            }
        }

        // Asked inside the distro: many tools are symlinks (/usr/bin/cmake ->
        // /etc/alternatives/...), which \\wsl.localhost cannot follow from Windows.
        override fun which(name: String): String? = WHICH_CACHE.getOrPut("$distro\u0000$name") {
            val path = env()["PATH"]?.split(':')?.filterNot { it.startsWith("/mnt/") }?.joinToString(":")
            val args = buildList {
                if (path != null) { add("env"); add("PATH=$path") }
                add("which"); add(name)
            }
            try {
                val out = CapturingProcessHandler(GeneralCommandLine(listOf("wsl.exe", "-d", distro, "--exec") + args)
                    .withCharset(StandardCharsets.UTF_8)).runProcess(10_000)
                if (out.exitCode == 0) out.stdout.trim().lineSequence().firstOrNull().orEmpty() else ""
            } catch (e: Exception) {
                ""
            }
        }.ifEmpty { null }

        /** Login + interactive bash, so exports from ~/.profile and ~/.bashrc are seen. */
        private fun loadEnv(): Map<String, String> {
            val marker = "__SOURCEPAWN_ENV__"
            val cmd = GeneralCommandLine("wsl.exe", "-d", distro, "--exec", "bash", "-lic", "printf '$marker\\0'; env -0")
                .withCharset(StandardCharsets.UTF_8)
            return try {
                val out = CapturingProcessHandler(cmd).runProcess(15_000)
                val text = out.stdout.substringAfter("$marker\u0000", "")
                text.split('\u0000').mapNotNull { e ->
                    val i = e.indexOf('=')
                    if (i <= 0) null else e.substring(0, i) to e.substring(i + 1)
                }.toMap()
            } catch (e: Exception) {
                LOG.warn("cannot read the environment of WSL distro $distro", e)
                emptyMap()
            }
        }
    }

    companion object {
        private val LOG = logger<SpTarget>()
        private val ENV_CACHE = ConcurrentHashMap<String, Map<String, String>>()
        private val WHICH_CACHE = ConcurrentHashMap<String, String>()

        /** Forget cached WSL environments and lookups (after the user changed ~/.bashrc, say). */
        fun clearCaches() {
            ENV_CACHE.clear()
            WHICH_CACHE.clear()
        }
    }
}
