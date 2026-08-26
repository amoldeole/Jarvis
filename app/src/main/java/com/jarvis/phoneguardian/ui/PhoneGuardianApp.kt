@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jarvis.phoneguardian.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.phoneguardian.assistant.AssistantIntent
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.MediaTypes
import com.jarvis.phoneguardian.core.model.OrganizationSuggestion
import com.jarvis.phoneguardian.core.model.TrashEntity
import com.jarvis.phoneguardian.core.server.LocalServerRegistry
import com.jarvis.phoneguardian.core.server.QrCode
import com.jarvis.phoneguardian.core.storage.FolderSafety
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private enum class Tab(val label: String) { HOME("Home"), FILES("Files"), CLEAN("Clean"), BACKUP("Backup"), ASSISTANT("Assistant") }
private enum class PickPurpose { SCAN, ORGANIZE, BACKUP, CLEAN, RESTORE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneGuardianApp() {
    val context = LocalContext.current
    val viewModel: GuardianViewModel = viewModel()
    var tab by remember { mutableStateOf(Tab.HOME) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showOrganization by remember { mutableStateOf(false) }
    var pickPurpose by remember { mutableStateOf<PickPurpose?>(null) }
    var restoreItem by remember { mutableStateOf<TrashEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val organization by viewModel.organization.collectAsStateCompat()

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val purpose = pickPurpose
        pickPurpose = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        when (purpose) {
            PickPurpose.SCAN -> {
                context.getSharedPreferences("guardian_trees", Context.MODE_PRIVATE).edit().putStringSet("trees", setOf(uri.toString())).apply()
                viewModel.startScan(listOf(uri))
            }
            PickPurpose.ORGANIZE -> {
                viewModel.approveOrganization(uri)
                showOrganization = false
            }
            PickPurpose.BACKUP -> viewModel.backupTo(context, uri)
            PickPurpose.CLEAN -> viewModel.findEmptyFolders(context, uri)
            PickPurpose.RESTORE -> restoreItem?.let { viewModel.restoreFromTrash(context, it, uri); restoreItem = null }
            null -> Unit
        }
    }
    val mediaPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) viewModel.startScan()
        else notice = "Photos and video access was declined. You can grant it later from Android Settings."
    }
    val requestScan: () -> Unit = {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        mediaPermission.launch(permissions)
    }

    PhoneGuardianTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text("Phone Guardian", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showPrivacy = true }) { Icon(Icons.Default.Security, "Privacy center") }
                        IconButton(onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }) { Icon(Icons.Default.MoreVert, "Settings") }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { item ->
                        NavigationBarItem(
                            selected = tab == item,
                            onClick = { tab = item },
                            icon = { Icon(tabIcon(item), item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                when (tab) {
                    Tab.HOME -> HomeScreen(viewModel, requestScan, { pickPurpose = PickPurpose.SCAN; treePicker.launch(null) }, { tab = it }, { showOrganization = true; viewModel.buildOrganizationPreview() }, { showPrivacy = true })
                    Tab.FILES -> FilesScreen(viewModel, { pickPurpose = PickPurpose.SCAN; treePicker.launch(null) })
                    Tab.CLEAN -> CleanScreen(viewModel, onSelectFolder = { pickPurpose = PickPurpose.CLEAN; treePicker.launch(null) }, onRestore = { item -> restoreItem = item; pickPurpose = PickPurpose.RESTORE; treePicker.launch(null) })
                    Tab.BACKUP -> BackupScreen(viewModel, { pickPurpose = PickPurpose.BACKUP; treePicker.launch(null) })
                    Tab.ASSISTANT -> AssistantScreen(viewModel)
                }
            }
        }
    }

    if (notice != null) {
        LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it) }; notice = null }
    }
    if (showPrivacy) PrivacyCenterDialog(viewModel) { showPrivacy = false }
    if (showOrganization && organization.isNotEmpty()) {
        OrganizationPreviewDialog(organization, viewModel.scan.collectAsStateCompat().value.isRunning, onApprove = { pickPurpose = PickPurpose.ORGANIZE; treePicker.launch(null) }, onDismiss = { showOrganization = false })
    }
}

private fun tabIcon(tab: Tab) = when (tab) {
    Tab.HOME -> Icons.Default.Storage
    Tab.FILES -> Icons.Default.FolderOpen
    Tab.CLEAN -> Icons.Default.CleaningServices
    Tab.BACKUP -> Icons.Default.Backup
    Tab.ASSISTANT -> Icons.Default.Assistant
}

