package org.source2mod.sourcepawn.toolchain

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.execution.ParametersListUtil
import java.io.File

enum class BuildSystem(val label: String) {
    NONE("None (spcomp directly)"),
    AUTO("Auto (CMake or AMBuild build dir in the project)"),
    CMAKE("CMake"),
    AMBUILD("AMBuild"),
    ;

    override fun toString() = label
}

/**
 * One spcomp invocation the build system would run. All paths are target paths
 * (what the build itself sees), already made absolute.
 */
data class SpBuildCommand(
    val spcomp: String,
    val source: String,
    val includes: List<String>,
    val output: String?,
    val workDir: String?,
)

/** A configured build directory and the spcomp commands found in it. */
data class SpBuildDir(
    val kind: BuildSystem,
    /** Host path of the CMake build dir / AMBuild objdir. */
    val hostDir: String,
    val commands: List<SpBuildCommand>,
)

/**
 * Reads the spcomp command lines a CMake (Ninja or Makefiles) build dir or an
 * AMBuild objdir already holds, so the include set and the compiler come from the
 * build scripts instead of the settings. Nothing in the build scripts has to change.
 */
object SpBuildSystemReader {
    private val LOG = logger<SpBuildSystemReader>()

    /** Build dirs of [kind] under [projectDir] (host path), or [explicitDir] alone when given. */
    fun find(kind: BuildSystem, projectDir: String, explicitDir: String?, target: SpTarget): List<SpBuildDir> {
        if (kind == BuildSystem.NONE) return emptyList()
        val candidates = if (explicitDir != null) listOf(File(explicitDir)) else scan(File(projectDir), 2)
        val out = ArrayList<SpBuildDir>()
        for (dir in candidates) {
            val isCMake = File(dir, "CMakeCache.txt").isFile
            val isAMBuild = File(dir, ".ambuild2/graph").isFile
            when {
                isCMake && (kind == BuildSystem.CMAKE || kind == BuildSystem.AUTO) ->
                    out += SpBuildDir(BuildSystem.CMAKE, dir.path, readCMake(dir))
                isAMBuild && (kind == BuildSystem.AMBUILD || kind == BuildSystem.AUTO) ->
                    out += SpBuildDir(BuildSystem.AMBUILD, dir.path, readAMBuild(dir, target))
            }
        }
        // Auto: CMake first when both exist, newest dir first within a kind
        return out.sortedWith(compareBy<SpBuildDir> { it.kind != BuildSystem.CMAKE }.thenByDescending { File(it.hostDir).lastModified() })
    }

    private fun scan(root: File, depth: Int): List<File> {
        val found = ArrayList<File>()
        fun walk(d: File, left: Int) {
            if (File(d, "CMakeCache.txt").isFile || File(d, ".ambuild2/graph").isFile) found += d
            if (left == 0) return
            d.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" }?.forEach { walk(it, left - 1) }
        }
        walk(root, depth)
        return found
    }

    // --- CMake -------------------------------------------------------------

    private fun readCMake(dir: File): List<SpBuildCommand> {
        val lines = ArrayList<String>()
        File(dir, "build.ninja").takeIf { it.isFile }?.useLines { seq ->
            seq.filter { it.trimStart().startsWith("COMMAND = ") }.forEach { lines += it.trimStart().removePrefix("COMMAND = ") }
        }
        File(dir, "CMakeFiles").listFiles()?.forEach { t ->
            File(t, "build.make").takeIf { it.isFile }?.useLines { seq -> seq.filter { it.startsWith("\t") }.forEach { lines += it.trim() } }
        }
        return lines.filter { it.contains("spcomp", ignoreCase = true) }.mapNotNull { parseShellLine(it) }.distinct()
    }

    /** `cd /x && /path/spcomp a.sp -i... -o...`, or Windows' `cmd.exe /C "cd /D x && spcomp.exe ..."`. */
    fun parseShellLine(line: String): SpBuildCommand? {
        var s = line.trim()
        Regex("""^(?:\S*cmd(?:\.exe)?)\s+/[Cc]\s+"(.*)"$""").find(s)?.let { s = it.groupValues[1] }
        var workDir: String? = null
        for (segment in s.split("&&").map { it.trim() }) {
            val argv = ParametersListUtil.parse(segment)
            if (argv.isEmpty()) continue
            if (argv[0] == "cd") {
                workDir = argv.drop(1).lastOrNull() // `cd /D dir` on Windows
                continue
            }
            fromArgv(argv, workDir)?.let { return it }
        }
        return null
    }

