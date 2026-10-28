package org.source2mod.sourcepawn.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import org.source2mod.sourcepawn.SpFileType
import org.source2mod.sourcepawn.lsp.SpLspServerSupportProvider
import org.source2mod.sourcepawn.run.SpSaveChecker
import org.source2mod.sourcepawn.toolchain.SpToolchain
import org.source2mod.sourcepawn.toolchain.SpToolchainService
import javax.swing.JButton

class SpConfigurable(private val project: Project) :
    BoundSearchableConfigurable("SourcePawn", "SourcePawn", "settings.sourcepawn") {

    private val stored get() = SpSettings.get(project)

    /** Edited copy; [apply] copies it back. */
    private val edit = SpSettingsState()
    private var includesText = ""
    private lateinit var doctor: JBLabel
    private lateinit var dialogPanel: DialogPanel

    override fun createPanel(): DialogPanel {
        resetCopy()
        dialogPanel = panel {
            group("Toolchain") {
                row("Run tools:") {
                    comboBox(ExecutionMode.entries).bindItem(edit::executionMode.toNullableProperty())
                }
                row("WSL distro:") {
                    textField().bindText({ edit.wslDistro ?: "" }, { edit.wslDistro = it })
                        .comment("Empty = the distro of a \\\\wsl.localhost project, else the default distro")
                }
                row("Build system:") {
                    comboBox(org.source2mod.sourcepawn.toolchain.BuildSystem.entries)
                        .bindItem(edit::buildSystem.toNullableProperty())
                        .comment("spcomp and include dirs from the spcomp commands of a CMake build dir (build.ninja / build.make) " +
                            "or an AMBuild objdir; the fields below still come first")
                }
                row("Build directory:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Build Directory"), project)
                        .align(AlignX.FILL)
                        .bindText({ edit.buildDirectory ?: "" }, { edit.buildDirectory = it })
                        .comment("Empty = every CMake build dir / AMBuild objdir up to two levels under the project")
                }
                row("spcomp:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("spcomp"), project)
                        .bindText({ edit.compilerPath ?: "" }, { edit.compilerPath = it })
                        .align(AlignX.FILL)
                        .comment("Empty = \$SPCOMP, \$SOURCE2MOD_DIR/scripting, \$SOURCEMOD_DIR/scripting, then PATH. " +
                            "Linux paths (/home/...) are fine in WSL mode.")
                }
                row("Include directories:") {
                    textArea().rows(4).columns(COLUMNS_LARGE).align(AlignX.FILL)
                        .bindText({ includesText }, { includesText = it })
                        .comment("One per line. \$VAR\$, \${VAR}, \$PROJECT_DIR\$ and project-relative paths work.")
                }
                row {
                    checkBox("Also use \$SOURCEPAWN_INCLUDE, \$SOURCE2MOD_SDK/public/scripting and the include dir next to spcomp")
                        .bindSelected(edit::useEnvironmentIncludes)
                }
                row("spcomp arguments:") {
                    textField().align(AlignX.FILL).bindText({ edit.compilerArguments ?: "" }, { edit.compilerArguments = it })
                }
            }
            group("Project") {
                row("Main file:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileDescriptor("sp").withTitle("Main Plugin File"), project)
                        .align(AlignX.FILL)
                        .bindText({ edit.mainFile ?: "" }, { edit.mainFile = it })
                        .comment("Compiled by run configurations set to \"project main file\"")
                }
                row("Output directory:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Output Directory"), project)
                        .align(AlignX.FILL)
                        .bindText({ edit.outputDirectory ?: "" }, { edit.outputDirectory = it })
                        .comment("Empty = <script dir>/../plugins when the script is in a 'scripting' dir, else next to the script")
                }
            }
            group("Language Server (sourcepawn-studio)") {
                row {
                    checkBox("Enable").bindSelected(edit::languageServerEnabled)
                }
                row("Server binary:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("sourcepawn-studio"), project)
                        .align(AlignX.FILL)
                        .bindText({ edit.languageServerPath ?: "" }, { edit.languageServerPath = it })
                        .comment("Empty = PATH, then the downloaded copy (${SpToolchain.SERVER_VERSION})")
                }
                row {
                    checkBox("Check with spcomp on save").bindSelected(edit::compileOnSave)
                    checkBox("Disable linter").bindSelected(edit::linterDisable)
                }
                row("Events game name:") {
                    textField().bindText({ edit.eventsGameName ?: "" }, { edit.eventsGameName = it })
                        .comment("e.g. csgo, tf; empty = all games")
                }
            }
            group("Doctor") {
                row {
                    doctor = JBLabel("Click Resolve to see the values in use.").apply { setCopyable(true) }
                    cell(doctor).align(AlignX.FILL)
                }
                row {
                    cell(JButton("Resolve").apply { addActionListener { runDoctor() } })
                    comment("Uses the values above, applied or not")
                }
            }
        }
        return dialogPanel
    }

    private fun resetCopy() {
        edit.copyFrom(stored)
        includesText = stored.includeDirectories.joinToString("\n")
    }

    override fun reset() {
        resetCopy()
        super.reset()
    }

    override fun isModified(): Boolean {
        if (super.isModified()) return true
        return edit != stored || includesText.lines().map { it.trim() }.filter { it.isNotEmpty() } != stored.includeDirectories
    }

    override fun apply() {
        super.apply()
        edit.includeDirectories = includesText.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        stored.copyFrom(edit)
        SpToolchainService.get(project).invalidate()
    }

    private fun runDoctor() {
        // UI -> edit copy (not the stored settings)
        dialogPanel.apply()
        val scratch = SpSettingsState().also { it.copyFrom(edit) }
        doctor.text = "Resolving..."
        val modality = ModalityState.stateForComponent(doctor)
        ApplicationManager.getApplication().executeOnPooledThread {
            val html = try {
                doctorHtml(SpToolchain.of(project, scratch.also {
                    it.includeDirectories = includesText.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }.toMutableList()
                }))
            } catch (e: Exception) {
                "<html>Failed: ${StringUtil.escapeXmlEntities(e.toString())}</html>"
            }
            ApplicationManager.getApplication().invokeLater({ doctor.text = html }, modality)
        }
    }

    companion object {
        fun doctorHtml(t: SpToolchain): String {
            fun e(s: String) = StringUtil.escapeXmlEntities(s)
            fun line(label: String, r: org.source2mod.sourcepawn.toolchain.Resolved?) =
                if (r == null) "<b>$label:</b> <font color='#c75450'>not found</font><br>"
                else "<b>$label:</b> <code>${e(r.path)}</code> <i>(${e(r.source)})</i>" +
                    (if (!r.exists) " <font color='#c75450'>missing</font>" else "") + "<br>"
            val sb = StringBuilder("<html>")
            sb.append("<b>Runs on:</b> ${e(t.target.displayName)}<br>")
            t.projectDir?.let { sb.append("<b>Project dir:</b> <code>${e(it)}</code> → <code>${e(t.target.toTarget(it))}</code><br>") }
            if (t.buildDirs.isNotEmpty()) {
                sb.append("<b>Build dirs:</b><br>")
                for (b in t.buildDirs) {
                    sb.append("&nbsp;&nbsp;${b.kind.name} <code>${e(b.hostDir)}</code>: ${b.commands.size} spcomp command(s)")
                    for (c in b.commands) sb.append("<br>&nbsp;&nbsp;&nbsp;&nbsp;<code>${e(c.source.substringAfterLast('/'))}</code> → <code>${e(c.output ?: "?")}</code>")
                    sb.append("<br>")
                }
            } else if (t.settings.buildSystem != org.source2mod.sourcepawn.toolchain.BuildSystem.NONE) {
                sb.append("<b>Build dirs:</b> none found (configure the CMake / AMBuild build once)<br>")
            }
            sb.append(line("spcomp", t.compiler))
            sb.append(line("Language server", t.languageServer))
            sb.append("<b>Include directories:</b>")
            if (t.includeDirectories.isEmpty()) sb.append(" <font color='#c75450'>none</font><br>")
            else {
                sb.append("<br>")
                for (r in t.includeDirectories) {
                    sb.append("&nbsp;&nbsp;<code>${e(r.path)}</code> <i>(${e(r.source)})</i>")
                    if (!r.exists) sb.append(" <font color='#c75450'>missing</font>")
                    sb.append("<br>")
                }
            }
            val envs = listOf("SPCOMP", "SOURCE2MOD_DIR", "SOURCEMOD_DIR", "SOURCE2MOD_SDK", "SOURCEPAWN_INCLUDE")
                .mapNotNull { k -> t.env[k]?.let { "$k=${e(it)}" } }
            sb.append("<b>Environment:</b> ${if (envs.isEmpty()) "none of SPCOMP, SOURCE2MOD_DIR, SOURCEMOD_DIR, SOURCE2MOD_SDK, SOURCEPAWN_INCLUDE set" else envs.joinToString(", ")}")
            return sb.append("</html>").toString()
        }
    }
}

/** Restarts the language server when the toolchain settings change. */
class SpToolchainChangeListener(private val project: Project) : SpToolchainService.Listener {
    override fun toolchainChanged() {
        SpLspServerSupportProvider.forgetNotification(project)
        SpLspServerSupportProvider.restart(project)
        // recheck what is open: a newly set spcomp / include dir shows up without a save
        val open = FileEditorManager.getInstance(project).openFiles.filter { it.fileType == SpFileType }.map { it.path }
        if (open.isNotEmpty()) SpSaveChecker.get(project).schedule(open)
    }
}
