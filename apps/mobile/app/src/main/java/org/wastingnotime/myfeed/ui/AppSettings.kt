package org.wastingnotime.myfeed.ui

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
import org.wastingnotime.myfeed.FeedApplication
import org.wastingnotime.myfeed.R
import org.wastingnotime.myfeed.data.*
import org.wastingnotime.myfeed.preview.PreviewScheduler
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
            "Backup exported successfully"
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.settingsOperation {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytesBounded() }
            val raw = bytes.toString(Charsets.UTF_8)
            FeedBackup.parse(raw)
            model.pendingImport(raw)
            "Backup ready for review"
        }
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) model.settingsOperation { backup.configure(uri); "Folder saved. Initial backup queued." }
    }
    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        listOf(
            "Appearance" to R.drawable.ic_palette,
            "Backup" to R.drawable.ic_save,
            "Recently deleted" to R.drawable.ic_delete,
            "About" to R.drawable.ic_info,
        ).forEach { (label, icon) ->
            DropdownMenuItem(text = { Text(label) },
                leadingIcon = { Icon(painterResource(icon), contentDescription = null) },
                onClick = { panel = label; menu = false })
        }
    }
    if (panel == "Backup" || panel == "Recently deleted") {
        Dialog(onDismissRequest = { panel = "" }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BackHandler { panel = "" }
            Scaffold(topBar = { TopAppBar(title = { Text(panel) }, navigationIcon = {
                IconButton(onClick = { panel = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
                        Text("Manual backup", style = MaterialTheme.typography.titleLarge)
                        Text("Export links, titles and saved states. Cached images are downloaded again after import.")
                        Button(enabled = !operation.busy, onClick = { export.launch("LinkShelf-${System.currentTimeMillis()}.json") }) { Text("Export backup") }
                        OutlinedButton(enabled = !operation.busy, onClick = { restore.launch(arrayOf("application/json", "text/plain")) }) { Text("Import backup") }
                        operation.importRaw?.let { raw ->
                            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Review import", style = MaterialTheme.typography.titleMedium)
                                Text(summary?.let { "${it.visible} links to add · ${it.deleted} deleted records · ${it.duplicates} existing links skipped" } ?: "Checking links…")
                                Text("Existing links stay unchanged. Deleted records can be recovered in Recently deleted.")
                                Button(enabled = !operation.busy && summary != null, onClick = {
                                    model.settingsOperation {
                                        val count = FeedBackup.import(app.database, raw)
                                        model.pendingImport(null)
                                        app.database.links().pendingPreviews().forEach { PreviewScheduler(context).enqueue(it, recover = true) }
                                        "$count records imported successfully"
                                    }
                                }) { Text("Confirm import") }
                                TextButton(enabled = !operation.busy, onClick = { model.pendingImport(null) }) { Text("Cancel import") }
                            } }
                        }
                        HorizontalDivider()
                        Text("Automatic backup", style = MaterialTheme.typography.titleLarge)
                        Text(if (status.enabled) "Daily backups · keeps the latest 7 files" else "Daily backups are off")
                        Text("Folder: " + (backup.folderLabel() ?: "Not selected"))
                        Text("Last successful backup: " + (status.lastSuccessMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "Never"))
                        if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Writing backup…") }
                        else if (queued) Text("Backup queued · Android will run it when possible")
                        if (status.lastAttemptFailed) Text("Backup failed. Check folder access or choose another folder.", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(enabled = !operation.busy && !running, onClick = { folder.launch(null) }) { Text(if (status.enabled) "Change backup folder" else "Choose backup folder") }
                        if (status.enabled) {
                            Button(enabled = !operation.busy && !running && !queued, onClick = { model.settingsOperation { backup.backupNow(); "Backup queued" } }) { Text("Back up now") }
                            TextButton(enabled = !operation.busy && !running, onClick = { model.settingsOperation { backup.disable(); "Daily backups disabled. Existing files kept." } }) { Text("Disable daily backup") }
                        }
                        if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        operation.message?.let { Text(it, modifier = Modifier.fillMaxWidth()) }
                    }
                } else {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("Deleted links stay here until restored. Restore returns each link to its previous Inbox or Archived view.") }
                        if (deleted.isEmpty()) item { Text("No deleted links") }
                        items(deleted.size, key = { deleted[it].id }) { i -> val link = deleted[i]
                            Card { Column(Modifier.padding(16.dp)) {
                                Text(link.title ?: link.originalUrl, style = MaterialTheme.typography.titleMedium)
                                Text(link.host)
                                TextButton(onClick = { model.restoreDeleted(link.id) }) { Text("Restore to ${if (link.archivedAt == null) "Inbox" else "Archived"}") }
                            } }
                        }
                    }
                }
            }
        }
    } else if (panel.isNotEmpty()) AlertDialog(onDismissRequest = { panel = "" }, title = { Text(panel) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (panel == "Appearance") listOf("System", "Light", "Dark").forEach { mode ->
                Row(Modifier.fillMaxWidth().selectable(selected = theme == mode, role = Role.RadioButton, onClick = { changeTheme(mode) }).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = theme == mode, onClick = null); Text(mode)
                }
            } else {
                val info = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
                Text("${stringResource(R.string.app_name)} ${info.versionName} · build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)}", style = MaterialTheme.typography.titleMedium)
                Text("A personal link inbox by Wasting No Time. Save, revisit and triage links without an account.")
                Text("Your privacy", style = MaterialTheme.typography.titleMedium)
                Text("Links stay on this device. Preview requests contact linked sites. Backups contain links and metadata, without cached images.")
                TextButton(onClick = { try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/wastingnotime/my-feed"))) } catch (_: android.content.ActivityNotFoundException) { model.notify("No browser available") } }) { Text("Source code") }
            }
        }
    }, confirmButton = { TextButton(onClick = { panel = "" }) { Text("Close") } })
}

private data class ImportSummary(val visible: Int, val deleted: Int, val duplicates: Int)
private fun java.io.InputStream.readBytesBounded(): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break
        require(out.size() + count <= FeedBackup.MAX_BYTES) { "Backup exceeds 10 MB" }; out.write(buffer, 0, count) }
    return out.toByteArray()
}
