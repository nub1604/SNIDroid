package de.silge.snidroid.fxpak

import de.silge.snidroid.fxpak.Usb2Snes.OP_BOOT
import de.silge.snidroid.fxpak.Usb2Snes.OP_INFO
import de.silge.snidroid.fxpak.Usb2Snes.OP_LS
import de.silge.snidroid.fxpak.Usb2Snes.OP_MKDIR
import de.silge.snidroid.fxpak.Usb2Snes.OP_MV
import de.silge.snidroid.fxpak.Usb2Snes.OP_PUT
import de.silge.snidroid.fxpak.Usb2Snes.OP_RESPONSE
import de.silge.snidroid.fxpak.Usb2Snes.OP_RM
import de.silge.snidroid.fxpak.Usb2Snes.PACKET_SIZE
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** Byte transport used by [FxPakClient]; implemented by the USB CDC-ACM port. */
interface SerialLink {
    fun write(data: ByteArray, length: Int = data.size)
    fun readExact(dst: ByteArray, length: Int = dst.size)
}

/** Error code reported by the FXPak itself; the link is still in sync. */
class FxPakDeviceException(val op: String, val code: Int) :
    IOException("$op: FXPak responded with error code $code")

data class FxPakInfo(val firmwareVersion: String, val deviceName: String, val currentRom: String)

data class DirEntry(val name: String, val isDirectory: Boolean)

/**
 * FXPak Pro file operations over the usb2snes protocol.
 * Ported from SNI's fxpakpro driver (info.go, ls.go, mkdir.go, putfile.go, boot.go).
 * Not thread-safe; callers must serialize access.
 */
class FxPakClient(private val link: SerialLink) {

    fun info(): FxPakInfo {
        val resp = transact("info", Usb2Snes.command(OP_INFO))
        return FxPakInfo(
            firmwareVersion = Usb2Snes.cString(resp, 260, 260 + 64),
            deviceName = Usb2Snes.cString(resp, 260 + 64, 260 + 128),
            currentRom = Usb2Snes.cString(resp, 16, 252),
        )
    }

    fun list(path: String): List<DirEntry> {
        val nameLen = path.toByteArray(Charsets.UTF_8).size.toLong()
        val resp = transact("ls", Usb2Snes.command(OP_LS, name = path, size = nameLen))
        val size = Usb2Snes.getUInt32BE(resp, Usb2Snes.SIZE_OFFSET)
        if (size != 1L) throw IOException("ls: unexpected response size $size")

        val entries = ArrayList<DirEntry>()
        val buf = ByteArray(PACKET_SIZE)
        while (true) {
            link.readExact(buf)
            var i = 0
            var nextPacket = false
            while (i < PACKET_SIZE) {
                when (buf[i].toInt() and 0xFF) {
                    0xFF -> return entries          // end of listing
                    0x02 -> { nextPacket = true; break } // continue in next packet
                }
                val isDir = buf[i].toInt() == 0
                i++
                val start = i
                while (i < PACKET_SIZE && buf[i] != 0.toByte()) i++
                if (i >= PACKET_SIZE) throw IOException("ls: malformed packet")
                val name = String(buf, start, i - start, Charsets.UTF_8)
                if (name != "." && name != "..") entries += DirEntry(name, isDir)
                i++
            }
            if (!nextPacket && i >= PACKET_SIZE && buf[PACKET_SIZE - 1] != 0.toByte()) {
                throw IOException("ls: malformed packet")
            }
        }
    }

    fun mkdir(path: String) {
        transact("mkdir", Usb2Snes.command(OP_MKDIR, name = path))
    }

    /** Deletes a file or an empty directory. */
    fun rm(path: String) {
        transact("rm", Usb2Snes.command(OP_RM, name = path))
    }

    /** Deletes a file, or a directory including all of its contents. */
    fun rmTree(path: String, isDirectory: Boolean) {
        if (isDirectory) {
            for (child in list(path)) rmTree(joinPath(path, child.name), child.isDirectory)
        }
        rm(path)
    }

    /**
     * Renames or moves [path] to [newPath] (full path, same SD card).
     * The firmware passes both arguments to FatFs f_rename.
     */
    fun mv(path: String, newPath: String) {
        transact("mv", Usb2Snes.command(OP_MV, name = path, name2 = newPath))
    }

    /** Creates every missing directory of [dir] (e.g. "/roms/snes"). */
    fun mkdirs(dir: String) {
        val parts = dir.split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            val parent = current.ifEmpty { "/" }
            current = "$current/$part"
            val exists = list(parent).any { it.isDirectory && it.name.equals(part, ignoreCase = true) }
            if (!exists) mkdir(current)
        }
    }

    /**
     * Uploads [size] bytes from [input] to [path] on the SD card.
     * [progress] receives (bytesSent, totalBytes).
     */
    fun putFile(path: String, size: Long, input: InputStream, progress: (Long, Long) -> Unit = { _, _ -> }) {
        require(size in 0..0xFFFF_FFFFL) { "Invalid file size $size" }
        transact("put", Usb2Snes.command(OP_PUT, name = path, size = size))

        if (size == 0L) {
            // Firmware expects one (empty) block even for zero-length files.
            link.write(ByteArray(PACKET_SIZE))
            return
        }

        // Stream data in chunks that are multiples of 512; final block is zero-padded.
        val chunk = ByteArray(CHUNK_SIZE)
        var sent = 0L
        progress(0, size)
        while (sent < size) {
            val want = minOf(CHUNK_SIZE.toLong(), size - sent).toInt()
            readFully(input, chunk, want)
            val padded = (want + PACKET_SIZE - 1) / PACKET_SIZE * PACKET_SIZE
            if (padded > want) chunk.fill(0, want, padded)
            link.write(chunk, padded)
            sent += want
            progress(sent, size)
        }
    }

    fun boot(path: String) {
        transact("boot", Usb2Snes.command(OP_BOOT, name = path))
    }

    /** Sends a command packet and validates the 512-byte response header. */
    private fun transact(op: String, packet: ByteArray): ByteArray {
        link.write(packet)
        val resp = ByteArray(PACKET_SIZE)
        link.readExact(resp)
        if (resp[0] != 'U'.code.toByte() || resp[1] != 'S'.code.toByte() || resp[2] != 'B'.code.toByte() || resp[3] != 'A'.code.toByte()) {
            throw IOException("$op: response packet does not contain USBA header")
        }
        if (resp[4].toInt() != OP_RESPONSE) throw IOException("$op: wrong opcode in response: ${resp[4]}")
        val ec = resp[5].toInt() and 0xFF
        if (ec != 0) throw FxPakDeviceException(op, ec)
        return resp
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int) {
        var p = 0
        while (p < length) {
            val n = input.read(buf, p, length - p)
            if (n < 0) throw EOFException("Source ended after ${p} of $length bytes in chunk")
            p += n
        }
    }

    companion object {
        private const val CHUNK_SIZE = 16 * 1024

        fun joinPath(dir: String, name: String): String = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

        fun parentOf(path: String): String {
            val idx = path.trimEnd('/').lastIndexOf('/')
            return if (idx <= 0) "/" else path.substring(0, idx)
        }

        fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')
    }
}
