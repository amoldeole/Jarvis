package com.jarvis.phoneguardian.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.jarvis.phoneguardian.PhoneGuardianApplication
import com.jarvis.phoneguardian.assistant.AssistantIntent
import com.jarvis.phoneguardian.assistant.IntentParser
import com.jarvis.phoneguardian.core.ai.DisabledAIProvider
import com.jarvis.phoneguardian.core.ai.LocalMetadataAIProvider
import com.jarvis.phoneguardian.core.backup.BackupProgress
import com.jarvis.phoneguardian.core.backup.ContactBackupManager
import com.jarvis.phoneguardian.core.cleanup.EmptyFolder
import com.jarvis.phoneguardian.core.cleanup.EmptyFolderFinder
import com.jarvis.phoneguardian.core.backup.BackupResult
import com.jarvis.phoneguardian.core.backup.LocalSafBackupProvider
import com.jarvis.phoneguardian.core.duplicate.DuplicateFinder
import com.jarvis.phoneguardian.core.model.DuplicateGroup
import com.jarvis.phoneguardian.core.model.FileEntity
import com.jarvis.phoneguardian.core.model.MediaTypes
import com.jarvis.phoneguardian.core.model.OrganizationSuggestion
import com.jarvis.phoneguardian.core.model.ScanProgress
import com.jarvis.phoneguardian.core.model.StorageSummary
import com.jarvis.phoneguardian.core.model.TrashEntity
import com.jarvis.phoneguardian.core.model.ProtectedFolderEntity
import com.jarvis.phoneguardian.core.operations.FileOperationEngine
import com.jarvis.phoneguardian.core.organizer.OrganizationEngine
import com.jarvis.phoneguardian.core.server.LocalFileServerService
import com.jarvis.phoneguardian.core.security.GuardianSettings
import com.jarvis.phoneguardian.core.server.LocalServerRegistry
import com.jarvis.phoneguardian.core.storage.StorageScanner
import com.jarvis.phoneguardian.core.work.IndexWorker
import com.jarvis.phoneguardian.core.work.TrashExpiryWorker
import com.jarvis.phoneguardian.assistant.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class GuardianViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PhoneGuardianApplication
    private val db = app.database
    private val scanner = StorageScanner(application, db)
    private val operations = FileOperationEngine(application, db)
    private val organizer = OrganizationEngine(db)
    private val duplicates = DuplicateFinder(db)
    val settings = GuardianSettings(application)

    val files: StateFlow<List<FileEntity>> = db.fileDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val protectedFolders = db.protectionDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val operationsLog = db.operationDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val trash: StateFlow<List<TrashEntity>> = db.trashDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val summary: StateFlow<StorageSummary> = files.map { list ->
        val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        StorageSummary(
            totalBytes = total,
            freeBytes = free,
            usedBytes = (total - free).coerceAtLeast(0),
            photosBytes = list.filter { it.mediaType == MediaTypes.PHOTO }.sumOf { it.size },
            videosBytes = list.filter { it.mediaType == MediaTypes.VIDEO }.sumOf { it.size },
            documentsBytes = list.filter { it.mediaType == MediaTypes.DOCUMENT }.sumOf { it.size },
            audioBytes = list.filter { it.mediaType == MediaTypes.AUDIO }.sumOf { it.size },
            downloadsBytes = list.filter { it.displayPath.contains("download", true) }.sumOf { it.size },
            installersBytes = list.filter { it.mediaType == MediaTypes.INSTALLER }.sumOf { it.size },
            archivesBytes = list.filter { it.mediaType == MediaTypes.ARCHIVE }.sumOf { it.size },
            otherBytes = list.filter { it.mediaType == MediaTypes.OTHER }.sumOf { it.size },
            fileCount = list.size
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageSummary())

    private val _scan = MutableStateFlow(ScanProgress("fast", 0, 0, false))
    val scan: StateFlow<ScanProgress> = _scan.asStateFlow()
    private val _organization = MutableStateFlow<List<OrganizationSuggestion>>(emptyList())
    val organization: StateFlow<List<OrganizationSuggestion>> = _organization.asStateFlow()
    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = _duplicateGroups.asStateFlow()
    private val _largeFiles = MutableStateFlow<List<FileEntity>>(emptyList())
    val largeFiles: StateFlow<List<FileEntity>> = _largeFiles.asStateFlow()
    private val _emptyFolders = MutableStateFlow<List<EmptyFolder>>(emptyList())
    val emptyFolders: StateFlow<List<EmptyFolder>> = _emptyFolders.asStateFlow()
    private val _searchResults = MutableStateFlow<List<FileEntity>>(emptyList())
    val searchResults: StateFlow<List<FileEntity>> = _searchResults.asStateFlow()
    private val _backupProgress = MutableStateFlow<BackupProgress?>(null)
    val backupProgress: StateFlow<BackupProgress?> = _backupProgress.asStateFlow()
    private val _backupResult = MutableStateFlow<BackupResult?>(null)
    val backupResult: StateFlow<BackupResult?> = _backupResult.asStateFlow()
    private val _contactResult = MutableStateFlow<String?>(null)
    val contactResult: StateFlow<String?> = _contactResult.asStateFlow()
    private val _assistantResponse = MutableStateFlow("Ask Jarvis to search or manage your storage.")
    val assistantResponse: StateFlow<String> = _assistantResponse.asStateFlow()
    private val _pendingConfirmation = MutableStateFlow<AssistantIntent?>(null)
    val pendingConfirmation: StateFlow<AssistantIntent?> = _pendingConfirmation.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) { db.operationDao().markInterrupted(System.currentTimeMillis()) }
        scheduleMaintenance()
    }

    fun startScan(selectedTrees: List<Uri> = emptyList(), deepHash: Boolean = false) {
        if (scanJob?.isActive == true) return
        scanJob = viewModelScope.launch {
            _scan.value = ScanProgress("fast", 0, 0, true, "Starting safe scan…")
            scanner.scan(StorageScanner.Options(selectedTrees = selectedTrees, deepHash = deepHash)) { _scan.value = it }
        }
    }

    fun buildOrganizationPreview() {
        viewModelScope.launch {
            val provider = if (settings.aiMode == "local") LocalMetadataAIProvider() else DisabledAIProvider()
            _organization.value = organizer.buildPreview(provider)
        }
    }

    fun approveOrganization(destinationTree: Uri, selected: List<OrganizationSuggestion> = organization.value) {
        viewModelScope.launch(Dispatchers.IO) {
            for (suggestion in selected.filter { !it.source.isProtected }) {
                operations.moveToSafFolder(suggestion.source, destinationTree, "Phone/${suggestion.destinationLabel}")
            }
            withContext(Dispatchers.Main) { _organization.value = emptyList() }
        }
    }

    fun deepScanForDuplicates() {
        viewModelScope.launch(Dispatchers.IO) {
            _scan.value = ScanProgress("deep", 0, files.value.size, true, "Creating exact checksums…")
            scanner.deepHash(files.value) { complete, total ->
                _scan.value = ScanProgress("deep", complete, total, complete < total, "Checksum $complete of $total")
            }
            _duplicateGroups.value = duplicates.find(DuplicateFinder.Mode.EXACT)
            _scan.value = _scan.value.copy(isRunning = false, message = "Duplicate scan complete")
        }
    }

    fun loadDuplicates(metadataMode: Boolean = false) {
        viewModelScope.launch { _duplicateGroups.value = duplicates.find(if (metadataMode) DuplicateFinder.Mode.METADATA else DuplicateFinder.Mode.EXACT) }
    }

    fun findLargeFiles(minimumBytes: Long = 500L * 1024 * 1024) {
        viewModelScope.launch { _largeFiles.value = db.fileDao().largerThan(minimumBytes) }
    }

    fun findEmptyFolders(context: Context, tree: Uri) {
        viewModelScope.launch { _emptyFolders.value = EmptyFolderFinder(context.applicationContext, db.operationDao()).find(tree) }
    }

    fun deleteEmptyFolders(context: Context, folders: List<EmptyFolder>) {
        viewModelScope.launch {
            EmptyFolderFinder(context.applicationContext, db.operationDao()).deleteConfirmed(folders)
            _emptyFolders.value = emptyList()
        }
    }

    fun search(query: String) {
        viewModelScope.launch { _searchResults.value = if (query.isBlank()) emptyList() else db.fileDao().search(query.trim()) }
    }

    fun protectFolder(path: String) {
        val normalized = path.trim().trim('/').takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            db.protectionDao().upsert(ProtectedFolderEntity(key = normalized, label = normalized))
            db.fileDao().markProtected(normalized)
        }
    }

    fun unprotectFolder(path: String) {
        viewModelScope.launch { db.protectionDao().delete(path) }
    }

    fun moveToTrash(file: FileEntity) {
        viewModelScope.launch { operations.moveToTrash(file, settings.trashRetentionDays) }
    }

    fun permanentlyDelete(item: TrashEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val trashFile = File(Uri.parse(item.trashUri).path.orEmpty())
            if (!trashFile.exists() || trashFile.delete()) db.trashDao().delete(item.id)
        }
    }

    fun restoreFromTrash(context: Context, item: TrashEntity, destination: Uri) {
        viewModelScope.launch { operations.restoreFromTrash(item, destination) }
    }

    fun exportContacts(context: Context, destination: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ContactBackupManager(context.applicationContext).exportVcf(destination) }
                .onSuccess { _contactResult.value = "Exported $it contacts to a VCF backup." }
                .onFailure { _contactResult.value = "Contact export failed safely: ${it.message ?: "Android denied access"}" }
        }
    }

    fun backupTo(context: Context, destination: Uri, documentsOnly: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val selected = if (documentsOnly) files.value.filter { it.mediaType == MediaTypes.DOCUMENT } else files.value
            val provider = LocalSafBackupProvider(context.applicationContext, db)
            _backupProgress.value = BackupProgress(0, selected.size, 0, 0, "Starting…")
            _backupResult.value = provider.backup(selected, destination) { _backupProgress.value = it }
        }
    }

    fun startLocalServer(context: Context) {
        val intent = Intent(context, LocalFileServerService::class.java).setAction(LocalFileServerService.ACTION_START)
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    fun stopLocalServer(context: Context) {
        context.startService(Intent(context, LocalFileServerService::class.java).setAction(LocalFileServerService.ACTION_STOP))
    }

    fun isLocalServerRunning(): Boolean = LocalServerRegistry.running
    fun localServerAddress(): String? = LocalServerRegistry.address
    fun localServerToken(): String? = LocalServerRegistry.token

    fun handleAssistantCommand(raw: String, context: Context) {
        val parsed = IntentParser.parse(raw)
        viewModelScope.launch {
            when (val intent = parsed.intent) {
                AssistantIntent.Unknown -> _assistantResponse.value = "I didn't recognize that as a safe Phone Guardian command. Try: find duplicate photos."
                AssistantIntent.StorageSummary -> {
                    val current = summary.value
                    _assistantResponse.value = "${formatBytes(current.freeBytes)} is free. The largest indexed categories are videos (${formatBytes(current.videosBytes)}), photos (${formatBytes(current.photosBytes)}) and downloads (${formatBytes(current.downloadsBytes)})."
                }
                is AssistantIntent.SearchFiles -> {
                    search(intent.query)
                    _assistantResponse.value = "Searching your local index for “${intent.query}”. Nothing was uploaded."
                }
                AssistantIntent.OrganizeFiles -> {
                    buildOrganizationPreview()
                    _assistantResponse.value = "I prepared a non-destructive organization preview. Review it before approving any move."
                }
                AssistantIntent.FindDuplicates -> {
                    if (files.value.any { it.sha256.isNullOrBlank() }) deepScanForDuplicates() else loadDuplicates()
                    _assistantResponse.value = "I’m checking exact duplicates locally. I will not delete anything."
                }
                is AssistantIntent.LargeFiles -> {
                    findLargeFiles(intent.minimumBytes)
                    _assistantResponse.value = "Showing files larger than ${formatBytes(intent.minimumBytes)}."
                }
                is AssistantIntent.OldFiles -> {
                    _searchResults.value = db.fileDao().olderThan(intent.beforeMillis)
                    _assistantResponse.value = "Showing older files for review. They will not be deleted automatically."
                }
                AssistantIntent.DeleteDuplicates -> {
                    _pendingConfirmation.value = intent
                    _assistantResponse.value = "I can move selected duplicate files to Trash after you review the groups. Shall I prepare that action?"
                }
                AssistantIntent.Back -> _assistantResponse.value = if (JarvisAccessibilityService.perform(AssistantIntent.Back)) "Going back." else "Accessibility access is off, so I cannot control the current app."
                AssistantIntent.Home -> _assistantResponse.value = if (JarvisAccessibilityService.perform(AssistantIntent.Home)) "Going home." else "Enable optional Accessibility access to control other apps."
                AssistantIntent.ScrollUp, AssistantIntent.ScrollDown, AssistantIntent.ClickFirstResult -> _assistantResponse.value = if (JarvisAccessibilityService.perform(intent)) "Command sent to the visible app." else "Enable optional Accessibility access to control visible controls."
                is AssistantIntent.OpenApp -> openApp(intent.appName, context)
                is AssistantIntent.VolumeDelta -> changeVolume(intent.percent, context)
                AssistantIntent.Mute -> mute(context)
                AssistantIntent.MediaPlay -> mediaKey(AudioManager.KEYCODE_MEDIA_PLAY, context)
                AssistantIntent.MediaPause -> mediaKey(AudioManager.KEYCODE_MEDIA_PAUSE, context)
                is AssistantIntent.MediaSeek -> {
                    mediaKey(if (intent.seconds >= 0) AudioManager.KEYCODE_MEDIA_FAST_FORWARD else AudioManager.KEYCODE_MEDIA_REWIND, context)
                    _assistantResponse.value = "A seek command was sent to the active media player; exact interval is controlled by that player."
                }
                is AssistantIntent.CallContact -> _assistantResponse.value = "I can prepare a call to ${intent.name}, but I will not place a call without an explicit Android dialer confirmation."
                AssistantIntent.BackupDocuments, AssistantIntent.BackupEverything -> _assistantResponse.value = "Choose a backup destination in Backup. A backup never starts from voice alone."
            }
        }
    }

    fun confirmPendingDuplicateDeletion() {
        _pendingConfirmation.value = null
        _assistantResponse.value = "Please select specific copies in Clean. Phone Guardian always requires a file-level review before Trash."
    }

    fun dismissConfirmation() { _pendingConfirmation.value = null }

    private fun openApp(name: String, context: Context) {
        val packageManager = context.packageManager
        val app = packageManager.getInstalledApplications(0).firstOrNull {
            packageManager.getApplicationLabel(it).toString().contains(name, ignoreCase = true) || it.packageName.contains(name, true)
        }
        if (app == null) {
            _assistantResponse.value = "I couldn't find an installed app named $name."
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch == null) {
            _assistantResponse.value = "Android did not provide a launch action for $name."
            return
        }
        runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess { _assistantResponse.value = "Opening $name." }
            .onFailure { _assistantResponse.value = "Android did not allow that app to open." }
    }

    private fun changeVolume(percent: Int, context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (current + max * percent / 100).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
        _assistantResponse.value = "Media volume adjusted."
    }

    private fun mute(context: Context) {
        context.getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
        _assistantResponse.value = "Media muted."
    }

    private fun mediaKey(keyCode: Int, context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        _assistantResponse.value = "Media command sent to the active player."
    }

    private fun scheduleMaintenance() {
        val wm = WorkManager.getInstance(getApplication())
        wm.enqueueUniquePeriodicWork("guardian_index", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS).build())
        wm.enqueueUniquePeriodicWork("guardian_trash_expiry", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<TrashExpiryWorker>(1, TimeUnit.DAYS).build())
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var index = -1
            do { value /= 1024; index++ } while (value >= 1024 && index < units.lastIndex)
            return "%.1f %s".format(value, units[index])
        }
    }
}
