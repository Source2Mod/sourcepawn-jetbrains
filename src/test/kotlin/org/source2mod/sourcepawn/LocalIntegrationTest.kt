package org.source2mod.sourcepawn

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.source2mod.sourcepawn.run.SpCompileRequest
import org.source2mod.sourcepawn.run.SpCompiler
import org.source2mod.sourcepawn.run.SpMessage
import org.source2mod.sourcepawn.settings.ExecutionMode
import org.source2mod.sourcepawn.settings.SpSettingsState
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.SpToolchain
import java.io.File

/**
 * Local (no WSL) compile with a Windows spcomp.exe (-Dsp.test.localspcomp, default:
 * the Source2Mod backup in C:\Users\micha\cs2win). Includes are the SDK's, copied
 * from WSL when that is reachable. Skipped when either is missing.
 */
class LocalIntegrationTest : BasePlatformTestCase() {
    private val spcomp = System.getProperty("sp.test.localspcomp",
        "C:/Users/micha/cs2win/addons_bak_s2m_20261015/source2mod/scripting/spcomp.exe")
    private val sdk = System.getProperty("sp.test.sdk", "//wsl.localhost/ubuntu-20/home/Source2Mod-sdk/public/scripting")

    fun testCompileLocally() {
        if (!File(spcomp).isFile || !File(sdk).isDirectory) return
        val dir = FileUtil.createTempDirectory("spjb-local", null)
        val scripting = File(dir, "scripting").apply { mkdirs() }
        FileUtil.copyDir(File(sdk), File(dir, "sdk/scripting"))
        File(scripting, "good.sp").writeText(WslIntegrationTest.GOOD)
        File(scripting, "bad.sp").writeText(WslIntegrationTest.GOOD.replace("{\n}", "{\n\tundefined_thing = 5;\n}"))

        val s = SpSettingsState().apply {
            executionMode = ExecutionMode.LOCAL
            compilerPath = spcomp
            includeDirectories = mutableListOf("\$PROJECT_DIR\$/sdk/scripting")
        }
        val t = SpToolchain.of(project, s)
        assertEquals(SpTarget.Local, t.target)
        // $PROJECT_DIR$ is the test project's dir, so point at the temp dir explicitly
        s.includeDirectories = mutableListOf(File(dir, "sdk/scripting").path)
        val t2 = SpToolchain.of(project, s)

        val out = File(dir, "plugins").path
        val good = SpCompiler.compile(t2, SpCompileRequest(File(scripting, "good.sp").path, out)) { _, _ -> }
        assertTrue("good.sp: ${good.messages}", good.success)
        assertTrue(File(out, "good.smx").isFile)

        val bad = SpCompiler.compile(t2, SpCompileRequest(File(scripting, "bad.sp").path, out)) { _, _ -> }
        val err = bad.messages.first { it.severity == SpMessage.Severity.ERROR }
        assertEquals(File(scripting, "bad.sp").path.replace('\\', '/').lowercase(), err.hostFile.lowercase())
        assertEquals(14, err.line)
    }
}
