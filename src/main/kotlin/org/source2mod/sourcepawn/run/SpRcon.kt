package org.source2mod.sourcepawn.run

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal Source RCON client (https://developer.valvesoftware.com/wiki/Source_RCON_Protocol). */
class SpRcon(host: String, port: Int, password: String, timeoutMs: Int = 5000) : Closeable {
    private val socket = Socket()
    private val input: DataInputStream
    private var nextId = 1

    init {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        input = DataInputStream(socket.getInputStream().buffered())
        val id = send(AUTH, password)
        // servers answer auth with an empty RESPONSE_VALUE first, then AUTH_RESPONSE
        while (true) {
            val p = read()
            if (p.type == AUTH_RESPONSE) {
                if (p.id == -1 || p.id != id) throw IOException("RCON authentication failed (wrong password?)")
                break
            }
        }
    }

    /** Runs [command]; multi-packet replies are collected by sending an empty marker packet after it. */
    fun exec(command: String): String {
        val id = send(EXEC, command)
        val marker = send(RESPONSE_VALUE, "")
        val sb = StringBuilder()
        try {
            while (true) {
                val p = read()
                if (p.id == marker) break
                if (p.id == id) sb.append(p.body)
            }
        } catch (_: SocketTimeoutException) {
            // some servers (CS2) never answer the marker; what came so far is the reply
        }
        return sb.toString()
    }

    private fun send(type: Int, body: String): Int {
        val id = nextId++
        val bytes = body.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 4 + 4 + bytes.size + 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(4 + 4 + bytes.size + 2).putInt(id).putInt(type).put(bytes).put(0).put(0)
        socket.getOutputStream().apply { write(buf.array()); flush() }
        return id
    }

    private class Packet(val id: Int, val type: Int, val body: String)

    private fun read(): Packet {
        val size = Integer.reverseBytes(input.readInt())
        if (size < 10 || size > 1 shl 20) throw IOException("bad RCON packet size $size")
        val data = ByteArray(size)
        input.readFully(data)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val id = b.int
        val type = b.int
        val body = String(data, 8, (size - 10).coerceAtLeast(0), Charsets.UTF_8)
        return Packet(id, type, body)
    }

    override fun close() = socket.close()

    private companion object {
        const val AUTH = 3
        const val AUTH_RESPONSE = 2
        const val EXEC = 2
        const val RESPONSE_VALUE = 0
    }
}
