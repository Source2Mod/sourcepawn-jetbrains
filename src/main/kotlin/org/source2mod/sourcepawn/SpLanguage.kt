package org.source2mod.sourcepawn

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon

object SpLanguage : Language("SourcePawn") {
    private fun readResolve(): Any = SpLanguage
    override fun getDisplayName() = "SourcePawn"
    override fun isCaseSensitive() = true
}

object SpIcons {
    @JvmField val FILE: Icon = IconLoader.getIcon("/icons/sourcepawn.svg", SpIcons::class.java)
    @JvmField val INCLUDE: Icon = IconLoader.getIcon("/icons/sourcepawn-inc.svg", SpIcons::class.java)
    @JvmField val RUN_CONFIG: Icon = IconLoader.getIcon("/icons/sourcepawn-run.svg", SpIcons::class.java)
}

object SpFileType : LanguageFileType(SpLanguage) {
    const val NAME = "SourcePawn"
    override fun getName() = NAME
    override fun getDescription() = "SourcePawn script"
    override fun getDefaultExtension() = "sp"
    override fun getIcon(): Icon = SpIcons.FILE
}

object SpIncludeFileType : LanguageFileType(SpLanguage, true) {
    const val NAME = "SourcePawn Include"
    override fun getName() = NAME
    override fun getDisplayName() = NAME
    override fun getDescription() = "SourcePawn include"
    override fun getDefaultExtension() = "inc"
    override fun getIcon(): Icon = SpIcons.INCLUDE
}

fun VirtualFile.isSourcePawn(): Boolean {
    val ft = fileType
    return ft == SpFileType || ft == SpIncludeFileType
}
