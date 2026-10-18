package org.source2mod.sourcepawn.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

enum class ExecutionMode(val label: String) {
    AUTO("Auto (WSL when the project is under \\\\wsl.localhost)"),
    LOCAL("Local (this machine)"),
    WSL("WSL"),
    ;

    override fun toString() = label
}

class SpSettingsState : BaseState() {
    /** Where spcomp and the language server run. */
    var executionMode by enum(ExecutionMode.AUTO)
    /** WSL distro; empty = the project's distro, else the default one. */
    var wslDistro by string("")

    /** sourcepawn-studio binary; empty = PATH, then the downloaded copy. */
    var languageServerPath by string("")
    var languageServerEnabled by property(true)

    /** spcomp; empty = $SPCOMP, then $SOURCE2MOD_SDK/..., then PATH. */
    var compilerPath by string("")
    /** One per line; $VAR$ / ${VAR} and project-relative paths allowed. */
    var includeDirectories by list<String>()
    /** Also add $SOURCEPAWN_INCLUDE, $SOURCE2MOD_SDK/public/scripting and spcomp's include dir. */
    var useEnvironmentIncludes by property(true)
    /** Extra spcomp arguments, shell-style quoting. */
    var compilerArguments by string("")

    /**
     * Take spcomp and the include dirs from the spcomp commands of a CMake build
     * dir or an AMBuild objdir. Auto finds them up to two levels under the project.
     */
    var buildSystem by enum(org.source2mod.sourcepawn.toolchain.BuildSystem.AUTO)
    /** The build dir / objdir; empty = search the project. */
    var buildDirectory by string("")

    /** Main plugin file (for the run configuration's "project main file"). */
    var mainFile by string("")
    /** Where .smx files go; empty = <script dir>/../plugins (or next to the script). */
    var outputDirectory by string("")

    /** Run spcomp on save and show its messages in the editor (SpSaveChecker). */
    var compileOnSave by property(true)
    var linterDisable by property(false)
    var eventsGameName by string("")
}

@Service(Service.Level.PROJECT)
@State(name = "SourcePawnSettings", storages = [Storage("sourcepawn.xml")])
class SpSettings : SimplePersistentStateComponent<SpSettingsState>(SpSettingsState()) {
    companion object {
        fun get(project: Project): SpSettingsState = project.service<SpSettings>().state
    }
}
