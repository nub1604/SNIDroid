package de.silge.snidroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.silge.snidroid.R
import de.silge.snidroid.fxpak.DirEntry
import java.util.Locale

/** Dialogs shown on top of the browser. */
private sealed interface DialogState {
    data object NewFolder : DialogState
    data class Rename(val entry: DirEntry) : DialogState
    data class ConfirmDelete(val names: Set<String>) : DialogState
    data object Log : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // SAF picker: shows local storage and cloud providers such as Google Drive.
    val pickRoms = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.prepareUpload(uris)
    }

    BackHandler(enabled = state.selectionMode || state.currentPath != "/") {
        if (state.selectionMode) vm.clearSelection() else vm.navigateUp()
    }

    Scaffold(
        topBar = {
            if (state.selectionMode) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, null) } },
                    title = { Text(pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size)) },
                    actions = {
                        ActionIcon(Icons.Filled.SelectAll, R.string.action_select_all) { vm.selectAll() }
                        ActionIcon(Icons.Filled.ContentCut, R.string.action_move) { vm.cut(state.selection) }
                        ActionIcon(Icons.Filled.Delete, R.string.action_delete) { dialog = DialogState.ConfirmDelete(state.selection) }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.app_name))
                            Text(
                                connectionText(state.connection, state.deviceDescription),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (state.connected) {
                            ActionIcon(Icons.Filled.CreateNewFolder, R.string.action_new_folder, enabled = !state.busy) {
                                dialog = DialogState.NewFolder
                            }
                            ActionIcon(Icons.Filled.Refresh, R.string.action_refresh, enabled = !state.busy) { vm.refresh() }
                        }
                        ActionIcon(Icons.Filled.History, R.string.section_log) { dialog = DialogState.Log }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.connected && !state.selectionMode && !state.busy) {
                ExtendedFloatingActionButton(
                    onClick = { pickRoms.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Upload, null) },
                    text = { Text(stringResource(R.string.action_upload_here)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.connected) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                DisconnectedPanel(state.connection) { vm.refreshDevices(userInitiated = true) }
            }
        } else Column(Modifier.fillMaxSize().padding(padding)) {

            Breadcrumbs(state.currentPath, onNavigate = vm::navigateTo)

            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()

            state.clipboard?.let { clip ->
                MoveBanner(
                    count = clip.entries.size,
                    onPaste = vm::pasteHere,
                    onCancel = vm::cancelMove,
                    enabled = !state.busy,
                )
            }

            state.progress?.let { UploadProgressCard(it) }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp),
            ) {
                if (state.entries.isEmpty() && !state.loading) {
                    item {
                        Text(
                            stringResource(R.string.folder_empty),
                            modifier = Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.entries, key = { it.name }) { entry ->
                    EntryRow(
                        entry = entry,
                        selectionMode = state.selectionMode,
                        selected = entry.name in state.selection,
                        enabled = !state.busy,
                        onOpen = { vm.openDirectory(entry.name) },
                        onToggleSelect = { vm.toggleSelection(entry.name) },
                        onBoot = { vm.boot(entry) },
                        onRename = { dialog = DialogState.Rename(entry) },
                        onMove = { vm.cut(listOf(entry.name)) },
                        onDelete = { dialog = DialogState.ConfirmDelete(setOf(entry.name)) },
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- dialogs

    when (val d = dialog) {
        DialogState.NewFolder -> TextInputDialog(
            title = stringResource(R.string.action_new_folder),
            initial = "",
            onConfirm = { vm.createFolder(it); dialog = null },
            onDismiss = { dialog = null },
        )
        is DialogState.Rename -> TextInputDialog(
            title = stringResource(R.string.action_rename),
            initial = d.entry.name,
            onConfirm = { vm.rename(d.entry, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is DialogState.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = {
                val hasDirs = state.entries.any { it.isDirectory && it.name in d.names }
                Text(
                    pluralStringResource(R.plurals.confirm_delete, d.names.size, d.names.size, d.names.sorted().joinToString("\n")) +
                        if (hasDirs) "\n\n" + stringResource(R.string.confirm_delete_folders) else ""
                )
            },
            confirmButton = { TextButton(onClick = { vm.delete(d.names); dialog = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
        DialogState.Log -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.section_log)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(state.log) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(android.R.string.ok)) } },
        )
        null -> Unit
    }

    if (state.pendingUploads.isNotEmpty()) {
        UploadDialog(
            files = state.pendingUploads,
            targetDir = state.currentPath,
            boot = state.bootAfterUpload,
            onBootChange = vm::setBootAfterUpload,
            onConfirm = vm::confirmUpload,
            onDismiss = vm::cancelUpload,
        )
    }
}

// -------------------------------------------------------------------- components

@Composable
private fun ActionIcon(icon: ImageVector, labelRes: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = stringResource(labelRes)) }
}

@Composable
private fun DisconnectedPanel(state: ConnectionState, onSearch: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.SdCard, null, Modifier.size(64.dp))
        Text(connectionText(state, null), style = MaterialTheme.typography.bodyLarge)
        if (state != ConnectionState.CONNECTING) {
            OutlinedButton(onClick = onSearch) { Text(stringResource(R.string.action_search_device)) }
        }
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = buildList {
        add("/")
        var acc = ""
        path.split('/').filter { it.isNotEmpty() }.forEach { acc += "/$it"; add(acc) }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { i, p ->
            if (i > 0) Text("›", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { onNavigate(p) }) {
                Text(
                    MainViewModel.displayName(p),
                    fontWeight = if (i == segments.lastIndex) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MoveBanner(count: Int, onPaste: () -> Unit, onCancel: () -> Unit, enabled: Boolean) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.move_pending, count, count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = onPaste, enabled = enabled) {
                Icon(Icons.Filled.ContentPaste, null)
                Text(" " + stringResource(R.string.action_paste_here))
            }
        }
    }
}

@Composable
private fun UploadProgressCard(p: UploadProgress) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (p.fileCount > 1) "${p.fileIndex + 1}/${p.fileCount}  ${p.fileName}" else p.fileName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            Text(
                String.format(
                    Locale.ROOT, "%d %%  ·  %s / %s  ·  %s/s",
                    (p.fraction * 100).toInt(), formatSize(p.sent), formatSize(p.total), formatSize(p.bytesPerSecond),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: DirEntry,
    selectionMode: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onBoot: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val isRom = !entry.isDirectory && MainViewModel.isRomFile(entry.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    when {
                        selectionMode -> onToggleSelect()
                        entry.isDirectory -> onOpen()
                        else -> menuOpen = true
                    }
                },
                onLongClick = onToggleSelect,
            )
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        } else {
            val icon = when {
                entry.isDirectory -> Icons.Filled.Folder
                isRom -> Icons.Filled.VideogameAsset
                else -> Icons.Filled.InsertDriveFile
            }
            Icon(icon, null, Modifier.padding(end = 16.dp))
        }
        Text(entry.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!selectionMode) {
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled) { Icon(Icons.Filled.MoreVert, null) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (isRom) MenuItem(Icons.Filled.PlayArrow, R.string.action_boot) { menuOpen = false; onBoot() }
                    MenuItem(Icons.Filled.Edit, R.string.action_rename) { menuOpen = false; onRename() }
                    MenuItem(Icons.Filled.ContentCut, R.string.action_move) { menuOpen = false; onMove() }
                    MenuItem(Icons.Filled.Delete, R.string.action_delete) { menuOpen = false; onDelete() }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, labelRes: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(icon, null) },
        onClick = onClick,
    )
}

@Composable
private fun TextInputDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun UploadDialog(
    files: List<RomSelection>,
    targetDir: String,
    boot: Boolean,
    onBootChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_upload_here)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.upload_target, targetDir), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(files) { f ->
                        Text(
                            f.displayName + (f.size?.let { "  (${formatSize(it)})" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (files.size == 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = boot, onCheckedChange = onBootChange)
                        Text(stringResource(R.string.label_boot_after_upload))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_upload)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun connectionText(state: ConnectionState, description: String?): String = when (state) {
    ConnectionState.NO_DEVICE -> stringResource(R.string.state_no_device)
    ConnectionState.NO_PERMISSION -> stringResource(R.string.state_no_permission)
    ConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
    ConnectionState.CONNECTED -> stringResource(R.string.state_connected, description ?: "FXPak Pro")
    ConnectionState.ERROR -> stringResource(R.string.state_error)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.2f MB", bytes / 1048576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
