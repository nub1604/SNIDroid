package de.silge.snidroid.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import de.silge.snidroid.fxpak.SerialLink
import java.io.Closeable
import java.io.IOException

/**
 * Minimal USB CDC-ACM serial port on top of Android's USB host API.
 * The FXPak Pro enumerates as a CDC-ACM device; baud rate is irrelevant,
 * but DTR must be asserted (same as SNI does).
 */
class CdcAcmPort private constructor(
    private val connection: UsbDeviceConnection,
    private val interfaces: List<UsbInterface>,
    private val controlInterfaceId: Int,
    private val endpointIn: UsbEndpoint,
    private val endpointOut: UsbEndpoint,
) : SerialLink, Closeable {

    private val rxBuffer = ByteArray(16 * 1024)
    private var rxStart = 0
    private var rxEnd = 0

    @Volatile
    var isClosed = false
        private set

    override fun write(data: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val n = connection.bulkTransfer(endpointOut, data, offset, length - offset, WRITE_TIMEOUT_MS)
            if (n <= 0) throw IOException("USB write failed (result=$n, offset=$offset/$length)")
            offset += n
        }
    }

    /** Reads exactly [length] bytes into [dst] or throws on timeout. */
    override fun readExact(dst: ByteArray, length: Int) {
        var p = 0
        val deadline = SystemClock.elapsedRealtime() + READ_TIMEOUT_MS
        while (p < length) {
            if (rxStart < rxEnd) {
                val n = minOf(rxEnd - rxStart, length - p)
                System.arraycopy(rxBuffer, rxStart, dst, p, n)
                rxStart += n
                p += n
                continue
            }
            val n = connection.bulkTransfer(endpointIn, rxBuffer, rxBuffer.size, POLL_TIMEOUT_MS)
            if (n > 0) {
                rxStart = 0
                rxEnd = n
            } else if (SystemClock.elapsedRealtime() > deadline) {
                throw IOException("USB read timed out ($p/$length bytes)")
            }
        }
    }

    /** Discards any pending input (used to resync after an error). */
    fun drain() {
        rxStart = 0
        rxEnd = 0
        val tmp = ByteArray(rxBuffer.size)
        while (connection.bulkTransfer(endpointIn, tmp, tmp.size, 50) > 0) { /* discard */ }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        runCatching { setControlLineState(dtr = false) }
        interfaces.forEach { runCatching { connection.releaseInterface(it) } }
        connection.close()
    }

    private fun setControlLineState(dtr: Boolean, rts: Boolean = false) {
        val value = (if (dtr) 0x01 else 0) or (if (rts) 0x02 else 0)
        connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, SET_CONTROL_LINE_STATE, value, controlInterfaceId, null, 0, CONTROL_TIMEOUT_MS)
    }

    private fun setLineCoding(baud: Int) {
        val coding = byteArrayOf(
            (baud and 0xFF).toByte(),
            (baud shr 8 and 0xFF).toByte(),
            (baud shr 16 and 0xFF).toByte(),
            (baud shr 24 and 0xFF).toByte(),
            0, // 1 stop bit
            0, // no parity
            8, // data bits
        )
        connection.controlTransfer(REQTYPE_HOST_TO_INTERFACE, SET_LINE_CODING, 0, controlInterfaceId, coding, coding.size, CONTROL_TIMEOUT_MS)
    }

    companion object {
        const val FXPAK_VENDOR_ID = 0x1209
        const val FXPAK_PRODUCT_ID = 0x5A22

        private const val REQTYPE_HOST_TO_INTERFACE = 0x21
        private const val SET_LINE_CODING = 0x20
        private const val SET_CONTROL_LINE_STATE = 0x22
        private const val CONTROL_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000L
        private const val POLL_TIMEOUT_MS = 200

        fun isFxPak(device: UsbDevice): Boolean =
            device.vendorId == FXPAK_VENDOR_ID && device.productId == FXPAK_PRODUCT_ID

        fun open(manager: UsbManager, device: UsbDevice): CdcAcmPort {
            var control: UsbInterface? = null
            var data: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                when (intf.interfaceClass) {
                    UsbConstants.USB_CLASS_COMM -> if (control == null) control = intf
                    UsbConstants.USB_CLASS_CDC_DATA -> if (data == null) data = intf
                }
            }
            // Fallback: any interface that exposes bulk IN and OUT endpoints.
            if (data == null) {
                data = (0 until device.interfaceCount).map { device.getInterface(it) }
                    .firstOrNull { findBulk(it, UsbConstants.USB_DIR_IN) != null && findBulk(it, UsbConstants.USB_DIR_OUT) != null }
            }
            val dataIntf = data ?: throw IOException("No CDC data interface found")
            val epIn = findBulk(dataIntf, UsbConstants.USB_DIR_IN) ?: throw IOException("No bulk IN endpoint")
            val epOut = findBulk(dataIntf, UsbConstants.USB_DIR_OUT) ?: throw IOException("No bulk OUT endpoint")

            val connection = manager.openDevice(device) ?: throw IOException("Cannot open USB device (permission?)")
            val claimed = listOfNotNull(control, dataIntf).distinct()
            for (intf in claimed) {
                if (!connection.claimInterface(intf, true)) {
                    connection.close()
                    throw IOException("Cannot claim USB interface ${intf.id}")
                }
            }
            val port = CdcAcmPort(connection, claimed, (control ?: dataIntf).id, epIn, epOut)
            runCatching { port.setLineCoding(921_600) }
            port.setControlLineState(dtr = true)
            port.drain()
            return port
        }

        private fun findBulk(intf: UsbInterface, direction: Int): UsbEndpoint? =
            (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == direction }
    }
}
