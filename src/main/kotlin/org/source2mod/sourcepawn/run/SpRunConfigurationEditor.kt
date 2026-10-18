package org.source2mod.sourcepawn.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import javax.swing.AbstractButton
import javax.swing.ButtonGroup
import javax.swing.JComponent

class SpRunConfigurationEditor(private val project: Project) : SettingsEditor<SpRunConfiguration>() {
    private val useFile = JBRadioButton("Script file:")
    private val useMain = JBRadioButton("Project main file (Settings | SourcePawn)")
    private val script = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("sp").withTitle("SourcePawn Script"))
    }
    private val outputDir = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Output Directory"))
    }
    private val extraArgs = JBTextField()
    private val compiler = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("spcomp"))
    }
    private val overrideIncludes = JBCheckBox("Use these include directories instead of the project's:")
    private val includes = JBTextArea(3, 40)

    private val copyEnabled = JBCheckBox("Copy the .smx to:")
    private val copyTo = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Server Plugins Directory"))
    }
    private val rconEnabled = JBCheckBox("Send RCON commands")
    private val rconHost = JBTextField()
    private val rconPort = JBTextField()
    private val rconPassword = JBPasswordField()
    private val rconCommands = JBTextArea(3, 40)

    init {
        ButtonGroup().apply { add(useFile); add(useMain) }
    }

    override fun createEditor(): JComponent = panel {
        group("Compile") {
            row { cell(useFile); cell(script).align(AlignX.FILL) }
            row { cell(useMain) }
            row("Output directory:") {
                cell(outputDir).align(AlignX.FILL)
                    .comment("Empty = project setting, else <script dir>/../plugins for a 'scripting' dir, else next to the script")
            }
            row("Extra spcomp arguments:") { cell(extraArgs).align(AlignX.FILL) }
            row("spcomp override:") { cell(compiler).align(AlignX.FILL).comment("Empty = the project's spcomp") }
            row { cell(overrideIncludes) }
            row { cell(includes).align(AlignX.FILL).comment("One per line; \$VAR\$ / \${VAR} / project-relative") }
                .enabledIf(ButtonSelected(overrideIncludes))
        }
        group("After a successful compile (Run only, not Build)") {
            row { cell(copyEnabled); cell(copyTo).align(AlignX.FILL) }
            row { cell(rconEnabled) }
            indent {
                row("Host:") { cell(rconHost); label("Port:"); cell(rconPort) }
                row("Password:") { cell(rconPassword).comment("Stored in the IDE password safe") }
                row("Commands:") { cell(rconCommands).align(AlignX.FILL).comment("One per line, e.g. s2m reload or sm plugins refresh") }
            }.enabledIf(ButtonSelected(rconEnabled))
        }
    }

    override fun resetEditorFrom(c: SpRunConfiguration) {
        val o = c.options
        useMain.isSelected = o.useMainFile
        useFile.isSelected = !o.useMainFile
        script.text = o.scriptPath ?: ""
        outputDir.text = o.outputDir ?: ""
        extraArgs.text = o.extraArgs ?: ""
        compiler.text = o.compilerOverride ?: ""
        overrideIncludes.isSelected = o.overrideIncludes
        includes.text = o.includeOverrides.joinToString("\n")
        copyEnabled.isSelected = o.copyEnabled
        copyTo.text = o.copyToDir ?: ""
        rconEnabled.isSelected = o.rconEnabled
        rconHost.text = o.rconHost ?: ""
        rconPort.text = o.rconPort.toString()
        rconPassword.text = SpRunConfiguration.rconPassword(o.rconHost ?: "", o.rconPort) ?: ""
        rconCommands.text = o.rconCommands.joinToString("\n")
    }

    override fun applyEditorTo(c: SpRunConfiguration) {
        val o = c.options
        o.useMainFile = useMain.isSelected
        o.scriptPath = script.text.trim()
        o.outputDir = outputDir.text.trim()
        o.extraArgs = extraArgs.text.trim()
        o.compilerOverride = compiler.text.trim()
        o.overrideIncludes = overrideIncludes.isSelected
        o.includeOverrides = lines(includes.text)
        o.copyEnabled = copyEnabled.isSelected
        o.copyToDir = copyTo.text.trim()
        o.rconEnabled = rconEnabled.isSelected
        o.rconHost = rconHost.text.trim()
        o.rconPort = rconPort.text.trim().toIntOrNull() ?: 27015
        o.rconCommands = lines(rconCommands.text)
        val pw = String(rconPassword.password)
        if (pw != (SpRunConfiguration.rconPassword(o.rconHost ?: "", o.rconPort) ?: "")) {
            SpRunConfiguration.setRconPassword(o.rconHost ?: "", o.rconPort, pw)
        }
    }

    private fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
}

private class ButtonSelected(private val b: AbstractButton) : ComponentPredicate() {
    override fun invoke() = b.isSelected
    override fun addListener(listener: (Boolean) -> Unit) = b.addChangeListener { listener(b.isSelected) }
}