@Composable
private fun HomeScreen(
    vm: GuardianViewModel,
    onScan: () -> Unit,
    onFolder: () -> Unit,
    onTab: (Tab) -> Unit,
    onOrganize: () -> Unit,
    onPrivacy: () -> Unit
) {
    val summary by vm.summary.collectAsStateCompat()
    val scan by vm.scan.collectAsStateCompat()
    val hasFiles = summary.fileCount > 0
    val prefs = LocalContext.current.getSharedPreferences("guardian_ui", Context.MODE_PRIVATE)
    var welcome by remember { mutableStateOf(!prefs.getBoolean("welcome_seen", false)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 18.dp)) {
        if (welcome) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Let's understand your phone storage.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Phone Guardian scans safely using Android's standard APIs. It never deletes, moves or uploads your files without your approval.")
                    Button(onClick = { prefs.edit().putBoolean("welcome_seen", true).apply(); welcome = false; onScan() }) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Scan my phone") }
                    TextButton(onClick = { prefs.edit().putBoolean("welcome_seen", true).apply(); welcome = false; onFolder() }) { Text("Choose a folder instead") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    StorageRing(summary.usedPercent)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Storage", style = MaterialTheme.typography.titleMedium)
                        Text(GuardianViewModel.formatBytes(summary.usedBytes), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("of ${GuardianViewModel.formatBytes(summary.totalBytes)} used · ${GuardianViewModel.formatBytes(summary.freeBytes)} free", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (scan.isRunning) item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Scanning safely… ${scan.scanned} files"); LinearProgressIndicator(Modifier.fillMaxWidth()); Text(scan.message, style = MaterialTheme.typography.bodySmall) } }
        }
        item {
            Text("Storage overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            CategoryRow("Photos", summary.photosBytes, Icons.Default.PhotoLibrary)
            CategoryRow("Videos", summary.videosBytes, Icons.Default.Videocam)
            CategoryRow("Documents", summary.documentsBytes, Icons.Default.Description)
            CategoryRow("Downloads", summary.downloadsBytes, Icons.Default.Folder)
            CategoryRow("Audio", summary.audioBytes, Icons.Default.MusicNote)
            CategoryRow("Archives", summary.archivesBytes, Icons.Default.Archive)
            CategoryRow("Installers", summary.installersBytes, Icons.Default.Bolt)
            CategoryRow("Other", summary.otherBytes, Icons.Default.InsertDriveFile)
        }
        item {
            Text("Safe actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Organize", Icons.Default.Folder, onOrganize, Modifier.weight(1f))
                ActionButton("Duplicates", Icons.Default.CleaningServices, { onTab(Tab.CLEAN) }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Backup", Icons.Default.Backup, { onTab(Tab.BACKUP) }, Modifier.weight(1f))
                ActionButton("Browse files", Icons.Default.FolderOpen, { onTab(Tab.FILES) }, Modifier.weight(1f))
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                ListItem(leadingContent = { Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Privacy first") }, supportingContent = { Text("Files scanned: ${if (hasFiles) "Yes" else "Not yet"} · Cloud AI: Disabled · Uploads: 0") }, trailingContent = { TextButton(onClick = onPrivacy) { Text("View") } })
            }
        }
    }
}

@Composable
private fun StorageRing(progress: Float) {
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(MaterialTheme.colorScheme.primary.copy(alpha = .15f), 0f, 360f, false, style = Stroke(10.dp.toPx()))
            drawArc(MaterialTheme.colorScheme.primary, -90f, progress * 360f, false, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round))
        }
        Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CategoryRow(label: String, bytes: Long, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(label, Modifier.weight(1f))
        Text(GuardianViewModel.formatBytes(bytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label) }
}

@Composable
private fun FilesScreen(vm: GuardianViewModel, onFolder: () -> Unit) {
    val files by vm.files.collectAsStateCompat()
    val results by vm.searchResults.collectAsStateCompat()
    var query by rememberSaveable { mutableStateOf("") }
    var mediaFilter by rememberSaveable { mutableStateOf("all") }
    var deleteFile by remember { mutableStateOf<FileEntity?>(null) }
    LaunchedEffect(query) { delay(250); vm.search(query) }
    val display = (if (query.isBlank()) files else results).filter { mediaFilter == "all" || it.mediaType == mediaFilter }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Search files and paths") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search))
            IconButton(onClick = onFolder) { Icon(Icons.Default.Folder, "Select a folder") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("all" to "All", MediaTypes.PHOTO to "Photos", MediaTypes.VIDEO to "Videos", MediaTypes.DOCUMENT to "Docs").forEach { (key, label) -> FilterChip(selected = mediaFilter == key, onClick = { mediaFilter = key }, label = { Text(label) }) }
        }
        Text("${display.size} indexed files", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            items(display, key = { it.uri }) { file -> FileRow(file, { deleteFile = file }) }
        }
    }
    deleteFile?.let { file -> ConfirmTrashDialog(file) { vm.moveToTrash(file); deleteFile = null } { deleteFile = null } }
}

@Composable
private fun FileRow(file: FileEntity, onTrash: () -> Unit) {
    val context = LocalContext.current
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(file.uri)).setDataAndType(Uri.parse(file.uri), file.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } },
        leadingContent = { Icon(fileIcon(file), null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(file.fileName, maxLines = 1) },
        supportingContent = { Text("${GuardianViewModel.formatBytes(file.size)} · ${file.displayPath}", maxLines = 2, fontSize = 12.sp) },
        trailingContent = { if (!file.isProtected && !FolderSafety.isLikelyUserCreated(file.displayPath)) IconButton(onClick = onTrash) { Icon(Icons.Default.DeleteOutline, "Move to Trash") } else Icon(Icons.Default.Lock, "Protected folder") }
    )
}

private fun fileIcon(file: FileEntity) = when (file.mediaType) {
    MediaTypes.PHOTO -> Icons.Default.Image
    MediaTypes.VIDEO -> Icons.Default.Videocam
    MediaTypes.AUDIO -> Icons.Default.MusicNote
    MediaTypes.DOCUMENT -> Icons.Default.Description
    MediaTypes.ARCHIVE -> Icons.Default.Archive
    MediaTypes.INSTALLER -> Icons.Default.Bolt
    else -> Icons.Default.InsertDriveFile
}

@Composable
private fun CleanScreen(vm: GuardianViewModel, onSelectFolder: () -> Unit, onRestore: (TrashEntity) -> Unit) {
    val groups by vm.duplicateGroups.collectAsStateCompat()
    val large by vm.largeFiles.collectAsStateCompat()
    val trash by vm.trash.collectAsStateCompat()
    val history by vm.operationsLog.collectAsStateCompat()
    val emptyFolders by vm.emptyFolders.collectAsStateCompat()
    val scan by vm.scan.collectAsStateCompat()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var showLarge by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showEmptyDialog by remember { mutableStateOf(false) }
    LaunchedEffect(emptyFolders) { if (emptyFolders.isNotEmpty()) showEmptyDialog = true }
    var confirmFiles by remember { mutableStateOf<List<FileEntity>?>(null) }
    var permanentDelete by remember { mutableStateOf<com.jarvis.phoneguardian.core.model.TrashEntity?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)) {
        item {
            Text("Clean carefully", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Every cleanup is review-first. Nothing is permanently deleted by Phone Guardian.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card { ListItem(leadingContent = { Icon(Icons.Default.CleaningServices, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Exact duplicates") }, supportingContent = { Text(if (scan.isRunning) scan.message else "${groups.size} groups · checksums stay on this phone") }, trailingContent = { Button(onClick = { vm.deepScanForDuplicates() }, enabled = !scan.isRunning) { Text("Scan") } }) }
        }
        item {
            Card { ListItem(leadingContent = { Icon(Icons.Default.BatteryAlert, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Large files") }, supportingContent = { Text("Find files larger than 500 MB") }, trailingContent = { OutlinedButton(onClick = { showLarge = true; vm.findLargeFiles() }) { Text("Find") } }) }
        }
        item {
            Card { ListItem(leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Empty folders") }, supportingContent = { Text(if (emptyFolders.isEmpty()) "Select a folder to inspect safely" else "${emptyFolders.size} empty folders found") }, trailingContent = { OutlinedButton(onClick = onSelectFolder) { Text("Inspect") } }) }
        }
        if (groups.isNotEmpty()) item { Text("Duplicate groups", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        items(groups, key = { it.key }) { group ->
            Card {
                Column(Modifier.padding(8.dp)) {
                    Text("${group.files.size} matching files · ${GuardianViewModel.formatBytes(group.reclaimableBytes)} possible", Modifier.padding(8.dp), fontWeight = FontWeight.SemiBold)
                    group.files.forEach { file ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(enabled = !file.isProtected && !FolderSafety.isLikelyUserCreated(file.displayPath), selected = selected.contains(file.uri), onCheckedChange = { checked -> selected = if (checked) selected + file.uri else selected - file.uri })
                            Column(Modifier.weight(1f)) { Text(file.fileName, maxLines = 1); Text("${GuardianViewModel.formatBytes(file.size)} · ${file.displayPath}", maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    TextButton(onClick = { confirmFiles = group.files.filter { selected.contains(it.uri) && !it.isProtected && !FolderSafety.isLikelyUserCreated(it.displayPath) } }, enabled = group.files.any { selected.contains(it.uri) && !it.isProtected && !FolderSafety.isLikelyUserCreated(it.displayPath) }) { Text("Move selected to Trash") }
                }
            }
        }
        item {
            Card { ListItem(leadingContent = { Icon(Icons.Default.Restore, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Trash") }, supportingContent = { Text("${trash.size} items · default retention is 30 days") }, trailingContent = { OutlinedButton(onClick = { showTrash = !showTrash }) { Text(if (showTrash) "Hide" else "View") } }) }
        }
        item {
            Card { ListItem(leadingContent = { Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary) }, headlineContent = { Text("Operation history") }, supportingContent = { Text("${history.size} recent transactions with status and errors") }, trailingContent = { OutlinedButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide" else "View") } }) }
        }
        if (showTrash) items(trash, key = { it.id }) { item ->
            ListItem(headlineContent = { Text(item.fileName) }, supportingContent = { Text("${GuardianViewModel.formatBytes(item.size)} · ${item.originalPath}") }, trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { onRestore(item) }) { Text("Restore") }; IconButton(onClick = { permanentDelete = item }) { Icon(Icons.Default.DeleteForever, "Permanently delete") } } })
        }
        if (showHistory) items(history, key = { it.id }) { operation ->
            ListItem(headlineContent = { Text("${operation.kind.replaceFirstChar { it.uppercaseChar() }} · ${operation.status}") }, supportingContent = { Text("${operation.sourcePath}${operation.destinationPath?.let { " → $it" }.orEmpty()}${operation.errorMessage?.let { "\n$it" }.orEmpty()}", maxLines = 3) })
        }
    }
    if (showEmptyDialog && emptyFolders.isNotEmpty()) {
        val context = LocalContext.current
        AlertDialog(onDismissRequest = { showEmptyDialog = false }, title = { Text("${emptyFolders.size} empty folders") }, text = { LazyColumn(Modifier.height(260.dp)) { items(emptyFolders, key = { it.uri }) { Text(it.path, Modifier.padding(vertical = 6.dp), fontSize = 12.sp) } } }, confirmButton = { Button(onClick = { vm.deleteEmptyFolders(context, emptyFolders); showEmptyDialog = false }) { Text("Delete empty folders") } }, dismissButton = { TextButton(onClick = { showEmptyDialog = false }) { Text("Leave them") } })
    }
    if (showLarge) AlertDialog(onDismissRequest = { showLarge = false }, title = { Text("Large files") }, text = { LazyColumn { items(large.take(100), key = { it.uri }) { FileRow(it) {} } } }, confirmButton = { TextButton(onClick = { showLarge = false }) { Text("Done") } })
    confirmFiles?.let { files -> AlertDialog(onDismissRequest = { confirmFiles = null }, title = { Text("Move ${files.size} files to Trash?") }, text = { Text("Phone Guardian will verify each copy before removing the original. This can be undone from Trash while retained.") }, confirmButton = { Button(onClick = { files.forEach(vm::moveToTrash); confirmFiles = null }) { Text("Move to Trash") } }, dismissButton = { TextButton(onClick = { confirmFiles = null }) { Text("Cancel") } }) }
    permanentDelete?.let { item -> AlertDialog(onDismissRequest = { permanentDelete = null }, title = { Text("Permanently delete ${item.fileName}?") }, text = { Text("This cannot be undone. The Trash copy will be erased from this phone.") }, confirmButton = { Button(onClick = { vm.permanentlyDelete(item); permanentDelete = null }) { Text("Permanently delete") } }, dismissButton = { TextButton(onClick = { permanentDelete = null }) { Text("Cancel") } }) }
}

@Composable
private fun BackupScreen(vm: GuardianViewModel, onChooseDestination: () -> Unit) {
    val summary by vm.summary.collectAsStateCompat()
    val progress by vm.backupProgress.collectAsStateCompat()
    val result by vm.backupResult.collectAsStateCompat()
    val contactResult by vm.contactResult.collectAsStateCompat()
    val context = LocalContext.current
    val contactPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vcard")) { uri -> if (uri != null) vm.exportContacts(context, uri) }
    val contactPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) contactPicker.launch("contacts.vcf")
    }
    var serverOn by remember { mutableStateOf(LocalServerRegistry.running) }
    var serverAddress by remember { mutableStateOf<String?>(LocalServerRegistry.address) }
    LaunchedEffect(serverOn) {
        if (!serverOn) serverAddress = null
        while (serverOn) {
            serverAddress = LocalServerRegistry.address
            delay(400)
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Backup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Maximum Accessible Phone Backup", style = MaterialTheme.typography.titleMedium)
        Text("Back up files Android allows this app to read. Private app data and a bit-for-bit system clone are not accessible to a normal app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Local folder backup", fontWeight = FontWeight.Bold)
            Text("Offline, resumable and checksum-verified. Select a folder on an SD card, USB drive or another provider.")
            Button(onClick = onChooseDestination) { Icon(Icons.Default.Backup, null); Spacer(Modifier.width(8.dp)); Text("Choose destination") }
            if (progress != null) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("${progress!!.completed} / ${progress!!.total} · ${progress!!.current}") }
            result?.let { Text("Copied ${it.copied}, skipped ${it.skipped}, verified ${GuardianViewModel.formatBytes(it.verifiedBytes)}, failed ${it.failed.size}", color = if (it.failed.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        } }
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Contacts", fontWeight = FontWeight.Bold)
            Text("Export a standard VCF file. Existing contacts are never overwritten.")
            OutlinedButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) contactPicker.launch("contacts.vcf")
                else contactPermission.launch(Manifest.permission.READ_CONTACTS)
            }) { Icon(Icons.Default.Description, null); Spacer(Modifier.width(8.dp)); Text("Export contacts") }
            contactResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Access from a laptop", fontWeight = FontWeight.Bold)
            Text("Read and download indexed files from a browser on the same Wi-Fi. Internet exposure is blocked by design.")
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(serverOn, { serverOn = it; if (it) vm.startLocalServer(context) else vm.stopLocalServer(context) }); Spacer(Modifier.width(8.dp)); Text(if (serverOn) "Server enabled" else "Server off") }
            if (serverOn) {
                val pairingUrl = serverAddress?.let { "$it?token=${vm.localServerToken().orEmpty()}" }
                pairingUrl?.let { url ->
                    val qr = remember(url) { QrCode.create(url).asImageBitmap() }
                    Image(qr, "Pairing QR code", Modifier.size(180.dp).align(Alignment.CenterHorizontally))
                    Text(url, style = MaterialTheme.typography.bodySmall)
                } ?: Text("Starting local server…", style = MaterialTheme.typography.bodySmall)
                Text("Keep this address private. Disconnect all devices by switching the server off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        Text("Indexed: ${summary.fileCount} files · ${GuardianViewModel.formatBytes(summary.usedBytes)} phone storage used", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AssistantScreen(vm: GuardianViewModel) {
    val context = LocalContext.current
    val response by vm.assistantResponse.collectAsStateCompat()
    val pending by vm.pendingConfirmation.collectAsStateCompat()
    var text by rememberSaveable { mutableStateOf("") }
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) { text = spoken; vm.handleAssistantCommand(spoken, context) }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Jarvis") })
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Jarvis", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Explicit commands only. Voice processing stays in Android's recognizer; no recording is stored by Phone Guardian.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Text(response, Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium) }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Try: find duplicate photos") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { if (text.isNotBlank()) vm.handleAssistantCommand(text, context) }, modifier = Modifier.weight(1f)) { Text("Run safely") }
            OutlinedButton(onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speechLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Jarvis") })
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text("Speak") }
        }
        Text("Safe examples", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf("What’s using most of my storage?", "Show files larger than 1 GB", "Find duplicate photos", "Jarvis, organize my downloads").forEach { example -> AssistChip(onClick = { text = example; vm.handleAssistantCommand(example, context) }, label = { Text(example) }) }
    }
    if (pending != null) AlertDialog(onDismissRequest = vm::dismissConfirmation, title = { Text("Confirmation required") }, text = { Text("Deleting is never automatic. Review duplicate groups and choose individual files to move to Trash.") }, confirmButton = { Button(onClick = vm::confirmPendingDuplicateDeletion) { Text("Review Clean") } }, dismissButton = { TextButton(onClick = vm::dismissConfirmation) { Text("Cancel") } })
}

@Composable
private fun OrganizationPreviewDialog(items: List<OrganizationSuggestion>, scanning: Boolean, onApprove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Proposed organization") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${items.size} files · ${GuardianViewModel.formatBytes(items.sumOf { it.source.size })}")
            Text("No changes have been made. Existing protected folders are excluded.", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.height(300.dp)) { items(items.take(80), key = { it.source.uri }) { suggestion -> Column(Modifier.padding(vertical = 5.dp)) { Text("Before: ${suggestion.source.displayPath}", fontSize = 12.sp); Text("After: ${suggestion.destinationPath}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary); Text(suggestion.reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            if (items.size > 80) Text("Showing first 80; all files will be listed in the operation log.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { Button(onClick = onApprove, enabled = !scanning) { Text("Approve after choosing folder") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Reject") } })
}

@Composable
private fun ConfirmTrashDialog(file: FileEntity, confirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Move to Trash?") }, text = { Text("${file.fileName}\n${GuardianViewModel.formatBytes(file.size)}\n\nThe original is verified before it is removed. Retention defaults to 30 days.") }, confirmButton = { Button(onClick = confirm) { Text("Move to Trash") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun PrivacyCenterDialog(vm: GuardianViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val files by vm.files.collectAsStateCompat()
    val protectedFoldersState by vm.protectedFolders.collectAsStateCompat()
    var path by rememberSaveable { mutableStateOf("") }
    var retention by remember { mutableStateOf(vm.settings.trashRetentionDays) }
    var autoEmpty by remember { mutableStateOf(vm.settings.autoRemoveEmptyFolders) }
    var aiMode by remember { mutableStateOf(vm.settings.aiMode) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Privacy Center") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Files scanned: ${if (files.isEmpty()) "No" else "Yes"}")
            Text("Files uploaded by Phone Guardian: 0")
            Text("AI processing: ${if (aiMode == "local") "On device" else "Off / rule-based"}")
            Text("Cloud AI: Disabled")
            Text("Google Drive: Not connected")
            Text("Local server: ${if (LocalServerRegistry.running) "On" else "Off"}")
            Text("Microphone: ${if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "Permission granted, recording off" else "Off"}")
            Text("Accessibility: Optional and controlled by Android Settings")
            Divider()
            Text("Trash retention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7 to "7d", 15 to "15d", 30 to "30d", 0 to "Never").forEach { (days, label) ->
                    FilterChip(selected = retention == days, onClick = { retention = days; vm.settings.trashRetentionDays = days }, label = { Text(label) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Automatically remove empty folders", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(autoEmpty, { autoEmpty = it; vm.settings.autoRemoveEmptyFolders = it })
            }
            Text("Empty-folder automation is off by default and only applies after a successful SAF scan.", style = MaterialTheme.typography.bodySmall)
            Text("AI mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("off" to "Off", "local" to "On-device").forEach { (mode, label) -> FilterChip(selected = aiMode == mode, onClick = { aiMode = mode; vm.settings.aiMode = mode }, label = { Text(label) }) }
            }
            Divider()
            Text("Protected locations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Unfamiliar existing folders are also protected automatically. Add a path such as Pictures/Wedding Photos to make the choice explicit.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(path, { path = it }, Modifier.weight(1f), placeholder = { Text("Relative folder path") }, singleLine = true)
                TextButton(onClick = { vm.protectFolder(path); path = "" }, enabled = path.isNotBlank()) { Text("Add") }
            }
            protectedFoldersState.take(4).forEach { folder ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text(folder.label, Modifier.weight(1f)); TextButton(onClick = { vm.unprotectFolder(folder.key) }) { Text("Remove") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("Done") } })
}

@Suppress("UNCHECKED_CAST")
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): androidx.compose.runtime.State<T> = collectAsState(initial = value)
