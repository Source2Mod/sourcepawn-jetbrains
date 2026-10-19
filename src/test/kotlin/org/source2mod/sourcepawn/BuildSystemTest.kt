package org.source2mod.sourcepawn

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.source2mod.sourcepawn.run.SpCompiler
import org.source2mod.sourcepawn.settings.ExecutionMode
import org.source2mod.sourcepawn.settings.SpSettingsState
import org.source2mod.sourcepawn.toolchain.BuildSystem
import org.source2mod.sourcepawn.toolchain.SpBuildSystemReader
import org.source2mod.sourcepawn.toolchain.SpToolchain
import java.io.File

class BuildCommandParseTest {
    @Test fun ninja() {
        val c = SpBuildSystemReader.parseShellLine(
            "cd /home/s/sp_sample/build && /home/sdk/bin/linux/spcomp /home/s/sp_sample/scripting/a.sp " +
                "-i/home/sdk/public/scripting -i/home/s/sp_sample/../cpp_sample/scripting/include -o/home/s/sp_sample/build/a.smx")!!
        assertEquals("/home/sdk/bin/linux/spcomp", c.spcomp)
        assertEquals("/home/s/sp_sample/scripting/a.sp", c.source)
        assertEquals(listOf("/home/sdk/public/scripting", "/home/s/cpp_sample/scripting/include"), c.includes)
        assertEquals("/home/s/sp_sample/build/a.smx", c.output)
        assertEquals("/home/s/sp_sample/build", c.workDir)
    }

    @Test fun relativeToCd() {
        val c = SpBuildSystemReader.parseShellLine("cd /obj/makefiles && /x/spcomp64 ../../src/a.sp -i inc -o a.smx")!!
        assertEquals("/src/a.sp", c.source)
        assertEquals(listOf("/obj/makefiles/inc"), c.includes)
        assertEquals("/obj/makefiles/a.smx", c.output)
    }

    @Test fun windowsCmd() {
        val c = SpBuildSystemReader.parseShellLine(
            """cmd.exe /C "cd /D C:\p\build && C:\sdk\spcomp.exe C:\p\scripting\a.sp -iC:\sdk\scripting -oC:\p\build\a.smx"""")!!
        assertEquals("""C:\sdk\spcomp.exe""", c.spcomp)
        assertEquals("""C:\p\scripting\a.sp""", c.source)
        assertEquals(listOf("""C:\sdk\scripting"""), c.includes)
        assertEquals("""C:\p\build""", c.workDir)
    }

    @Test fun notSpcomp() {
        assertEquals(null, SpBuildSystemReader.parseShellLine("cd /x && /usr/bin/cmake -E copy a b"))
    }

    @Test fun json() {
        assertEquals(listOf("a\"b", "c\\d", "é"), SpBuildSystemReader.jsonStrings("""["a\"b", "c\\d", "\u00e9"]"""))
    }
}

/**
 * sp_sample (WSL) built through the plugin's build-system mode: a fresh CMake/Ninja
 * build dir and a fresh AMBuild objdir under /tmp, so the user's own build dirs are
 * left alone. Skipped when the samples or the tools are missing.
 */
class BuildSystemIntegrationTest : BasePlatformTestCase() {
    private val distro = "ubuntu-20"
    private val sample = "/home/source2mod-samples/cs2/sp_sample"
    private val sdk = "/home/Source2Mod-sdk"

    private fun available() = File("//wsl.localhost/$distro$sample/CMakeLists.txt").isFile

    private fun sh(script: String): String {
        val out = CapturingProcessHandler(GeneralCommandLine("wsl.exe", "-d", distro, "--exec", "bash", "-lc", script)).runProcess(300_000)
        assertEquals(out.stdout + out.stderr, 0, out.exitCode)
        return out.stdout
    }

    private fun toolchain(buildDir: String, kind: BuildSystem): SpToolchain {
        val s = SpSettingsState().apply {
            executionMode = ExecutionMode.WSL
            wslDistro = distro
            buildSystem = kind
            buildDirectory = buildDir
            useEnvironmentIncludes = false
        }
        return SpToolchain.of(project, s)
    }

