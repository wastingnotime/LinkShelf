package org.wastingnotime.myfeed.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wastingnotime.myfeed.preview.PreviewImages
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.wastingnotime.myfeed.data.LinkAction
import org.wastingnotime.myfeed.data.SavedLink
import java.text.DateFormat
import java.util.Date

private enum class Destination { Main, Grouped, Archived }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(model: FeedViewModel, open: (SavedLink) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    var destination by rememberSaveable { mutableStateOf(Destination.Main) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) {
        model.events.collect { notice ->
            val result = snackbar.showSnackbar(notice.text,
                actionLabel = if (notice.undo != null) "Undo" else null,
                withDismissAction = true,
                duration = if (notice.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) notice.undo?.let(model::undo)
        }
    }
    BackHandler(destination != Destination.Main) { destination = Destination.Main }
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF315C4B),
        background = Color(0xFFF6F7F3), surface = Color(0xFFF6F7F3))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("my-feed") }, actions = {
                TextButton(onClick = { adding = true }) { Text("Paste link") }
            }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    Destination.entries.forEach { tab ->
                        NavigationBarItem(selected = destination == tab,
                            onClick = { destination = tab },
                            icon = { Text(when (tab) {
                                Destination.Main -> "▤"
                                Destination.Grouped -> "▦"
                                Destination.Archived -> "▣"
                            }) }, label = { Text(tab.name) })
                    }
                }
            },
        ) { padding ->
            val links = state.links.filter {
                if (destination == Destination.Archived) it.archivedAt != null else it.archivedAt == null
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(if (destination == Destination.Archived) "Saved for later, out of your inbox"
                    else "Your inbox · newest first", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                if (destination == Destination.Grouped) {
                    Text("Grouping is coming. For now, this shows the same inbox as Main.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    links.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(if (destination == Destination.Archived) "No archived links yet."
                            else "A quiet inbox. Share a link to my-feed from another app, or paste one here.")
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(links, key = { it.id }) { link ->
                            LinkCard(link, open = { open(link) }, act = { model.act(link.id, it) },
                                retry = { model.retryPreview(link.id) })
                        }
                    }
                }
            }
        }
        if (adding) {
            var text by rememberSaveable { mutableStateOf("") }
            AlertDialog(onDismissRequest = { adding = false },
                title = { Text("Paste a link") },
                text = {
                    OutlinedTextField(value = text, onValueChange = { text = it },
                        label = { Text("URL or shared text") },
                        supportingText = { Text("Saves the first valid HTTP or HTTPS link. Works offline.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth())
                },
                confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = {
                    model.capture(text); adding = false
                }) { Text("Save link") } },
                dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkCard(link: SavedLink, open: () -> Unit, act: (LinkAction) -> Unit, retry: () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    val currentAction by rememberUpdatedState(act)
    LaunchedEffect(dismiss.settledValue) {
        when (dismiss.settledValue) {
            SwipeToDismissBoxValue.StartToEnd -> currentAction(LinkAction.Archive)
            SwipeToDismissBoxValue.EndToStart -> currentAction(LinkAction.Delete)
            SwipeToDismissBoxValue.Settled -> Unit
        }
        dismiss.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(state = dismiss, modifier = Modifier.clip(MaterialTheme.shapes.medium),
        enableDismissFromStartToEnd = link.archivedAt == null,
        backgroundContent = {
            val deleting = dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart
            Box(Modifier.fillMaxSize().background(if (deleting) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer).padding(24.dp),
                contentAlignment = if (deleting) Alignment.CenterEnd else Alignment.CenterStart) {
                if (dismiss.dismissDirection != SwipeToDismissBoxValue.Settled) {
                    Text(if (deleting) "Delete" else "Archive")
                }
            }
        }) {
        Card(onClick = open, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val context = LocalContext.current
                val image by produceState<android.graphics.Bitmap?>(null, link.imagePath) {
                    value = withContext(Dispatchers.IO) { link.imagePath?.let { PreviewImages(context).load(it) } }
                }
                image?.let { Image(it.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop) }
                Text(link.siteName?.let { "$it · ${link.host}" } ?: link.host, style = MaterialTheme.typography.titleMedium)
                Text(link.title ?: link.originalUrl, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge)
                link.description?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium) }
                if (link.title != null) Text(link.originalUrl, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                Text("Saved " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(link.capturedAt)), style = MaterialTheme.typography.labelMedium)
                Text(when (link.previewStatus) {
                    "pending" -> "Saved offline · preview pending"
                    "ready" -> "Preview saved · available offline"
                    else -> "Preview unavailable · link saved"
                }, style = MaterialTheme.typography.bodySmall)
                if (link.previewStatus == "failed") TextButton(onClick = retry) { Text("Retry preview") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        act(if (link.archivedAt == null) LinkAction.Archive else LinkAction.Restore)
                    }) { Text(if (link.archivedAt == null) "Archive" else "Restore") }
                    TextButton(onClick = { act(LinkAction.Delete) }) { Text("Delete") }
                }
            }
        }
    }
}
