package org.wastingnotime.linkshelf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.wastingnotime.linkshelf.preview.PreviewImages
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
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
import org.wastingnotime.linkshelf.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.wastingnotime.linkshelf.data.LinkAction
import org.wastingnotime.linkshelf.data.SavedLink
import org.wastingnotime.linkshelf.data.FeedDestination
import org.wastingnotime.linkshelf.data.LinkGroup
import org.wastingnotime.linkshelf.data.SearchScope
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(model: FeedViewModel, open: (SavedLink) -> Unit) {
    val context = LocalContext.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
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
        if (paste.saved) { adding = false; model.resetPaste(); model.notify(context.getString(R.string.saved_inbox)) }
    }
    LaunchedEffect(selection.query, selection.scope) { if (selection.searching) searchScroll.scrollToItem(0) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model) {
        model.events.collect { notice ->
            val result = snackbar.showSnackbar(notice.text,
                actionLabel = if (notice.undo != null) context.getString(R.string.undo) else null,
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
                        contentDescription = if (selection.searching) context.getString(R.string.close_search) else context.getString(R.string.search))
                }
                IconButton(onClick = { adding = true }) { Icon(painterResource(R.drawable.ic_add_link), context.getString(R.string.paste_link)) }
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
                            }, label = { Text(context.getString(tab.labelResource)) })
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
                        label = { Text(context.getString(R.string.search_saved)) }, singleLine = true,
                        trailingIcon = { if (selection.query.isNotEmpty()) TextButton(onClick = { model.search("") }) { Text(context.getString(R.string.clear)) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchScope.entries.forEach { scope ->
                            FilterChip(selected = selection.scope == scope, onClick = { model.scope(scope) },
                                label = { Text(context.getString(scope.labelResource)) }, modifier = Modifier.testTag("scope-${scope.name}"))
                        }
                    }
                }
                Text(if (showing.searching) context.resources.getQuantityString(R.plurals.search_results, links.size, links.size, context.getString(showing.scope.labelResource))
                    else if (showing.destination == FeedDestination.Archived) context.getString(R.string.archive_summary)
                    else if (showing.destination == FeedDestination.Grouped) context.getString(R.string.group_summary)
                    else context.getString(R.string.inbox_summary), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    links.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(if (showing.searching) context.getString(R.string.no_matches, context.getString(showing.scope.labelResource))
                            else if (showing.destination == FeedDestination.Archived) context.getString(R.string.archive_empty)
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
                                item(key = if (group.isFavorites) "group-favorites" else "group-${group.representative.id}") { GroupHeading(group) }
                                items(group.links, key = { it.id }) { link ->
                                    LinkCard(link, open = { open(link) }, act = { model.act(link.id, it) },
                                        favorite = { model.toggleFavorite(link.id) }, retry = { model.retryPreview(link.id) })
                                }
                            }
                            when {
                                showing.searching || showing.destination == FeedDestination.Main ->
                                    items(links, key = { it.id }) { link ->
                                        LinkCard(link, open = { open(link) }, act = { model.act(link.id, it) },
                                            favorite = { model.toggleFavorite(link.id) }, retry = { model.retryPreview(link.id) }, showScope = showing.searching)
                                    }
                                showing.destination == FeedDestination.Grouped -> state.content.groups.forEach { group(it) }
                                else -> state.content.days.forEach { day ->
                                    item(key = "date-${day.day}") {
                                        Text(context.getString(R.string.captured_date, java.time.LocalDate.parse(day.day).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))), style = MaterialTheme.typography.titleMedium)
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
                title = { Text(context.getString(R.string.paste_title)) },
                text = {
                    OutlinedTextField(value = paste.text, onValueChange = model::editPaste, enabled = !paste.saving, isError = paste.error != null,
                        label = { Text(context.getString(R.string.paste_label)) },
                        supportingText = { Text(paste.error ?: context.getString(R.string.paste_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { model.savePaste() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus))
                },
                confirmButton = { TextButton(enabled = paste.text.isNotBlank() && !paste.saving, onClick = model::savePaste) { Text(if (paste.saving) context.getString(R.string.saving) else context.getString(R.string.save_link)) } },
                dismissButton = { TextButton(enabled = !paste.saving, onClick = { adding = false }) { Text(context.getString(R.string.cancel)) } })
        }
    }
}

@Composable
private fun GroupHeading(group: LinkGroup) {
    val context = LocalContext.current
    val link = group.representative
    if (group.isFavorites) {
        Text(context.getString(R.string.favorites_heading, group.links.size), modifier = Modifier.testTag("favorites-heading"),
            style = MaterialTheme.typography.titleSmall)
        return
    }
    val title = if (!group.hasTitleKeywords)
        context.getString(R.string.untitled_links) else link.title.orEmpty()
    Text("${link.host} · $title · ${group.links.size}",
        modifier = Modifier.testTag("group-${link.id}"), style = MaterialTheme.typography.titleSmall,
        maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkCard(link: SavedLink, open: () -> Unit, act: (LinkAction) -> Unit, retry: () -> Unit, favorite: () -> Unit, showScope: Boolean = false) {
    val context = LocalContext.current
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
                    Text(if (deleting) context.getString(R.string.delete) else context.getString(R.string.archive))
                }
            }
        }) {
        Card(onClick = open, modifier = Modifier.fillMaxWidth().testTag("link-${link.id}")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showScope) Text(context.getString(if (link.archivedAt == null) R.string.inbox else R.string.archived),
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
                if (link.previewStatus == "pending") Text(context.getString(R.string.preview_fetching), style = MaterialTheme.typography.bodySmall)
                else if (failed) Text(context.getString(R.string.preview_unavailable), style = MaterialTheme.typography.bodySmall)
                var menu by remember { mutableStateOf(false) }
                var details by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconToggleButton(checked = link.isFavorite, onCheckedChange = { favorite() },
                        modifier = Modifier.testTag("favorite-${link.id}")) {
                        Icon(if (link.isFavorite) androidx.compose.ui.graphics.vector.rememberVectorPainter(Icons.Default.Star) else painterResource(R.drawable.ic_star_outline),
                            contentDescription = context.getString(if (link.isFavorite) R.string.remove_favorite else R.string.add_favorite),
                            tint = if (link.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { act(if (link.archivedAt == null) LinkAction.Archive else LinkAction.Restore) }) {
                        Text(if (link.archivedAt == null) context.getString(R.string.archive) else context.getString(R.string.restore))
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, context.getString(R.string.more_actions)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(context.getString(R.string.share)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_share), contentDescription = null) }, onClick = {
                                menu = false
                                try { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, listOfNotNull(link.title, link.originalUrl).joinToString("\n"))
                                }, context.getString(R.string.share_link))) } catch (_: android.content.ActivityNotFoundException) { }
                            })
                            DropdownMenuItem(text = { Text(context.getString(R.string.preview_details)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_info), contentDescription = null) },
                                onClick = { menu = false; details = true })
                            DropdownMenuItem(text = { Text(context.getString(R.string.delete)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                                onClick = { menu = false; act(LinkAction.Delete) })
                        }
                    }
                }
                if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(context.getString(R.string.link_details)) }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(link.originalUrl)
                        Text(context.getString(R.string.saved_date, DateFormat.getDateTimeInstance().format(Date(link.capturedAt))))
                        Text(when {
                            loading -> context.getString(R.string.loading_image)
                            link.previewStatus == "pending" -> context.getString(R.string.preview_waiting)
                            failed -> context.getString(R.string.preview_failed)
                            image != null -> context.getString(R.string.preview_local)
                            else -> context.getString(R.string.preview_text)
                        })
                        if (failed && link.previewStatus.contains(':')) Text(link.previewStatus.substringAfter(':'),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = link.previewStatus != "pending", onClick = { retry(); reload++; details = false }) { Text(context.getString(R.string.retry_preview)) }
                    }
                }, confirmButton = { TextButton(onClick = { details = false }) { Text(context.getString(R.string.close)) } })
            }
        }
    }
}
