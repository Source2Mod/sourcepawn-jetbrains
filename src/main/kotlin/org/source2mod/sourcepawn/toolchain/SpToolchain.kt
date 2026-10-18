package org.source2mod.sourcepawn.toolchain

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import org.source2mod.sourcepawn.settings.ExecutionMode
import org.source2mod.sourcepawn.settings.SpSettings
import org.source2mod.sourcepawn.settings.SpSettingsState
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A resolved path plus where it came from, for the Doctor. */
data class Resolved(val path: String, val source: String, val exists: Boolean = true)

/** Per-run-configuration overrides of the project settings. */
data class SpOverrides(
    val compilerPath: String? = null,
    val includeDirectories: List<String>? = null,
)

/**
 * Turns the project settings plus environment into concrete tool paths.
 * Everything returned is a *target* path (see [SpTarget]).
 */
class SpToolchain private constructor(
    val project: Project,
    val settings: SpSettingsState,
    val target: SpTarget,
    private val overrides: SpOverrides,
) {
    val env: Map<String, String> by lazy { target.env() }

    /** The project directory as a host path (//wsl.localhost/... or C:/...). */
    val projectDir: String? get() = project.basePath ?: project.guessProjectDir()?.path

    /** CMake / AMBuild build dirs with the spcomp commands in them (Settings | SourcePawn | Build system). */
    val buildDirs: List<SpBuildDir> by lazy {
        val dir = projectDir ?: return@lazy emptyList()
        val explicit = settings.buildDirectory?.takeIf { it.isNotBlank() }?.let { target.toHost(userPathToTarget(it)) }
        SpBuildSystemReader.find(settings.buildSystem, dir, explicit, target)
    }

    val compiler: Resolved? by lazy { resolveCompiler() }
    val includeDirectories: List<Resolved> by lazy { resolveIncludes() }
    val languageServer: Resolved? by lazy { resolveServer() }

    val compilerArguments: List<String>
        get() = ParametersListUtil.parse(expand(settings.compilerArguments ?: ""))

    /** Expands $VAR$, ${VAR} and $PROJECT_DIR$ (as a host path). Unknown variables stay as they are. */
    fun expand(s: String): String {
        val vars = HashMap(env)
        projectDir?.let { vars["PROJECT_DIR"] = it }
        return VAR.replace(s) { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            vars[name] ?: m.value
        }
    }

    /** A user-typed path -> target path: expands variables, resolves relative paths against the project. */
    fun userPathToTarget(raw: String): String {
        val s = expand(raw.trim())
        if (s.isEmpty()) return s
        val absolute = s.startsWith("/") || s.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(s)
        val host = if (absolute) s else File(projectDir ?: ".", s).path
        return normalize(target.toTarget(host))
    }

    private fun normalize(p: String): String =
        if (target is SpTarget.Wsl) p.replace('\\', '/').replace(Regex("/+"), "/").let { collapseDots(it) } else FileUtil.toCanonicalPath(p)

    private fun collapseDots(p: String): String {
        val out = ArrayList<String>()
        for (part in p.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out += part
        }
        return "/" + out.joinToString("/")
    }

    private fun joinTarget(base: String, vararg parts: String): String =
        if (target is SpTarget.Wsl) collapseDots((listOf(base) + parts).joinToString("/")) else File(base, parts.joinToString(File.separator)).path

    private fun parentOf(p: String): String =
        if (target is SpTarget.Wsl) p.substringBeforeLast('/', "/").ifEmpty { "/" } else File(p).parent ?: p

    private fun resolveCompiler(): Resolved? {
        overrides.compilerPath?.takeIf { it.isNotBlank() }?.let {
            val p = userPathToTarget(it)
            return Resolved(p, "run configuration", target.exists(p))
        }
        settings.compilerPath?.takeIf { it.isNotBlank() }?.let {
            val p = userPathToTarget(it)
            return Resolved(p, "settings", target.exists(p))
        }
        for (b in buildDirs) {
            val c = b.commands.firstOrNull() ?: continue
            val p = if (c.spcomp.contains('/') || c.spcomp.contains('\\')) c.spcomp else target.which(c.spcomp) ?: continue
            return Resolved(p, buildSource(b), target.exists(p))
        }
        env["SPCOMP"]?.takeIf { it.isNotBlank() }?.let {
            val p = userPathToTarget(it)
            return Resolved(p, "\$SPCOMP", target.exists(p))
        }
        for (v in listOf("SOURCE2MOD_DIR", "SOURCEMOD_DIR")) {
            val dir = env[v]?.takeIf { it.isNotBlank() } ?: continue
            for (name in compilerNames()) {
                val p = joinTarget(userPathToTarget(dir), "scripting", name)
                if (target.exists(p)) return Resolved(p, "\$$v/scripting")
            }
        }
        for (name in compilerNames()) {
            target.which(name)?.let { return Resolved(it, "PATH") }
        }
        return null
    }

    private fun compilerNames(): List<String> =
        if (target is SpTarget.Local && SystemInfo.isWindows) listOf("spcomp64.exe", "spcomp.exe") else listOf("spcomp64", "spcomp")

    private fun resolveIncludes(): List<Resolved> {
        val out = LinkedHashMap<String, Resolved>()
        fun add(p: String, source: String) {
            if (p.isNotBlank() && p !in out) out[p] = Resolved(p, source, target.exists(p))
        }
        val listed = overrides.includeDirectories ?: settings.includeDirectories
        val listedSource = if (overrides.includeDirectories != null) "run configuration" else "settings"
        for (raw in listed) if (raw.isNotBlank()) add(userPathToTarget(raw), listedSource)
        for (b in buildDirs) for (c in b.commands) for (inc in c.includes) add(inc, buildSource(b))

        if (settings.useEnvironmentIncludes) {
            env["SOURCEPAWN_INCLUDE"]?.let { v ->
                val sep = if (target is SpTarget.Local && SystemInfo.isWindows) ';' else ':'
                for (p in v.split(sep)) if (p.isNotBlank()) add(userPathToTarget(p), "\$SOURCEPAWN_INCLUDE")
            }
            env["SOURCE2MOD_SDK"]?.takeIf { it.isNotBlank() }?.let {
                add(joinTarget(userPathToTarget(it), "public", "scripting"), "\$SOURCE2MOD_SDK/public/scripting")
            }
            compiler?.let { c ->
                val inc = joinTarget(parentOf(c.path), "include")
                if (target.exists(inc)) add(inc, "next to spcomp")
            }
        }
        return out.values.toList()
    }

    private fun buildSource(b: SpBuildDir): String {
        val rel = projectDir?.let { File(b.hostDir).relativeToOrNull(File(it))?.path?.replace('\\', '/') } ?: b.hostDir
        return if (b.kind == BuildSystem.CMAKE) "CMake: $rel" else "AMBuild: $rel"
    }

    /** cmake on the target's PATH; locally on Windows, else the IDE's bundled one (CLion ships it). */
    fun cmake(): String? {
        target.which("cmake")?.let { return it }
        if (target is SpTarget.Local && SystemInfo.isWindows) {
            val bundled = File(PathManager.getHomePath(), "bin/cmake/win/x64/bin/cmake.exe")
            if (bundled.isFile) return bundled.path
        }
        return null
    }

    private fun resolveServer(): Resolved? {
        settings.languageServerPath?.takeIf { it.isNotBlank() }?.let {
            val p = userPathToTarget(it)
            return Resolved(p, "settings", target.exists(p))
        }
        target.which("sourcepawn-studio")?.let { return Resolved(it, "PATH") }
        for (p in wellKnownServerPaths()) if (target.exists(p)) return Resolved(p, "found")
        return null
    }

    /** Where [SpServerDownloader] puts the server, plus the hand-built WSL location from CLION-SETUP.md. */
    fun wellKnownServerPaths(): List<String> = buildList {
        add(downloadedServerPath())
        if (target is SpTarget.Wsl) add("/opt/sourcepawn-studio/sourcepawn-studio")
    }

    fun downloadedServerPath(): String = when (target) {
        is SpTarget.Wsl -> (env["HOME"] ?: "/root") + "/.local/share/sourcepawn-jetbrains/" + SERVER_VERSION + "/sourcepawn-studio"
        SpTarget.Local -> File(PathManager.getSystemPath(), "sourcepawn-studio/$SERVER_VERSION/" +
            if (SystemInfo.isWindows) "sourcepawn-studio.exe" else "sourcepawn-studio").path
    }

    companion object {
        const val SERVER_VERSION = "v8.1.8"
        private val VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)\$""")
        private val DEFAULT_DISTRO = ConcurrentHashMap<String, String>()

        fun of(project: Project, overrides: SpOverrides = SpOverrides()): SpToolchain =
            of(project, SpSettings.get(project), overrides)

        /** With explicit (possibly unsaved) settings, for the Doctor. */
        fun of(project: Project, s: SpSettingsState, overrides: SpOverrides = SpOverrides()): SpToolchain =
            SpToolchain(project, s, targetFor(project, s), overrides)

        fun targetFor(project: Project, s: SpSettingsState = SpSettings.get(project)): SpTarget {
            val projectDistro = project.basePath?.let { WslPaths.distroOf(it) }
            val distro = s.wslDistro?.takeIf { it.isNotBlank() } ?: projectDistro
            return when (s.executionMode) {
                ExecutionMode.LOCAL -> SpTarget.Local
                ExecutionMode.WSL -> SpTarget.Wsl(distro ?: defaultDistro() ?: "Ubuntu")
                ExecutionMode.AUTO ->
                    if (SystemInfo.isWindows && projectDistro != null) SpTarget.Wsl(distro!!) else SpTarget.Local
            }
        }

        /** The default WSL distro (asks the distro itself, which avoids parsing UTF-16 `wsl -l`). */
        fun defaultDistro(): String? = DEFAULT_DISTRO.getOrPut("") {
            runCatching {
                CapturingProcessHandler(GeneralCommandLine("wsl.exe", "--exec", "printenv", "WSL_DISTRO_NAME"))
                    .runProcess(10_000).stdout.trim()
            }.getOrDefault("")
        }.ifEmpty { null }
    }
}