    fun testCMakeNinja() {
        if (!available()) return
        sh("rm -rf /tmp/spjb-cmake && SOURCE2MOD_SDK=$sdk cmake -S $sample -B /tmp/spjb-cmake -G Ninja >/dev/null")
        val t = toolchain("/tmp/spjb-cmake", BuildSystem.CMAKE)
        val dir = t.buildDirs.single()
        assertEquals(BuildSystem.CMAKE, dir.kind)
        val c = dir.commands.single()
        assertEquals("$sample/scripting/sp_sample.sp", c.source)
        assertEquals("/tmp/spjb-cmake/sp_sample.smx", c.output)
        assertTrue(t.compiler!!.source, t.compiler!!.source.startsWith("CMake"))
        assertTrue(t.includeDirectories.map { it.path }.toString(), t.includeDirectories.any { it.path == "$sdk/public/scripting" })
        assertTrue(t.includeDirectories.any { it.path == "/tmp/spjb-cmake/versioning" })
        // every dir the build uses exists (cpp_sample's include goes through ../, the SDK's bin may be a symlink)
        assertTrue(t.includeDirectories.filterNot { it.exists }.toString(), t.includeDirectories.all { it.exists })

        val plan = SpCompiler.buildSystemPlan(t, dir, "sp_sample", "//wsl.localhost/$distro$sample/scripting/sp_sample.sp")
        val lines = StringBuilder()
        val r = SpCompiler.run(t, plan) { l, _ -> lines.append(l) }
        assertTrue(lines.toString(), r.success)
        assertEquals(listOf("//wsl.localhost/$distro/tmp/spjb-cmake/sp_sample.smx"), r.outputs)
        assertTrue(File(r.outputs.single()).isFile)
    }

    fun testAMBuild() {
        if (!available()) return
        sh("command -v ambuild >/dev/null || exit 0; rm -rf /tmp/spjb-ab && mkdir -p /tmp/spjb-ab && cd /tmp/spjb-ab && SOURCE2MOD_SDK=$sdk python3 $sample/configure.py >/dev/null")
        if (!File("//wsl.localhost/$distro/tmp/spjb-ab/.ambuild2/graph").isFile) return
        val t = toolchain("/tmp/spjb-ab", BuildSystem.AMBUILD)
        val dir = t.buildDirs.single()
        val c = dir.commands.single()
        assertEquals("$sample/scripting/sp_sample.sp", c.source)
        assertEquals("/tmp/spjb-ab/makefiles/ambuild/sp_sample.smx", c.output)
        assertTrue(t.includeDirectories.any { it.path == "/tmp/spjb-ab/versioning" })

        val plan = SpCompiler.buildSystemPlan(t, dir, null, null)
        val lines = StringBuilder()
        val r = SpCompiler.run(t, plan) { l, _ -> lines.append(l) }
        assertTrue(lines.toString(), r.success)
        assertTrue(File(r.outputs.single()).isFile)
    }

    fun testBrokenScriptThroughCMake() {
        if (!available()) return
        // a copy of the sample with an error, so spcomp's message travels through ninja
        // (next to cpp_sample, which the sample includes from ../cpp_sample)
        sh("rm -rf /tmp/spjb-bad && mkdir /tmp/spjb-bad && cp -r $sample /tmp/spjb-bad/sp_sample && " +
            "ln -s $sample/../cpp_sample /tmp/spjb-bad/cpp_sample && rm -rf /tmp/spjb-bad/sp_sample/cmake-build-* && " +
            "printf '\\npublic void Broken() { undefined_thing = 1; }\\n' >> /tmp/spjb-bad/sp_sample/scripting/sp_sample.sp && " +
            "SOURCE2MOD_SDK=$sdk cmake -S /tmp/spjb-bad/sp_sample -B /tmp/spjb-bad/sp_sample/build -G Ninja >/dev/null")
        val t = toolchain("/tmp/spjb-bad/sp_sample/build", BuildSystem.CMAKE)
        val lines = StringBuilder()
        val r = SpCompiler.run(t, SpCompiler.buildSystemPlan(t, t.buildDirs.single(), null, null)) { l, _ -> lines.append(l) }
        assertFalse(r.success)
        val err = r.messages.firstOrNull { it.text.contains("undefined_thing") }
        assertNotNull(lines.toString(), err)
        assertEquals("//wsl.localhost/$distro/tmp/spjb-bad/sp_sample/scripting/sp_sample.sp", err!!.hostFile)
    }
}
