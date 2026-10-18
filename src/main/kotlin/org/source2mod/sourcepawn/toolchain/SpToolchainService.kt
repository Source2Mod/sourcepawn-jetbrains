package org.source2mod.sourcepawn.toolchain

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Caches the resolved toolchain: resolving it can start wsl.exe (environment,
 * existence checks over \\wsl.localhost), which must not happen on the EDT or on
 * every keystroke.
 */
@Service(Service.Level.PROJECT)
class SpToolchainService(private val project: Project) : Disposable {
    @Volatile private var cached: SpToolchain? = null
    private val computing = AtomicBoolean(false)

    /** Resolved toolchain, computing it on the calling (background) thread if needed. */
    fun get(): SpToolchain {
        cached?.let { return it }
        val t = SpToolchain.of(project)
        // touch the lazies here, in the background, not later on the EDT
        t.compiler; t.includeDirectories; t.languageServer
        cached = t
        return t
    }

    /** Cached value, or null after scheduling a background resolve that calls [onReady]. */
    fun getOrSchedule(onReady: (SpToolchain) -> Unit): SpToolchain? {
        cached?.let { return it }
        if (computing.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    onReady(get())
                } finally {
                    computing.set(false)
                }
            }
        }
        return null
    }

    /** Settings changed: drop the cache and tell listeners (the language server restarts). */
    fun invalidate() {
        cached = null
        SpTarget.clearCaches()
        project.messageBus.syncPublisher(TOPIC).toolchainChanged()
    }

    override fun dispose() {}

    fun interface Listener {
        fun toolchainChanged()
    }

    companion object {
        @JvmField val TOPIC = Topic.create("SourcePawn toolchain", Listener::class.java)
        fun get(project: Project): SpToolchainService = project.service()
    }
}
