package org.source2mod.sourcepawn.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import org.eclipse.lsp4j.ConfigurationItem
import org.source2mod.sourcepawn.isSourcePawn
import org.source2mod.sourcepawn.settings.SpConfigurable
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.SpToolchain
import org.source2mod.sourcepawn.toolchain.SpToolchainService
import org.source2mod.sourcepawn.toolchain.WslPaths
import java.util.concurrent.ConcurrentHashMap

class SpLspServerSupportProvider : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        if (!file.isSourcePawn()) return
        val service = SpToolchainService.get(project)
        val toolchain = service.getOrSchedule {
            // resolved in the background: ask the manager to call fileOpened again
            LspServerManager.getInstance(project).startServersIfNeeded(SpLspServerSupportProvider::class.java)
        } ?: return
        if (!toolchain.settings.languageServerEnabled) return
        val server = toolchain.languageServer
        if (server == null || !server.exists) {
            notifyMissingServer(project, toolchain)
            return
        }
        serverStarter.ensureServerStarted(SpLspServerDescriptor(project, toolchain, server.path))
    }

    companion object {
        private val notified = ConcurrentHashMap.newKeySet<Project>()

        fun servers(project: Project): Collection<LspServer> =
            LspServerManager.getInstance(project).getServersForProvider(SpLspServerSupportProvider::class.java)

        fun restart(project: Project) =
            LspServerManager.getInstance(project).stopAndRestartIfNeeded(SpLspServerSupportProvider::class.java)

        private fun notifyMissingServer(project: Project, toolchain: SpToolchain) {
            if (!notified.add(project)) return
            val where = toolchain.languageServer?.let { "'${it.path}' (from ${it.source}) does not exist" }
                ?: "sourcepawn-studio is not set in the settings, not on the PATH (${toolchain.target.displayName}) and not downloaded"
            NotificationGroupManager.getInstance().getNotificationGroup("SourcePawn")
                .createNotification("SourcePawn language server not found", where, NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Download ${SpToolchain.SERVER_VERSION}") {
                    SpServerDownloader.download(project, toolchain)
                })
                .addAction(NotificationAction.createSimple("Settings...") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, SpConfigurable::class.java)
                })
                .notify(project)
        }

        internal fun forgetNotification(project: Project) = notified.remove(project)
    }
}

/**
 * sourcepawn-studio, started locally or through `wsl.exe --exec`. For WSL the URIs
 * are translated here (what wsl-lsp-proxy.py did): the IDE's
 * file://wsl.localhost/<distro>/x is file:///x for the server and back.
 */
class SpLspServerDescriptor(
    project: Project,
    private val toolchain: SpToolchain,
    private val serverPath: String,
) : ProjectWideLspServerDescriptor(project, "SourcePawn") {
    private val target = toolchain.target

    override fun isSupportedFile(file: VirtualFile) = file.isSourcePawn()

    override fun getLanguageId(file: VirtualFile) = "sourcepawn"

    override fun createCommandLine(): GeneralCommandLine =
        target.commandLine(serverPath, listOf("--disable-telemetry"), toolchain.projectDir)

    override fun getFileUri(file: VirtualFile): String {
        if (target !is SpTarget.Wsl) return super.getFileUri(file)
        return WslPaths.linuxPathToUri(WslPaths.toLinux(file.path))
    }

    override fun findFileByUri(fileUri: String): VirtualFile? {
        if (target !is SpTarget.Wsl) return super.findFileByUri(fileUri)
        val host = WslPaths.uriToHostPath(fileUri, target.distro) ?: return null
        return findLocalFileByPath(host)
    }

    override fun createInitializationOptions(): Any = serverConfig()

    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? =
        if (item.section == null || item.section == "SourcePawnLanguageServer") serverConfig() else null

    /** The `SourcePawnLanguageServer` section, same keys as the VS Code extension. */
    private fun serverConfig(): Map<String, Any?> {
        val s = toolchain.settings
        return mapOf(
            "includeDirectories" to toolchain.includeDirectories.map { it.path },
            "compiler" to mapOf(
                "path" to toolchain.compiler?.path,
                "arguments" to toolchain.compilerArguments,
                // spcomp-on-save is done by SpSaveChecker: the server always adds
                // --syntax-only, which in SourcePawn 2.0 skips semantic analysis
                // (no "undefined symbol" errors), and it only sets up its checks
                // for files it had read when it started
                "onSave" to false,
            ),
            "linter" to mapOf("disable" to s.linterDisable),
            "eventsGameName" to s.eventsGameName?.takeIf { it.isNotBlank() },
            // hover action links are VS Code command URIs; they do nothing here
            "hover" to mapOf("actions" to mapOf("enable" to false)),
        )
    }
}
