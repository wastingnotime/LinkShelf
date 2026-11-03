package org.wastingnotime.linkshelf.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wastingnotime.linkshelf.FeedApplication
import org.wastingnotime.linkshelf.R
import org.wastingnotime.linkshelf.data.*
import org.wastingnotime.linkshelf.preview.PreviewScheduler
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettings(theme: String, changeTheme: (String) -> Unit, model: FeedViewModel) {
    val context = LocalContext.current
    val app = context.applicationContext as FeedApplication
    val backup = remember { ScheduledBackup(context) }
    var panel by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val operation by model.settings.collectAsStateWithLifecycle()
    val deleted by model.deleted.collectAsStateWithLifecycle()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.settingsOperation {
            val json = FeedBackup.export(app.database)
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(json) }
            context.getString(R.string.backup_exported)
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.settingsOperation {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytesBounded() }
            val raw = bytes.toString(Charsets.UTF_8)
            FeedBackup.parse(raw)
            model.pendingImport(raw)
            context.getString(R.string.backup_review_ready)
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.settingsOperation { backup.configure(uri); context.getString(R.string.folder_saved) }
    }
    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, context.getString(R.string.more)) }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        listOf(
            "Appearance" to R.drawable.ic_palette,
            "Backup" to R.drawable.ic_save,
            "Recently deleted" to R.drawable.ic_delete,
            "About" to R.drawable.ic_info,
        ).forEach { (label, icon) ->
            DropdownMenuItem(text = { Text(context.getString(settingsLabel(label))) },
                leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                onClick = { panel = label; menu = false })
        }
    }
    if (panel == "Backup" || panel == "Recently deleted") {
        Dialog(onDismissRequest = { panel = "" }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BackHandler { panel = "" }
            Scaffold(topBar = { TopAppBar(title = { Text(context.getString(settingsLabel(panel))) }, navigationIcon = {
                IconButton(onClick = { panel = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, context.getString(R.string.back)) }
            }) }) { padding ->
                if (panel == "Backup") {
                    val work by remember { backup.workStatus() }.collectAsStateWithLifecycle(emptyList())
                    var status by remember { mutableStateOf(backup.status()) }
                    LaunchedEffect(work, operation.busy) { status = backup.status() }
                    val running = work.any { it.state == androidx.work.WorkInfo.State.RUNNING }
                    val queued = work.any { it.tags.contains("manual-feed-backup") &&
                        it.state == androidx.work.WorkInfo.State.ENQUEUED }
                    var summary by remember { mutableStateOf<ImportSummary?>(null) }
                    LaunchedEffect(operation.importRaw) {
                        summary = operation.importRaw?.let { raw -> withContext(Dispatchers.IO) {
                            val rows = FeedBackup.parse(raw).distinctBy { it.canonicalUrl }
                            val existing = app.database.links().all().map { it.canonicalUrl }.toSet()
                            val added = rows.filter { it.canonicalUrl !in existing }
                            ImportSummary(added.count { it.deletedAt == null }, added.count { it.deletedAt != null }, rows.size - added.size)
                        } }
                    }
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(context.getString(R.string.manual_backup), style = MaterialTheme.typography.titleLarge)
                        Text(context.getString(R.string.backup_explanation))
                        Button(enabled = !operation.busy, onClick = { export.launch("LinkShelf-${System.currentTimeMillis()}.json") }) { Text(context.getString(R.string.export_backup)) }
                        OutlinedButton(enabled = !operation.busy, onClick = { restore.launch(arrayOf("application/json", "text/plain")) }) { Text(context.getString(R.string.import_backup)) }
                        operation.importRaw?.let { raw ->
                            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(context.getString(R.string.review_import), style = MaterialTheme.typography.titleMedium)
                                Text(summary?.let { context.getString(R.string.import_summary, it.visible, it.deleted, it.duplicates) } ?: context.getString(R.string.checking_links))
                                Text(context.getString(R.string.import_explanation))
                                Button(enabled = !operation.busy && summary != null, onClick = {
                                    model.settingsOperation {
                                        val count = FeedBackup.import(app.database, raw)
                                        DeletedLinkCleanup(app, app.database.links()).purge()
                                        model.pendingImport(null)
                                        app.database.links().pendingPreviews().forEach { PreviewScheduler(context).enqueue(it, recover = true) }
                                        context.resources.getQuantityString(R.plurals.imported_count, count, count)
                                    }
                                }) { Text(context.getString(R.string.confirm_import)) }
                                TextButton(enabled = !operation.busy, onClick = { model.pendingImport(null) }) { Text(context.getString(R.string.cancel_import)) }
                            } }
                        }
                        HorizontalDivider()
                        Text(context.getString(R.string.automatic_backup), style = MaterialTheme.typography.titleLarge)
                        Text(if (status.enabled) context.getString(R.string.daily_backup_enabled) else context.getString(R.string.daily_backup_disabled))
                        Text(context.getString(R.string.backup_folder, backup.folderLabel() ?: context.getString(R.string.backup_folder_not_selected)))
                        Text(context.getString(R.string.backup_last_success, status.lastSuccessMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: context.getString(R.string.never)))
                        if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(context.getString(R.string.writing_backup)) }
                        else if (queued) Text(context.getString(R.string.backup_queued_long))
                        if (status.lastAttemptFailed) Text(context.getString(R.string.backup_failed), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(enabled = !operation.busy && !running, onClick = { folder.launch(null) }) { Text(if (status.enabled) context.getString(R.string.change_backup_folder) else context.getString(R.string.choose_backup_folder)) }
                        if (status.enabled) {
                            Button(enabled = !operation.busy && !running && !queued, onClick = { model.settingsOperation { backup.backupNow(); context.getString(R.string.backup_queued) } }) { Text(context.getString(R.string.back_up_now)) }
                            TextButton(enabled = !operation.busy && !running, onClick = { model.settingsOperation { backup.disable(); context.getString(R.string.backups_disabled) } }) { Text(context.getString(R.string.disable_daily_backup)) }
                        }
                        if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        operation.message?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text(context.getString(R.string.deleted_explanation)) }
                        if (deleted.isEmpty()) item { Text(context.getString(R.string.deleted_empty)) }
                        items(deleted.size, key = { deleted[it].id }) { i -> val link = deleted[i]
                            Card { Column(Modifier.padding(16.dp)) {
                                Text(link.title ?: link.originalUrl, style = MaterialTheme.typography.titleMedium)
                                Text(link.host)
                                Text(context.getString(R.string.deleted_date, DateFormat.getDateInstance().format(Date(requireNotNull(link.deletedAt)))))
                                TextButton(onClick = { model.permanentlyDelete(link.id) }) { Text(context.getString(R.string.delete_permanently), color = MaterialTheme.colorScheme.error) }
                                TextButton(onClick = { model.restoreDeleted(link.id) }) { Text(context.getString(R.string.restore_destination, context.getString(if (link.archivedAt == null) R.string.inbox else R.string.archived))) }
                            } }
                        }
                    }
                }
            }
        }
    } else if (panel.isNotEmpty()) AlertDialog(onDismissRequest = { panel = "" }, title = { Text(context.getString(settingsLabel(panel))) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (panel == "Appearance") listOf("System", "Light", "Dark").forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(selected = theme == mode, role = Role.RadioButton, onClick = { changeTheme(mode) }).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = theme == mode, onClick = null); Text(context.getString(settingsLabel(mode)))
                }
            } else {
                val info = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
                Text(context.getString(R.string.app_version, context.getString(R.string.app_name), info.versionName, androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)), style = MaterialTheme.typography.titleMedium)
                Text(context.getString(R.string.about_description))
                Text(context.getString(R.string.your_privacy), style = MaterialTheme.typography.titleMedium)
                Text(context.getString(R.string.privacy_description))
                TextButton(onClick = { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/wastingnotime/my-feed"))) } catch (_: android.content.ActivityNotFoundException) { model.notify(context.getString(R.string.no_browser)) } }) { Text(context.getString(R.string.source_code)) }
            }
        }
    }, confirmButton = { TextButton(onClick = { panel = "" }) { Text(context.getString(R.string.close)) } })
}

private data class ImportSummary(val visible: Int, val deleted: Int, val duplicates: Int)
private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break
        require(out.size() + count <= FeedBackup.MAX_BYTES) { "Backup exceeds 10 MB" }; out.write(buffer, 0, count) }
    return out.toByteArray()
}
