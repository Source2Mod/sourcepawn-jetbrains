package org.source2mod.sourcepawn.lsp

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.SpToolchain
import org.source2mod.sourcepawn.toolchain.SpToolchainService
import java.io.File
import java.util.zip.ZipInputStream

/** Downloads the sourcepawn-studio release (Sarrus1/sourcepawn-studio, MIT) for the project's target. */
object SpServerDownloader {
    private const val BASE = "https://github.com/Sarrus1/sourcepawn-studio/releases/download/"

    fun download(project: Project, toolchain: SpToolchain) {
        object : Task.Backgroundable(project, "Downloading SourcePawn language server", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val dest = when (val t = toolchain.target) {
                        is SpTarget.Wsl -> downloadWsl(t, toolchain, indicator)
                        SpTarget.Local -> downloadLocal(toolchain, indicator)
                    }
                    notify(project, "SourcePawn language server installed", dest, NotificationType.INFORMATION)
                    SpLspServerSupportProvider.forgetNotification(project)
                    SpToolchainService.get(project).invalidate()
                } catch (e: Exception) {
                    notify(project, "SourcePawn language server download failed", e.message ?: e.toString(), NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun asset(os: String): String {
        val arch = if (SystemInfo.isAarch64) "arm64" else "amd64"
        val ext = if (os == "windows") "zip" else "tar.gz"
        return "sourcepawn-studio-${SpToolchain.SERVER_VERSION}-$os-$arch.$ext"
    }

    private fun downloadLocal(toolchain: SpToolchain, indicator: ProgressIndicator): String {
        val os = when {
            SystemInfo.isWindows -> "windows"
            SystemInfo.isMac -> "darwin"
            else -> "linux"
        }
        val name = asset(os)
        val exe = File(toolchain.downloadedServerPath())
        val dir = exe.parentFile
        FileUtil.delete(dir)
        dir.mkdirs()
        val archive = File(dir, name)
        fetch(name, archive, indicator)
        if (name.endsWith(".zip")) unzip(archive, dir) else untar(SpTarget.Local, archive.path, dir.path)
        archive.delete()
        val found = dir.walk().firstOrNull { it.isFile && it.name == exe.name }
            ?: error("${exe.name} not found in $name")
        if (found != exe) found.renameTo(exe)
        exe.setExecutable(true)
        return exe.path
    }

    private fun downloadWsl(t: SpTarget.Wsl, toolchain: SpToolchain, indicator: ProgressIndicator): String {
        checkGlibc(t)
        val exe = toolchain.downloadedServerPath()
        val dir = exe.substringBeforeLast('/')
        val name = asset("linux")
        val tmp = FileUtil.createTempFile("sourcepawn-studio", ".tar.gz", true)
        try {
            fetch(name, tmp, indicator)
            indicator.text = "Unpacking in WSL ($dir)"
            run(t, "mkdir", "-p", dir)
            untar(t, t.toTarget(tmp.path), dir)
            // the archive may hold the binary in a subdirectory
            run(t, "sh", "-c", "f=\$(find \"$dir\" -type f -name sourcepawn-studio | head -n1); [ \"\$f\" = \"$exe\" ] || mv \"\$f\" \"$exe\"; chmod +x \"$exe\"")
        } finally {
            tmp.delete()
        }
        if (!t.exists(exe)) error("$exe not found after unpacking $name")
        return "$exe (${t.displayName})"
    }

    /** The prebuilt Linux server needs glibc 2.32+; ubuntu-20 has 2.31. */
    private fun checkGlibc(t: SpTarget.Wsl) {
        val out = run(t, "ldd", "--version")
        val v = Regex("""(\d+)\.(\d+)\s*$""", RegexOption.MULTILINE).find(out.lineSequence().firstOrNull() ?: "") ?: return
        val (major, minor) = v.destructured
        if (major.toInt() < 2 || (major.toInt() == 2 && minor.toInt() < 32)) {
            error("The prebuilt sourcepawn-studio needs glibc 2.32+, ${t.distro} has $major.$minor. " +
                "Build it in WSL instead (git clone https://github.com/Sarrus1/sourcepawn-studio; cargo build --release) " +
                "and set its path in Settings | Languages & Frameworks | SourcePawn.")
        }
    }

    private fun fetch(name: String, dest: File, indicator: ProgressIndicator) {
        indicator.text = "Downloading $name"
        HttpRequests.request(BASE + SpToolchain.SERVER_VERSION + "/" + name)
            .productNameAsUserAgent()
            .saveToFile(dest, indicator)
    }

    private fun unzip(zip: File, dir: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(dir, e.name).canonicalFile
                if (!out.path.startsWith(dir.canonicalPath)) error("bad zip entry ${e.name}")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
    }

    private fun untar(t: SpTarget, archive: String, dir: String) {
        run(t, "tar", "-xzf", archive, "-C", dir)
    }

    private fun run(t: SpTarget, exe: String, vararg args: String): String {
        val out = CapturingProcessHandler(t.commandLine(exe, args.toList())).runProcess(120_000)
        if (out.exitCode != 0) error("$exe ${args.joinToString(" ")} failed: ${out.stderr.ifBlank { out.stdout }.trim()}")
        return out.stdout
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) =
        NotificationGroupManager.getInstance().getNotificationGroup("SourcePawn")
            .createNotification(title, content, type).notify(project)
}
