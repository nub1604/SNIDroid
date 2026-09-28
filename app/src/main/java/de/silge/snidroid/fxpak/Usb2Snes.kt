package de.silge.snidroid.fxpak

/**
 * usb2snes wire protocol constants as used by the FXPak Pro firmware
 * (mirrors sni/devices/snes/drivers/fxpakpro/usb2snes.go).
 */
object Usb2Snes {
    const val PACKET_SIZE = 512

    // Opcodes
    const val OP_GET = 0
    const val OP_PUT = 1
    const val OP_VGET = 2
    const val OP_VPUT = 3
    const val OP_LS = 4
    const val OP_MKDIR = 5
    const val OP_RM = 6
    const val OP_MV = 7
    const val OP_RESET = 8
    const val OP_BOOT = 9
    const val OP_POWER_CYCLE = 10
    const val OP_INFO = 11
    const val OP_MENU_RESET = 12
    const val OP_STREAM = 13
    const val OP_TIME = 14
    const val OP_RESPONSE = 15

    // Address spaces
    const val SPACE_FILE = 0
    const val SPACE_SNES = 1
    const val SPACE_MSU = 2
    const val SPACE_CMD = 3
    const val SPACE_CONFIG = 4

    // Flags
    const val FLAG_NONE = 0
    const val FLAG_SKIPRESET = 1
    const val FLAG_ONLYRESET = 2
    const val FLAG_CLRX = 4
    const val FLAG_SETX = 8
    const val FLAG_STREAM_BURST = 16
    const val FLAG_NORESP = 64
    const val FLAG_DATA64B = 128

    const val SIZE_OFFSET = 252
    const val NAME_OFFSET = 256
    const val MAX_NAME_BYTES = PACKET_SIZE - NAME_OFFSET - 1
    const val NAME2_OFFSET = 8
    const val MAX_NAME2_BYTES = SIZE_OFFSET - NAME2_OFFSET - 1

    /** Builds a 512-byte command packet. */
    fun command(
        opcode: Int,
        space: Int = SPACE_FILE,
        flags: Int = FLAG_NONE,
        name: String? = null,
        size: Long? = null,
        name2: String? = null,
    ): ByteArray {
        val p = ByteArray(PACKET_SIZE)
        p[0] = 'U'.code.toByte()
        p[1] = 'S'.code.toByte()
        p[2] = 'B'.code.toByte()
        p[3] = 'A'.code.toByte()
        p[4] = opcode.toByte()
        p[5] = space.toByte()
        p[6] = flags.toByte()
        if (name != null) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_NAME_BYTES) { "Path too long (${bytes.size} bytes, max $MAX_NAME_BYTES)" }
            System.arraycopy(bytes, 0, p, NAME_OFFSET, bytes.size)
        }
        if (name2 != null) {
            // Second path argument (MV target) lives at offset 8.
            val bytes = name2.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_NAME2_BYTES) { "Target path too long (${bytes.size} bytes, max $MAX_NAME2_BYTES)" }
            System.arraycopy(bytes, 0, p, NAME2_OFFSET, bytes.size)
        }
        if (size != null) putUInt32BE(p, SIZE_OFFSET, size)
        return p
    }

    fun putUInt32BE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value shr 24 and 0xFF).toByte()
        buf[offset + 1] = (value shr 16 and 0xFF).toByte()
        buf[offset + 2] = (value shr 8 and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun getUInt32BE(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)

    /** Reads a zero-terminated string from [buf] in the range [from, to). */
    fun cString(buf: ByteArray, from: Int, to: Int): String {
        var end = from
        while (end < to && buf[end] != 0.toByte()) end++
        return String(buf, from, end - from, Charsets.UTF_8)
    }
}
