package de.silge.snidroid.ui

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.silge.snidroid.R
import de.silge.snidroid.fxpak.DirEntry
import de.silge.snidroid.fxpak.FxPakClient
import de.silge.snidroid.fxpak.FxPakClient.Companion.joinPath
import de.silge.snidroid.fxpak.FxPakClient.Companion.nameOf
import de.silge.snidroid.fxpak.FxPakClient.Companion.parentOf
import de.silge.snidroid.fxpak.FxPakDeviceException
import de.silge.snidroid.fxpak.Usb2Snes
import de.silge.snidroid.usb.CdcAcmPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnectionState { NO_DEVICE, NO_PERMISSION, CONNECTING, CONNECTED, ERROR }

data class RomSelection(val uri: Uri, val displayName: String, val size: Long?)

data class UploadProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val sent: Long,
    val total: Long,
    val bytesPerSecond: Long,
) {
    val fraction: Float get() = if (total > 0) sent.toFloat() / total else 0f
}

/** Entries cut for moving; kept while the user navigates to the destination. */
data class MoveClipboard(val sourceDir: String, val entries: List<DirEntry>)

data class UiState(
    val connection: ConnectionState = ConnectionState.NO_DEVICE,
    val deviceDescription: String? = null,
    val currentPath: String = "/",
    val entries: List<DirEntry> = emptyList(),
    val loading: Boolean = false,
    val selection: Set<String> = emptySet(),
    val clipboard: MoveClipboard? = null,
    val pendingUploads: List<RomSelection> = emptyList(),
    val bootAfterUpload: Boolean = true,
    val busy: Boolean = false,
    val progress: UploadProgress? = null,
    val log: List<String> = emptyList(),
) {
    val connected: Boolean get() = connection == ConnectionState.CONNECTED
    val selectionMode: Boolean get() = selection.isNotEmpty()
}

private const val ACTION_USB_PERMISSION = "de.silge.snidroid.USB_PERMISSION"
private const val PREFS = "settings"
private const val KEY_BOOT = "boot_after_upload"
private const val KEY_LAST_PATH = "last_path"
private const val MAX_LOG_LINES = 200
private val ROM_EXTENSIONS = setOf("sfc", "smc", "swc", "fig", "bs", "st")

