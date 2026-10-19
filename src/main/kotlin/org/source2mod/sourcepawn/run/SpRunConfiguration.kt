package org.source2mod.sourcepawn.run

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.util.execution.ParametersListUtil
import org.source2mod.sourcepawn.SpIcons
import org.source2mod.sourcepawn.toolchain.SpOverrides
import org.source2mod.sourcepawn.toolchain.SpToolchain
import java.io.File

class SpRunConfigurationType : ConfigurationTypeBase(
    ID, "SourcePawn", "Compile a SourcePawn plugin with spcomp, then optionally deploy it",
    NotNullLazyValue.createValue { SpIcons.RUN_CONFIG },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun getId() = ID
            override fun createTemplateConfiguration(project: Project): RunConfiguration =
                SpRunConfiguration(project, this, "SourcePawn")

            override fun getOptionsClass() = SpRunOptions::class.java
        })
    }

    companion object {
        const val ID = "SourcePawnRunConfiguration"
    }
}

class SpRunOptions : LocatableRunConfigurationOptions() {
    /** true = the project's main file (Settings | SourcePawn), false = [scriptPath]. */
    var useMainFile by property(false)
    var scriptPath by string("")
    var outputDir by string("")
    var extraArgs by string("")

    /** Build through the project's CMake / AMBuild build dir instead of calling spcomp. */
    var useBuildSystem by property(false)
    /** cmake --build --target; empty = all. Unused for AMBuild (it builds the objdir). */
    var cmakeTarget by string("")

    var compilerOverride by string("")
    var overrideIncludes by property(false)
    var includeOverrides by list<String>()

    /** After-steps, only for Run (not for Build). */
    var copyEnabled by property(false)
    var copyToDir by string("")
    var rconEnabled by property(false)
    var rconHost by string("127.0.0.1")
    var rconPort by property(27015)
    var rconCommands by list<String>()
}

class SpRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<SpRunOptions>(project, factory, name) {

    public override fun getOptions(): SpRunOptions = super.getOptions() as SpRunOptions

    override fun getConfigurationEditor() = SpRunConfigurationEditor(project)

    override fun checkConfiguration() {
        if (options.useBuildSystem) {
            // the script is optional here: without one the whole build counts, every .smx is copied
        } else if (options.useMainFile) {
            if (org.source2mod.sourcepawn.settings.SpSettings.get(project).mainFile.isNullOrBlank())
                throw RuntimeConfigurationError("No main file set in Settings | Languages & Frameworks | SourcePawn")
        } else if (options.scriptPath.isNullOrBlank()) {
            throw RuntimeConfigurationError("Script file is not set")
        }
        if (options.copyEnabled && options.copyToDir.isNullOrBlank()) throw RuntimeConfigurationError("Copy target directory is not set")
        if (options.rconEnabled && options.rconHost.isNullOrBlank()) throw RuntimeConfigurationError("RCON host is not set")
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment) = SpRunState(environment, this)

    fun toolchain(): SpToolchain = SpToolchain.of(
        project,
        SpOverrides(
            compilerPath = options.compilerOverride?.takeIf { it.isNotBlank() },
            includeDirectories = if (options.overrideIncludes) options.includeOverrides.toList() else null,
        ),
    )

    /** Host path of the script to compile. */
    fun scriptHostPath(toolchain: SpToolchain): String {
        val raw = if (options.useMainFile) toolchain.settings.mainFile else options.scriptPath
        if (raw.isNullOrBlank()) throw ExecutionException("No script to compile")
        return toolchain.target.toHost(toolchain.userPathToTarget(raw))
    }

    fun compileRequest(toolchain: SpToolchain): SpCompileRequest {
        val script = scriptHostPath(toolchain)
        if (!File(script).isFile) throw ExecutionException("Script '$script' does not exist")
        val out = options.outputDir?.takeIf { it.isNotBlank() }
            ?.let { toolchain.target.toHost(toolchain.userPathToTarget(it)) }
            ?: SpCompiler.defaultOutputDir(toolchain, script)
        return SpCompileRequest(script, out, ParametersListUtil.parse(toolchain.expand(options.extraArgs ?: "")))
    }

    /** What Build / Run executes: spcomp, or the build system when [SpRunOptions.useBuildSystem]. */
    fun buildPlan(toolchain: SpToolchain): SpBuildPlan {
        if (!options.useBuildSystem) return SpCompiler.plan(toolchain, compileRequest(toolchain))
        val dir = toolchain.buildDirs.firstOrNull()
            ?: throw ExecutionException("No CMake build dir or AMBuild objdir found " +
                "(Settings | Languages & Frameworks | SourcePawn | Build system); configure the build once first")
        val raw = if (options.useMainFile) toolchain.settings.mainFile else options.scriptPath
        val script = raw?.takeIf { it.isNotBlank() }?.let { toolchain.target.toHost(toolchain.userPathToTarget(it)) }
        return SpCompiler.buildSystemPlan(toolchain, dir, options.cmakeTarget, script)
    }

    fun rconPasswordAttributes() = rconAttributes(options.rconHost ?: "", options.rconPort)


    companion object {
        /** RCON passwords live in the IDE password safe, keyed by host:port, not in the run configuration XML. */
        fun rconAttributes(host: String, port: Int) =
            CredentialAttributes(generateServiceName("SourcePawn RCON", "$host:$port"))

        fun rconPassword(host: String, port: Int): String? = PasswordSafe.instance.getPassword(rconAttributes(host, port))
        fun setRconPassword(host: String, port: Int, password: String?) =
            PasswordSafe.instance.setPassword(rconAttributes(host, port), password?.takeIf { it.isNotEmpty() })
    }
}