    fun fromArgv(argv: List<String>, workDir: String?): SpBuildCommand? {
        if (argv.isEmpty()) return null
        val exe = argv[0].substringAfterLast('/').substringAfterLast('\\')
        if (!exe.startsWith("spcomp", ignoreCase = true)) return null
        var source: String? = null
        var output: String? = null
        val includes = ArrayList<String>()
        var i = 1
        while (i < argv.size) {
            val a = argv[i]
            when {
                a == "-i" && i + 1 < argv.size -> includes += argv[++i]
                a.startsWith("-i") -> includes += a.substring(2)
                a.startsWith("--include=") -> includes += a.substringAfter('=')
                a == "-o" && i + 1 < argv.size -> output = argv[++i]
                a.startsWith("-o") -> output = a.substring(2)
                a.startsWith("--output=") -> output = a.substringAfter('=')
                !a.startsWith("-") && a.endsWith(".sp", ignoreCase = true) && source == null -> source = a
            }
            i++
        }
        source ?: return null
        fun abs(p: String) = absolutize(p, workDir)
        return SpBuildCommand(abs(argv[0]), abs(source), includes.map(::abs), output?.let(::abs), workDir)
    }

    private fun absolutize(p: String, workDir: String?): String {
        val absolute = p.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(p) || p.startsWith("\\\\")
        if (absolute || workDir == null) return normalize(p)
        // spcomp's own name without a dir is looked up on the PATH, keep it
        if (!p.contains('/') && !p.contains('\\') && p.startsWith("spcomp", true)) return p
        val sep = if (workDir.startsWith("/")) "/" else "\\"
        return normalize(workDir.trimEnd('/', '\\') + sep + p)
    }

    private fun normalize(p: String): String {
        if (!p.startsWith("/")) return p
        val out = ArrayList<String>()
        for (part in p.split('/')) when (part) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out += part
        }
        return "/" + out.joinToString("/")
    }

    // --- AMBuild -----------------------------------------------------------

    // AMBuild keeps its command graph in .ambuild2/graph (sqlite); command nodes
    // hold the pickled argv. Read it with the build's own Python.
    private const val AMBUILD_READER = """
import sqlite3, pickle, json, sys, os
objdir = sys.argv[1]
db = sqlite3.connect(os.path.join(objdir, '.ambuild2', 'graph'))
rows = list(db.execute('select * from nodes'))
paths = {r[0]: r[4] for r in rows}
for r in rows:
    if r[1] != 'cmd' or r[6] is None:
        continue
    try:
        argv = pickle.loads(r[6])
    except Exception:
        continue
    if isinstance(argv, (list, tuple)) and argv and 'spcomp' in os.path.basename(str(argv[0])).lower():
        folder = paths.get(r[5]) or ''
        print(json.dumps({'argv': [str(a) for a in argv], 'cwd': os.path.join(objdir, folder) if folder else objdir}))
"""

    private fun readAMBuild(dir: File, target: SpTarget): List<SpBuildCommand> {
        val python = target.which("python3") ?: target.which("python") ?: return emptyList()
        return try {
            val cmd = target.commandLine(python, listOf("-c", AMBUILD_READER.trimIndent(), target.toTarget(dir.path)))
            val out = CapturingProcessHandler(cmd).runProcess(20_000)
            out.stdoutLines.mapNotNull { line ->
                val m = Regex(""""argv":\s*(\[.*?]),\s*"cwd":\s*("(?:[^"\\]|\\.)*")""").find(line) ?: return@mapNotNull null
                val argv = jsonStrings(m.groupValues[1])
                val cwd = jsonStrings("[" + m.groupValues[2] + "]").firstOrNull()
                fromArgv(argv, cwd?.trimEnd('/'))
            }.distinct()
        } catch (e: Exception) {
            LOG.warn("cannot read AMBuild graph in $dir", e)
            emptyList()
        }
    }

    /** Minimal JSON string-array decoder (avoids a JSON dependency for one shape). */
    internal fun jsonStrings(array: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex(""""((?:[^"\\]|\\.)*)"""")
        for (m in re.findAll(array)) {
            val raw = m.groupValues[1]
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (val n = raw[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'u' -> { sb.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> sb.append(n)
                    }
                    i += 2
                } else {
                    sb.append(c); i++
                }
            }
            out += sb.toString()
        }
        return out
    }
}
