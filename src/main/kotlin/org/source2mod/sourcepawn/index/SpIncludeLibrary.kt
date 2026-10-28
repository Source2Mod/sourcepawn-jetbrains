package org.source2mod.sourcepawn.index

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsListener
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.source2mod.sourcepawn.SpIcons
import org.source2mod.sourcepawn.toolchain.SpToolchainService

/**
 * The include directories (SDK, settings, build system) as a library, so their .inc
 * files are indexed for Go to Class / Go to Symbol and show under External Libraries.
 */
class SpIncludeLibraryProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val roots = roots(project)
        if (roots.isEmpty()) return emptyList()
        return listOf(SpIncludeLibrary(roots))
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> = roots(project)

    companion object {
        private val LAST = Key.create<List<VirtualFile>>("sourcepawn.include.roots")

        fun roots(project: Project): List<VirtualFile> {
            val tc = SpToolchainService.get(project).getOrSchedule { refresh(project) } ?: return emptyList()
            val fs = LocalFileSystem.getInstance()
            return tc.includeDirectories.filter { it.exists }
                .mapNotNull { fs.findFileByPath(tc.target.toHost(it.path).replace('\\', '/')) }
                .distinct()
        }

        /** After a toolchain change: tell the platform the library roots moved. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeLater({
                val old = project.getUserData(LAST) ?: emptyList()
                val new = roots(project)
                if (old == new) return@invokeLater
                project.putUserData(LAST, new)
                WriteAction.run<RuntimeException> {
                    AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(project, "SourcePawn includes", old, new, "SourcePawn includes")
                }
            }, project.disposed)
        }
    }
}

class SpIncludeLibrary(private val roots: List<VirtualFile>) : SyntheticLibrary(), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = roots
    override fun equals(other: Any?) = other is SpIncludeLibrary && other.roots == roots
    override fun hashCode() = roots.hashCode()
    override fun getPresentableText() = "SourcePawn includes"
    override fun getIcon(unused: Boolean) = SpIcons.INCLUDE
}
