package org.source2mod.sourcepawn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.source2mod.sourcepawn.run.SpCompiler
import org.source2mod.sourcepawn.run.SpMessage
import org.source2mod.sourcepawn.toolchain.SpTarget
import org.source2mod.sourcepawn.toolchain.WslPaths

class WslPathsTest {
    @Test fun distro() {
        assertEquals("ubuntu-20", WslPaths.distroOf("//wsl.localhost/ubuntu-20/home/x"))
        assertEquals("ubuntu-20", WslPaths.distroOf("\\\\wsl$\\ubuntu-20\\home\\x"))
        assertEquals("Ubuntu", WslPaths.distroOf("file://wsl.localhost/Ubuntu/home"))
        assertNull(WslPaths.distroOf("C:/Users/x"))
    }

    @Test fun toLinux() {
        assertEquals("/home/a/b.sp", WslPaths.toLinux("//wsl.localhost/ubuntu-20/home/a/b.sp"))
        assertEquals("/home/a/b.sp", WslPaths.toLinux("\\\\wsl.localhost\\ubuntu-20\\home\\a\\b.sp"))
        assertEquals("/", WslPaths.toLinux("//wsl.localhost/ubuntu-20"))
        assertEquals("/mnt/c/Users/x", WslPaths.toLinux("C:\\Users\\x"))
        assertEquals("/mnt/d", WslPaths.toLinux("D:/"))
        assertEquals("/opt/x", WslPaths.toLinux("/opt/x"))
    }

    @Test fun toHost() {
        assertEquals("//wsl.localhost/ubuntu-20/home/x", WslPaths.toHost("/home/x", "ubuntu-20"))
        assertEquals("C:/Users/x", WslPaths.toHost("/mnt/c/Users/x", "ubuntu-20"))
        assertEquals("rel/x", WslPaths.toHost("rel/x", "ubuntu-20"))
    }

    @Test fun uris() {
        assertEquals("file:///home/a%20b/x.sp", WslPaths.linuxPathToUri("/home/a b/x.sp"))
        assertEquals("file:///home/x.sp", WslPaths.uriToLinux("file://wsl.localhost/ubuntu-20/home/x.sp"))
        assertEquals("file:///home/x.sp", WslPaths.uriToLinux("file:////wsl$/ubuntu-20/home/x.sp"))
        assertEquals("file:///mnt/c/x.sp", WslPaths.uriToLinux("file:///C:/x.sp"))
        assertEquals("//wsl.localhost/ubuntu-20/home/a b/x.sp", WslPaths.uriToHostPath("file:///home/a%20b/x.sp", "ubuntu-20"))
        assertEquals("C:/x.sp", WslPaths.uriToHostPath("file:///mnt/c/x.sp", "ubuntu-20"))
        assertEquals("C:/x.sp", WslPaths.uriToHostPath("file:///C:/x.sp", "ubuntu-20"))
        assertNull(WslPaths.uriToHostPath("untitled:1", "ubuntu-20"))
    }

    @Test fun spcompMessages() {
        val t = SpTarget.Wsl("ubuntu-20")
        val m = SpCompiler.parse("/home/p/scripting/a.sp(12) : error 017: undefined symbol \"x\"", t, null)!!
        assertEquals("//wsl.localhost/ubuntu-20/home/p/scripting/a.sp", m.hostFile)
        assertEquals(12, m.line)
        assertEquals(SpMessage.Severity.ERROR, m.severity)
        assertEquals("017", m.code)
        assertEquals("undefined symbol \"x\"", m.text)

        val w = SpCompiler.parse("/home/p/a.inc(3 -- 5) : warning 204: symbol is assigned a value that is never used: \"y\"", t, null)!!
        assertEquals(3, w.line)
        assertEquals(SpMessage.Severity.WARNING, w.severity)

        val f = SpCompiler.parse("/home/p/a.sp(1) : fatal error 183: cannot read from file: \"nope\"", t, null)!!
        assertEquals(SpMessage.Severity.ERROR, f.severity)

        assertNull(SpCompiler.parse("Code size:         3192 bytes", t, null))
    }
}
