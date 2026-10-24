package org.source2mod.sourcepawn.run

import com.intellij.build.BuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.EventResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FileMessageEventResult
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.pom.Navigatable
import java.io.File

/*
 * Build tool window events on the public com.intellij.build.events interfaces.
 * The platform's events.impl classes are @ApiStatus.Internal since 2026.2 and
 * their replacement (StartBuildEvent.builder() & co.) does not exist before it,
 * so these cover the whole since-build range.
 */

private abstract class SpBuildEvent(
    private val id: Any,
    private val parentId: Any?,
    private val message: String,
) : BuildEvent {
    private val time = System.currentTimeMillis()

    override fun getId(): Any = id
    override fun getParentId(): Any? = parentId
    override fun getEventTime(): Long = time
    override fun getMessage(): String = message
    override fun getHint(): String? = null
    override fun getDescription(): String? = null
}

private class SpStartBuildEvent(private val descriptor: BuildDescriptor, message: String) :
    SpBuildEvent(descriptor.id, null, message), StartBuildEvent {
    override fun getBuildDescriptor(): BuildDescriptor = descriptor
}

private class SpOutputEvent(buildId: Any, private val text: String, private val stdOut: Boolean) :
    SpBuildEvent(Any(), buildId, text), OutputBuildEvent {
    override fun getMessage(): String = text
    override fun isStdOut(): Boolean = stdOut

    // Abstract in OutputBuildEvent since 2026.2 (isStdOut became its default), absent before
    @Suppress("unused")
    fun getOutputType(): ProcessOutputType = if (stdOut) ProcessOutputType.STDOUT else ProcessOutputType.STDERR
}

private class SpFileMessageEvent(
    buildId: Any,
    private val kind: MessageEvent.Kind,
    private val group: String,
    private val text: String,
    private val file: File,
    private val line: Int,
) : SpBuildEvent(Any(), buildId, text), FileMessageEvent {
    private val position = FilePosition(file, line, 0)

    override fun getKind(): MessageEvent.Kind = kind
    override fun getGroup(): String = group
    override fun getDescription(): String = text
    override fun getFilePosition(): FilePosition = position

    override fun getResult(): FileMessageEventResult = object : FileMessageEventResult {
        override fun getKind(): MessageEvent.Kind = kind
        override fun getFilePosition(): FilePosition = position
        override fun getDetails(): String = text
    }

    override fun getNavigatable(project: Project): Navigatable? =
        LocalFileSystem.getInstance().findFileByIoFile(file)?.let { OpenFileDescriptor(project, it, line, 0) }
}

private class SpFinishBuildEvent(buildId: Any, message: String, private val result: EventResult) :
    SpBuildEvent(buildId, null, message), FinishBuildEvent {
    override fun getResult(): EventResult = result
}

internal object SpBuildEvents {
    fun start(descriptor: BuildDescriptor, message: String): BuildEvent = SpStartBuildEvent(descriptor, message)
    fun output(buildId: Any, text: String, stdOut: Boolean): BuildEvent = SpOutputEvent(buildId, text, stdOut)
    /** [line] is 0-based. */
    fun fileMessage(buildId: Any, kind: MessageEvent.Kind, group: String, text: String, file: File, line: Int): BuildEvent =
        SpFileMessageEvent(buildId, kind, group, text, file, line)
    fun finish(buildId: Any, message: String, result: EventResult): BuildEvent = SpFinishBuildEvent(buildId, message, result)
}
