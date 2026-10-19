package org.source2mod.sourcepawn

import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.source2mod.sourcepawn.lsp.SpLspServerDescriptor
import org.source2mod.sourcepawn.run.SpCompileRequest
import org.source2mod.sourcepawn.run.SpCompiler
import org.source2mod.sourcepawn.run.SpMessage
import org.source2mod.sourcepawn.settings.ExecutionMode
import org.source2mod.sourcepawn.settings.SpSettingsState
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.SpToolchain
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * End to end against the real WSL setup (ubuntu-20, Source2Mod core spcomp,
 * /opt/sourcepawn-studio): compile + error mapping, and the language server through
 * the plugin's own descriptor (command line, configuration, URI translation).
 * Skipped when that setup is not there.
 */
class WslIntegrationTest : BasePlatformTestCase() {
    private val distro = System.getProperty("sp.test.distro", "ubuntu-20")
    private val spcomp = System.getProperty("sp.test.spcomp", "/home/Source2Mod/cmake-build-relwithdebinfo-wsl/addons/source2mod/scripting/spcomp")
    private val hostRoot = "//wsl.localhost/$distro"
    private val dir = "$hostRoot/tmp/sourcepawn-jetbrains-test/scripting"

    private fun available() = File("$hostRoot$spcomp").isFile

    private fun toolchain(): SpToolchain {
        val s = SpSettingsState().apply {
            executionMode = ExecutionMode.WSL
            wslDistro = distro
            compilerPath = spcomp
        }
        return SpToolchain.of(project, s)
    }

    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(testRootDisposable, hostRoot)
        if (!available()) return
        File(dir).mkdirs()
        File(dir, "good.sp").writeText(GOOD)
        File(dir, "bad.sp").writeText(GOOD.replace("{\n}", "{\n\tundefined_thing = 5;\n}"))
    }

    fun testResolvesToolchain() {
        if (!available()) return
        val t = toolchain()
        assertEquals(SpTarget.Wsl(distro), t.target)
        assertEquals(spcomp, t.compiler!!.path)
        // the core's scripting/include (with source2mod/*.inc) comes from "next to spcomp"
        val inc = t.includeDirectories.single { it.source == "next to spcomp" }
        assertTrue(inc.exists)
        assertTrue(t.env.containsKey("HOME"))
    }

    fun testCompileGoodAndBad() {
        if (!available()) return
        val t = toolchain()
        val out = "$hostRoot/tmp/sourcepawn-jetbrains-test/plugins"
        val good = SpCompiler.compile(t, SpCompileRequest("$dir/good.sp", out)) { _, _ -> }
        assertTrue("good.sp: ${good.messages}", good.success)
        assertTrue(File("$out/good.smx").isFile)

        val lines = mutableListOf<String>()
        val bad = SpCompiler.compile(t, SpCompileRequest("$dir/bad.sp", out)) { l, _ -> lines += l }
        assertFalse(bad.success)
        val err = bad.messages.first { it.severity == SpMessage.Severity.ERROR }
        assertEquals("$dir/bad.sp", err.hostFile)
        assertEquals(14, err.line)
        assertTrue(err.text, err.text.contains("undefined_thing"))
        println(lines.joinToString(""))
    }

    fun testLanguageServerThroughDescriptor() {
        if (!available()) return
        val t = toolchain()
        val server = t.languageServer ?: return // no sourcepawn-studio in the distro
        val d = SpLspServerDescriptor(project, t, server.path)
        val client = object : LanguageClient {
            override fun telemetryEvent(o: Any?) {}
            override fun publishDiagnostics(p: PublishDiagnosticsParams) {}
            override fun showMessage(p: MessageParams) { println("server: ${p.message}") }
            override fun logMessage(p: MessageParams) {}
            override fun showMessageRequest(p: ShowMessageRequestParams) = CompletableFuture.completedFuture<MessageActionItem>(null)
            override fun configuration(p: ConfigurationParams): CompletableFuture<List<Any?>> =
                CompletableFuture.completedFuture(p.items.map { d.getWorkspaceConfiguration(it) })
        }
        val process = d.createCommandLine().createProcess()
        try {
            val launcher = LSPLauncher.createClientLauncher(client, process.inputStream, process.outputStream)
            launcher.startListening()
            val ls = launcher.remoteProxy
            val root = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(dir)!!
            val goodVf = root.findChild("good.sp")!!
            val rootUri = d.getFileUri(root)
            assertEquals("file:///tmp/sourcepawn-jetbrains-test/scripting", rootUri)

            ls.initialize(InitializeParams().apply {
                processId = ProcessHandle.current().pid().toInt()
                this.rootUri = rootUri
                workspaceFolders = listOf(WorkspaceFolder(rootUri, "test"))
                initializationOptions = d.createInitializationOptions()
                capabilities = ClientCapabilities().apply { workspace = WorkspaceClientCapabilities().apply { configuration = true } }
            }).get(30, TimeUnit.SECONDS)
            ls.initialized(InitializedParams())

            val goodUri = d.getFileUri(goodVf)
            ls.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(goodUri, "sourcepawn", 1, GOOD)))

            // "Source2ModCommands." on line 8 (0-based 7), completion right after the dot
            val line = GOOD.lines().indexOfFirst { it.contains("Source2ModCommands.") }
            val col = GOOD.lines()[line].indexOf('.') + 1
            var count = 0
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline) {
                val r = ls.textDocumentService.completion(CompletionParams(TextDocumentIdentifier(goodUri), Position(line, col))).get(30, TimeUnit.SECONDS)
                count = r?.let { if (it.isLeft) it.left.size else it.right.items.size } ?: 0
                if (count > 100) break
                Thread.sleep(1000)
            }
            assertTrue("only $count completions after Source2ModCommands.", count > 100)

            val defCol = GOOD.lines()[line].indexOf("RegisterConCommand") + 3
            val def = ls.textDocumentService.definition(DefinitionParams(TextDocumentIdentifier(goodUri), Position(line, defCol))).get(30, TimeUnit.SECONDS)
            val uri = if (def.isLeft) def.left.first().uri else def.right.first().targetUri
            val vf = d.findFileByUri(uri)
            assertNotNull("cannot map $uri back", vf)
            assertTrue(vf!!.path, vf.path.startsWith("$hostRoot/") && vf.path.endsWith("/source2mod/commands.inc"))
            println("completions=$count definition=${vf.path}")

        } finally {
            process.destroy()
        }
    }

    companion object {
        val GOOD = """
            #include <source2mod/api>
            #pragma semicolon 1
            #pragma newdecls required

            public Plugin myinfo = { name = "spjb test", author = "", description = "", version = "1.0", url = "" };

            public void OnPluginStart()
            {
            	Source2ModCommands.RegisterConCommand("spjb_test", Cmd_Test);
            }

            public void Cmd_Test(Source2ModCommandContext context, Source2ModCommandArgs args, bool post)
            {
            }
        """.trimIndent() + "\n"
    }
}
