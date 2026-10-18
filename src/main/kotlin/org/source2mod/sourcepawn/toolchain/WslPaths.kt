package org.source2mod.sourcepawn.toolchain

import java.net.URI

/**
 * Path translation between the Windows IDE and a WSL distro, the same rules as
 * /opt/sourcepawn-studio/wsl-lsp-proxy.py:
 *
 *   \\wsl.localhost\<distro>\home\x  <->  /home/x
 *   \\wsl$\<distro>\home\x            ->  /home/x
 *   C:\Users\x                       <->  /mnt/c/Users/x
 *
 * and the URI forms of both (file://wsl.localhost/<distro>/home/x <-> file:///home/x).
 */
object WslPaths {
    private val UNC = Regex("""^(?:\\\\|//)wsl(?:\.localhost|\$)[\\/]([^\\/]+)(.*)$""", RegexOption.IGNORE_CASE)
    private val UNC_URI = Regex("""^file:/{2,4}wsl(?:\.localhost|\$|%24)/([^/]+)(/.*)?$""", RegexOption.IGNORE_CASE)
    private val DRIVE = Regex("""^([A-Za-z]):[\\/]?(.*)$""")
    private val MNT = Regex("""^/mnt/([a-zA-Z])(?:/(.*))?$""")

    /** The distro of a \\wsl.localhost\<distro>\... path (or its // or URI form), else null. */
    fun distroOf(hostPath: String): String? =
        UNC.find(hostPath)?.groupValues?.get(1) ?: UNC_URI.find(hostPath)?.groupValues?.get(1)

    fun isWslPath(hostPath: String) = distroOf(hostPath) != null

    /**
     * Host (Windows) path -> path inside the distro. Paths that already are Linux
     * paths are returned as they are; Windows drive paths go to /mnt/<drive>.
     */
    fun toLinux(hostPath: String): String {
        UNC.find(hostPath)?.let { m ->
            val rest = m.groupValues[2].replace('\\', '/')
            return rest.ifEmpty { "/" }
        }
        DRIVE.find(hostPath)?.let { m ->
            val rest = m.groupValues[2].replace('\\', '/').trimEnd('/')
            return "/mnt/" + m.groupValues[1].lowercase() + if (rest.isEmpty()) "" else "/$rest"
        }
        return hostPath.replace('\\', '/')
    }

    /** Linux path inside [distro] -> the path Windows (and the IDE) uses for it. */
    fun toHost(linuxPath: String, distro: String): String {
        if (!linuxPath.startsWith("/")) return linuxPath
        MNT.find(linuxPath)?.let { m ->
            val rest = m.groupValues[2]
            return m.groupValues[1].uppercase() + ":/" + rest
        }
        return "//wsl.localhost/$distro$linuxPath"
    }

    /** An IDE URI (file://wsl.localhost/<distro>/x, file:////wsl$/..., file:///C:/x) -> server URI (file:///x, file:///mnt/c/x). */
    fun uriToLinux(uri: String): String {
        UNC_URI.find(uri)?.let { m -> return "file://" + (m.groupValues[2].ifEmpty { "/" }) }
        val drive = Regex("""^file:///([A-Za-z])(?::|%3A)(/.*)?$""", RegexOption.IGNORE_CASE).find(uri)
        if (drive != null) return "file:///mnt/" + drive.groupValues[1].lowercase() + drive.groupValues[2]
        return uri
    }

    /** A server URI (file:///home/x) -> the path the IDE uses (//wsl.localhost/<distro>/home/x), or null if it is not a file URI. */
    fun uriToHostPath(uri: String, distro: String): String? {
        if (!uri.startsWith("file:")) return null
        UNC_URI.find(uri)?.let { m -> return "//wsl.localhost/${m.groupValues[1]}${decode(m.groupValues[2])}" }
        val path = runCatching { URI(uri).path }.getOrNull() ?: return null
        if (Regex("^/[A-Za-z]:").containsMatchIn(path)) return path.substring(1)
        return toHost(path, distro)
    }

    fun linuxPathToUri(linuxPath: String): String = URI("file", "", linuxPath, null).toASCIIString()

    private fun decode(s: String) = runCatching { URI("file://$s").path }.getOrDefault(s)
}
