package org.source2mod.sourcepawn.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.source2mod.sourcepawn.SpFileType

/** Right-click a .sp file -> Run / create a SourcePawn configuration for it. */
class SpRunConfigurationProducer : LazyRunConfigurationProducer<SpRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(SpRunConfigurationType::class.java).configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: SpRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (file.fileType != SpFileType) return false
        configuration.options.useMainFile = false
        configuration.options.scriptPath = file.path
        configuration.name = file.nameWithoutExtension
        return true
    }

    override fun isConfigurationFromContext(configuration: SpRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.location?.virtualFile ?: return false
        return !configuration.options.useMainFile && configuration.options.scriptPath == file.path
    }
}