/** Top-level folders holding FXPak firmware/config; they must not be moved or deleted. */
private val PROTECTED_PATHS = setOf("/sd2snes")

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val usbManager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ioLock = Mutex()

    private var port: CdcAcmPort? = null
    private var client: FxPakClient? = null
    private var device: UsbDevice? = null
    private var lastPromptedDevice: String? = null

    private val _state = MutableStateFlow(
        UiState(
            bootAfterUpload = prefs.getBoolean(KEY_BOOT, true),
            currentPath = prefs.getString(KEY_LAST_PATH, "/") ?: "/",
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One-shot user-facing messages (snackbar). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val dev = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && dev != null) {
                        connect(dev)
                    } else {
                        _state.update { it.copy(connection = ConnectionState.NO_PERMISSION) }
                        log(str(R.string.log_permission_denied))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (dev != null && dev.deviceName == device?.deviceName) {
                        log(str(R.string.log_detached))
                        disconnect()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshDevices()
    }

    // ---------------------------------------------------------------- connection

    /**
     * Looks for an attached FXPak Pro and connects. Permission is requested automatically
     * once per attached device, or again when [userInitiated] is true.
     */
    fun refreshDevices(userInitiated: Boolean = false) {
        if (client != null || _state.value.connection == ConnectionState.CONNECTING) return
        val dev = usbManager.deviceList.values.firstOrNull { CdcAcmPort.isFxPak(it) }
        if (dev == null) {
            _state.update { it.copy(connection = ConnectionState.NO_DEVICE, deviceDescription = null) }
            return
        }
        if (usbManager.hasPermission(dev)) {
            connect(dev)
        } else {
            _state.update { it.copy(connection = ConnectionState.NO_PERMISSION) }
            if (!userInitiated && lastPromptedDevice == dev.deviceName) return
            lastPromptedDevice = dev.deviceName
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(getApplication<Application>().packageName)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            usbManager.requestPermission(dev, PendingIntent.getBroadcast(getApplication(), 0, intent, flags))
        }
    }

    private fun connect(dev: UsbDevice) {
        _state.update { it.copy(connection = ConnectionState.CONNECTING) }
        viewModelScope.launch {
            ioLock.withLock {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val p = CdcAcmPort.open(usbManager, dev)
                        val c = FxPakClient(p)
                        val info = try {
                            c.info()
                        } catch (e: Exception) {
                            p.close()
                            throw e
                        }
                        Triple(p, c, info)
                    }
                }.onSuccess { (p, c, info) ->
                    port = p
                    client = c
                    device = dev
                    val desc = listOf(info.deviceName, info.firmwareVersion).filter { it.isNotBlank() }.joinToString(" · ")
                        .ifEmpty { "FXPak Pro" }
                    _state.update { it.copy(connection = ConnectionState.CONNECTED, deviceDescription = desc) }
                    log(str(R.string.log_connected, desc))
                }.onFailure { e ->
                    _state.update { it.copy(connection = ConnectionState.ERROR) }
                    log(str(R.string.log_error, e.message ?: e.toString()))
                }
            }
            if (client != null) loadDirectory(_state.value.currentPath, fallbackToRoot = true)
        }
    }

    private fun disconnect() {
        runCatching { port?.close() }
        port = null
        client = null
        device = null
        _state.update {
            it.copy(connection = ConnectionState.NO_DEVICE, deviceDescription = null, entries = emptyList(), selection = emptySet())
        }
    }

    // ---------------------------------------------------------------- browsing

    fun openDirectory(name: String) = loadDirectory(joinPath(_state.value.currentPath, name))

    fun navigateTo(path: String) = loadDirectory(path)

    /** Returns false when already at the root. */
    fun navigateUp(): Boolean {
        val path = _state.value.currentPath
        if (path == "/") return false
        loadDirectory(parentOf(path))
        return true
    }

    fun refresh() = loadDirectory(_state.value.currentPath)

    private fun loadDirectory(path: String, fallbackToRoot: Boolean = false) {
        _state.update { it.copy(loading = true) }
        runOp(showErrors = !fallbackToRoot, onDeviceError = {
            if (fallbackToRoot && path != "/") loadDirectory("/")
        }) { c ->
            val entries = c.list(path).sortedWith(compareBy<DirEntry>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            _state.update { it.copy(currentPath = path, entries = entries, selection = emptySet()) }
            prefs.edit().putString(KEY_LAST_PATH, path).apply()
        }
    }

    // ---------------------------------------------------------------- selection

    fun toggleSelection(name: String) = _state.update {
        it.copy(selection = if (name in it.selection) it.selection - name else it.selection + name)
    }

    fun selectAll() = _state.update { s -> s.copy(selection = s.entries.map { it.name }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    // ---------------------------------------------------------------- file operations

    fun createFolder(name: String) {
        val path = joinPath(_state.value.currentPath, sanitizeFileName(name))
        runOp { c ->
            c.mkdir(path)
            log(str(R.string.log_folder_created, path))
        }
        refresh()
    }

    fun rename(entry: DirEntry, newName: String) {
        val clean = sanitizeFileName(newName)
        if (clean == entry.name) return
        val dir = _state.value.currentPath
        val from = joinPath(dir, entry.name)
        if (isProtected(from)) return notify(str(R.string.msg_protected, from))
        runOp { c ->
            c.mv(from, joinPath(dir, clean))
            log(str(R.string.log_renamed, entry.name, clean))
        }
        refresh()
    }

    /** Deletes [names] (entries of the current directory); directories recursively. */
    fun delete(names: Collection<String>) {
        val dir = _state.value.currentPath
        val targets = _state.value.entries.filter { it.name in names }
        targets.firstOrNull { isProtected(joinPath(dir, it.name)) }?.let {
            return notify(str(R.string.msg_protected, joinPath(dir, it.name)))
        }
        runOp { c ->
            for (e in targets) {
                val path = joinPath(dir, e.name)
                c.rmTree(path, e.isDirectory)
                log(str(R.string.log_deleted, path))
            }
        }
        refresh()
    }

    /** Puts entries of the current directory on the move clipboard. */
    fun cut(names: Collection<String>) {
        val dir = _state.value.currentPath
        val entries = _state.value.entries.filter { it.name in names }
        entries.firstOrNull { isProtected(joinPath(dir, it.name)) }?.let {
            return notify(str(R.string.msg_protected, joinPath(dir, it.name)))
        }
        if (entries.isEmpty()) return
        _state.update { it.copy(clipboard = MoveClipboard(dir, entries), selection = emptySet()) }
    }

    fun cancelMove() = _state.update { it.copy(clipboard = null) }

    /** Moves the clipboard entries into the current directory. */
    fun pasteHere() {
        val clip = _state.value.clipboard ?: return
        val dest = _state.value.currentPath
        if (clip.sourceDir == dest) return notify(str(R.string.msg_same_folder))
        for (e in clip.entries) {
            val src = joinPath(clip.sourceDir, e.name)
            if (e.isDirectory && (dest == src || dest.startsWith("$src/"))) {
                return notify(str(R.string.msg_move_into_itself, e.name))
            }
        }
        runOp { c ->
            for (e in clip.entries) {
                val src = joinPath(clip.sourceDir, e.name)
                val dst = joinPath(dest, e.name)
                c.mv(src, dst)
                log(str(R.string.log_moved, src, dst))
            }
            _state.update { it.copy(clipboard = null) }
        }
        refresh()
    }

    fun boot(entry: DirEntry) {
        val path = joinPath(_state.value.currentPath, entry.name)
        runOp { c ->
            c.boot(path)
            log(str(R.string.log_booted, path))
        }
    }

    // ---------------------------------------------------------------- upload

    fun prepareUpload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val roms = uris.map { resolveRom(it) }
        roms.filterNot { isRomFile(it.displayName) }.forEach { log(str(R.string.log_unknown_extension, it.displayName)) }
        _state.update { it.copy(pendingUploads = roms) }
    }

    fun cancelUpload() = _state.update { it.copy(pendingUploads = emptyList()) }

    fun setBootAfterUpload(value: Boolean) {
        _state.update { it.copy(bootAfterUpload = value) }
        prefs.edit().putBoolean(KEY_BOOT, value).apply()
    }

    /** Uploads the pending ROMs into the current directory. Boot only applies to a single file. */
    fun confirmUpload() {
        val s = _state.value
        val roms = s.pendingUploads
        if (roms.isEmpty()) return
        val dir = s.currentPath
        val boot = s.bootAfterUpload && roms.size == 1
        _state.update { it.copy(pendingUploads = emptyList()) }

        runOp(trackBusy = true) { c ->
            val app = getApplication<Application>()
            roms.forEachIndexed { index, rom ->
                val fileName = sanitizeFileName(rom.displayName)
                val targetPath = joinPath(dir, fileName)
                if (targetPath.toByteArray(Charsets.UTF_8).size > Usb2Snes.MAX_NAME_BYTES) {
                    log(str(R.string.log_path_too_long, targetPath))
                    return@forEachIndexed
                }
                // Providers like Google Drive may not report a size; buffer to cache in that case.
                var tempFile: File? = null
                val knownSize: Long? = rom.size?.takeIf { it > 0 }
                val (input, size) = if (knownSize != null) {
                    (app.contentResolver.openInputStream(rom.uri) ?: error("Cannot open ${rom.displayName}")) to knownSize
                } else {
                    val f = File.createTempFile("rom", ".bin", app.cacheDir).also { tempFile = it }
                    app.contentResolver.openInputStream(rom.uri)?.use { src -> f.outputStream().use { src.copyTo(it) } }
                        ?: error("Cannot open ${rom.displayName}")
                    f.inputStream() to f.length()
                }
                try {
                    log(str(R.string.log_uploading, targetPath, size / 1024))
                    uploadStream(c, targetPath, input, size, fileName, index, roms.size)
                } finally {
                    input.close()
                    tempFile?.delete()
                }
                log(str(R.string.log_upload_done, fileName))
            }
            if (boot) {
                val path = joinPath(dir, sanitizeFileName(roms.first().displayName))
                c.boot(path)
                log(str(R.string.log_booted, path))
            }
        }
        refresh()
    }

    private fun uploadStream(c: FxPakClient, path: String, input: InputStream, size: Long, name: String, index: Int, count: Int) {
        val start = System.nanoTime()
        var lastUpdate = 0L
        c.putFile(path, size, input) { sent, total ->
            val now = System.nanoTime()
            if (now - lastUpdate > 100_000_000L || sent == total) {
                lastUpdate = now
                val elapsed = (now - start).coerceAtLeast(1)
                val bps = sent * 1_000_000_000L / elapsed
                _state.update { it.copy(progress = UploadProgress(name, index, count, sent, total, bps)) }
            }
        }
    }

    private fun resolveRom(uri: Uri): RomSelection {
        val resolver = getApplication<Application>().contentResolver
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        return RomSelection(uri, name ?: uri.lastPathSegment ?: "rom.sfc", size)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Runs [block] on the IO dispatcher with exclusive access to the device.
     * Device-reported errors keep the link; transport errors trigger a reconnect.
     * Operations queue on the fair [ioLock], so a refresh launched afterwards runs after [block].
     */
    private fun runOp(
        trackBusy: Boolean = false,
        showErrors: Boolean = true,
        onDeviceError: () -> Unit = {},
        block: suspend (FxPakClient) -> Unit,
    ) {
        if (client == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        if (trackBusy) _state.update { it.copy(busy = true, progress = null) }
        viewModelScope.launch {
            ioLock.withLock {
                val c = client
                try {
                    if (c != null) withContext(Dispatchers.IO) { block(c) }
                } catch (e: FxPakDeviceException) {
                    val msg = str(R.string.log_error, e.message ?: e.toString())
                    log(msg)
                    if (showErrors) notify(msg)
                    onDeviceError()
                } catch (e: Exception) {
                    val msg = str(R.string.log_error, e.message ?: e.toString())
                    log(msg)
                    notify(msg)
                    val dev = device
                    disconnect()
                    if (dev != null && usbManager.deviceList.values.any { it.deviceName == dev.deviceName }) {
                        log(str(R.string.log_reconnecting))
                        refreshDevices()
                    }
                } finally {
                    _state.update {
                        it.copy(
                            loading = false,
                            busy = if (trackBusy) false else it.busy,
                            progress = if (trackBusy) null else it.progress,
                        )
                    }
                }
            }
        }
    }

    private fun isProtected(path: String) = path.lowercase(Locale.ROOT) in PROTECTED_PATHS

    private fun notify(message: String) {
        _messages.tryEmit(message)
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _state.update { it.copy(log = (listOf("$time  $message") + it.log).take(MAX_LOG_LINES)) }
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(usbReceiver) }
        runCatching { port?.close() }
        super.onCleared()
    }

    companion object {
        /** Replaces characters FAT does not allow in file names. */
        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_").trim().ifEmpty { "unnamed" }

        fun isRomFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in ROM_EXTENSIONS

        /** Label for a path in the breadcrumb bar. */
        fun displayName(path: String): String = if (path == "/") "SD" else nameOf(path)
    }
}
