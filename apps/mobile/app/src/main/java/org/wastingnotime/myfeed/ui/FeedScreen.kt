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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.wastingnotime.myfeed.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.wastingnotime.myfeed.data.LinkAction
import org.wastingnotime.myfeed.data.SavedLink
import org.wastingnotime.myfeed.data.FeedDestination
import org.wastingnotime.myfeed.data.LinkGroup
import org.wastingnotime.myfeed.data.SearchScope
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(model: FeedViewModel, open: (SavedLink) -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", 0) }
    var theme by remember { mutableStateOf(preferences.getString("theme", "System") ?: "System") }
    val dark = theme == "Dark" || (theme == "System" && androidx.compose.foundation.isSystemInDarkTheme())
    SideEffect {
        (context as? android.app.Activity)?.let { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val selection by model.selection.collectAsStateWithLifecycle()
    val destination = selection.destination
    var adding by rememberSaveable { mutableStateOf(false) }
    val paste by model.paste.collectAsStateWithLifecycle()
    val inboxScroll = rememberLazyListState()
    val groupedScroll = rememberLazyListState()
    val archiveScroll = rememberLazyListState()
    val searchScroll = rememberLazyListState()
    LaunchedEffect(paste.saved) {
        if (paste.saved) { adding = false; model.resetPaste(); model.notify("Saved to inbox") }
    }
    LaunchedEffect(selection.query, selection.scope) { if (selection.searching) searchScroll.scrollToItem(0) }
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
    LifecycleResumeEffect(model) { model.refresh(); onPauseOrDispose {} }
    BackHandler(selection.searching || destination != FeedDestination.Main) {
        if (selection.searching) model.closeSearch() else model.navigate(FeedDestination.Main)
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFFA0D0B8),
        background = Color(0xFF141A17), surface = Color(0xFF141A17)) else lightColorScheme(primary = Color(0xFF315C4B),
        background = Color(0xFFF6F7F3), surface = Color(0xFFF6F7F3))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis) }, actions = {
                IconButton(onClick = { if (selection.searching) model.closeSearch() else model.startSearch() }) {
                    Icon(if (selection.searching) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (selection.searching) "Close search" else "Search")
                }
                IconButton(onClick = { adding = true }) { Icon(painterResource(R.drawable.ic_add_link), "Paste link") }
                AppSettings(theme, {
                    theme = it
                    preferences.edit().putString("theme", it).apply()
                    syncLaunchAppearance(context, it)
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
                        (context as? android.app.Activity)?.recreate()
                    }
                }, model)

            }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    FeedDestination.entries.forEach { tab ->
                        NavigationBarItem(selected = destination == tab,
                            onClick = { model.closeSearch(); model.navigate(tab) },
                            icon = {
                                val selected = destination == tab
                                Icon(painterResource(when (tab) {
                                    FeedDestination.Main -> if (selected) R.drawable.ic_inbox_filled else R.drawable.ic_inbox
                                    FeedDestination.Grouped -> if (selected) R.drawable.ic_grid_view_filled else R.drawable.ic_grid_view
                                    FeedDestination.Archived -> if (selected) R.drawable.ic_archive_filled else R.drawable.ic_archive
                                }), contentDescription = null)
                            }, label = { Text(if (tab == FeedDestination.Main) "Inbox" else tab.name) })
                    }
                }
            },
        ) { padding ->
            val links = state.content.links
            val showing = state.selection
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (selection.searching) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(value = selection.query, onValueChange = model::search,
                        label = { Text("Search saved links") }, singleLine = true,
                        trailingIcon = { if (selection.query.isNotEmpty()) TextButton(onClick = { model.search("") }) { Text("Clear") } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchScope.entries.forEach { scope ->
                            FilterChip(selected = selection.scope == scope, onClick = { model.scope(scope) },
                                label = { Text(scope.name) }, modifier = Modifier.testTag("scope-${scope.name}"))
                        }
                    }
                }
                Text(if (showing.searching) "${links.size} results · ${showing.scope.name} · newest first"
                    else if (showing.destination == FeedDestination.Archived) "Archived · grouped by capture date and similarity"
                    else if (showing.destination == FeedDestination.Grouped) "Inbox · grouped by source and similar titles"
                    else "Inbox · newest first", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    links.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(if (showing.searching) "No matching links in ${showing.scope.name}."
                            else if (showing.destination == FeedDestination.Archived) "No archived links yet."
                            else stringResource(R.string.inbox_empty))
                    }
                    // A different view/query starts at its newest result, including group/date headings.
                    else -> {
                        LazyColumn(state = if (showing.searching) searchScroll else when (showing.destination) {
                            FeedDestination.Main -> inboxScroll
                            FeedDestination.Grouped -> groupedScroll
                            FeedDestination.Archived -> archiveScroll
                        }, modifier = Modifier.testTag("feed-list"), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            fun group(group: LinkGroup) {
                                item(key = "group-${group.representative.id}") { GroupHeading(group) }
                                items(group.links, key = { it.id }) { link ->
                                    LinkCard(link, open = { open(link) }, act = { model.act(link.id, it) },
                                        retry = { model.retryPreview(link.id) })
                                }
                            }
                            when {
                                showing.searching || showing.destination == FeedDestination.Main ->
                                    items(links, key = { it.id }) { link ->
                                        LinkCard(link, open = { open(link) }, act = { model.act(link.id, it) },
                                            retry = { model.retryPreview(link.id) }, showScope = showing.searching)
                                    }
                                showing.destination == FeedDestination.Grouped -> state.content.groups.forEach { group(it) }
                                else -> state.content.days.forEach { day ->
                                    item(key = "date-${day.day}") {
                                        Text("Captured ${day.day}", style = MaterialTheme.typography.titleMedium)
                                    }
                                    day.groups.forEach { group(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (adding) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            AlertDialog(onDismissRequest = { if (!paste.saving) adding = false },
                title = { Text("Paste a link") },
                text = {
                    OutlinedTextField(value = paste.text, onValueChange = model::editPaste, enabled = !paste.saving, isError = paste.error != null,
                        label = { Text("URL or shared text") },
                        supportingText = { Text(paste.error ?: "Saves the first link. Works offline.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { model.savePaste() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                },
                confirmButton = { TextButton(enabled = paste.text.isNotBlank() && !paste.saving, onClick = model::savePaste) { Text(if (paste.saving) "Saving…" else "Save link") } },
                dismissButton = { TextButton(enabled = !paste.saving, onClick = { adding = false }) { Text("Cancel") } })
        }
    }
}

@Composable
private fun GroupHeading(group: LinkGroup) {
    val link = group.representative
    val title = if (!group.hasTitleKeywords)
        "Untitled links" else link.title.orEmpty()
    Text("${link.host} · $title · ${group.links.size}",
        modifier = Modifier.testTag("group-${link.id}"), style = MaterialTheme.typography.titleSmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkCard(link: SavedLink, open: () -> Unit, act: (LinkAction) -> Unit, retry: () -> Unit, showScope: Boolean = false) {
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
        Card(onClick = open, modifier = Modifier.fillMaxWidth().testTag("link-${link.id}")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val context = LocalContext.current
                if (showScope) Text(if (link.archivedAt == null) "Inbox" else "Archived",
                    style = MaterialTheme.typography.labelMedium)
                var loading by remember(link.imagePath) { mutableStateOf(link.imagePath != null) }
                var reload by remember { mutableIntStateOf(0) }
                val image by produceState<android.graphics.Bitmap?>(null, link.imagePath, link.previewStatus, reload) {
                    loading = link.imagePath != null
                    value = withContext(Dispatchers.IO) { link.imagePath?.let { PreviewImages(context).load(it) } }
                    loading = false
                }
                image?.let { Image(it.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop) }
                Text(link.title?.takeIf { it.isNotBlank() } ?: link.originalUrl, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text((link.siteName ?: link.host) + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(link.capturedAt)),
                    style = MaterialTheme.typography.labelMedium)
                link.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium) }
                val failed = link.previewStatus.startsWith("failed") || link.previewStatus.startsWith("partial") ||
                    (!loading && link.imagePath != null && image == null)
                if (link.previewStatus == "pending") Text("Fetching preview · link saved", style = MaterialTheme.typography.bodySmall)
                else if (failed) Text("Preview unavailable · link saved", style = MaterialTheme.typography.bodySmall)
                var menu by remember { mutableStateOf(false) }
                var details by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { act(if (link.archivedAt == null) LinkAction.Archive else LinkAction.Restore) }) {
                        Text(if (link.archivedAt == null) "Archive" else "Restore")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More actions") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Share") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) }, onClick = {
                                menu = false
                                try { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, listOfNotNull(link.title, link.originalUrl).joinToString("\n"))
                                }, "Share link")) } catch (_: android.content.ActivityNotFoundException) { }
                            })
                            DropdownMenuItem(text = { Text("Link and preview details") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
                                onClick = { menu = false; details = true })
                            DropdownMenuItem(text = { Text("Delete") },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                                onClick = { menu = false; act(LinkAction.Delete) })
                        }
                    }
                }
                if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("Link details") }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(link.originalUrl)
                        Text("Saved " + DateFormat.getDateTimeInstance().format(Date(link.capturedAt)))
                        Text(when {
                            loading -> "Loading saved image…"
                            link.previewStatus == "pending" -> "Fetching preview; waiting for network or a scheduled retry."
                            failed -> if (link.previewStatus.contains(':')) link.previewStatus.substringAfter(':') else "Saved image unavailable or preview could not be fetched."
                            image != null -> "Image and text saved on this device. The linked page still needs an internet connection."
                            else -> "Text preview saved. No image was found."
                        })
                        TextButton(enabled = link.previewStatus != "pending", onClick = { retry(); reload++; details = false }) { Text("Retry preview") }
                    }
                }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
            }
        }
    }
}
