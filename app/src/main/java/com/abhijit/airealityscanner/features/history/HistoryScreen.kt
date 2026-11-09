@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class, FlowPreview::class)

package com.abhijit.airealityscanner.features.history

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.abhijit.airealityscanner.core.ui.components.EmptyState
import com.abhijit.airealityscanner.core.ui.components.color
import com.abhijit.airealityscanner.core.ui.components.icon
import com.abhijit.airealityscanner.core.util.TextFormat
import com.abhijit.airealityscanner.data.export.ReportExporter
import com.abhijit.airealityscanner.domain.model.ScanCategory
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class HistoryFilter(val query: String = "", val favouritesOnly: Boolean = false, val categoryId: String? = null)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: ScanRepository,
    private val exporter: ReportExporter,
) : ViewModel() {
    val query = MutableStateFlow("")
    val favouritesOnly = MutableStateFlow(false)
    val categoryId = MutableStateFlow<String?>(null)

    val categories: StateFlow<List<ScanCategory>> =
        repository.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessions: StateFlow<List<ScanSession>?> =
        combine(query.debounce(250), favouritesOnly, categoryId) { q, f, c -> HistoryFilter(q, f, c) }
            .flatMapLatest { repository.observeHistory(it.query, it.favouritesOnly, it.categoryId) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _shareEvents = Channel<Uri>(Channel.BUFFERED)
    val shareEvents: Flow<Uri> = _shareEvents.receiveAsFlow()

    fun toggleFavourite(session: ScanSession) {
        viewModelScope.launch { repository.setFavourite(session.id, !session.isFavourite) }
    }

    fun delete(session: ScanSession) {
        viewModelScope.launch { repository.deleteSession(session.id) }
    }

    fun exportAll() {
        viewModelScope.launch {
            val list = sessions.value.orEmpty()
            if (list.isNotEmpty()) _shareEvents.send(exporter.exportHistoryJson(list))
        }
    }
}

@Composable
fun HistoryScreen(
    onOpenScan: (String) -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val favouritesOnly by viewModel.favouritesOnly.collectAsStateWithLifecycle()
    val categoryId by viewModel.categoryId.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<ScanSession?>(null) }

    LaunchedEffect(Unit) {
        viewModel.shareEvents.collect { uri ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("history", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export scan history"))
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Scan History") },
                actions = {
                    IconButton(onClick = viewModel::exportAll, enabled = !sessions.isNullOrEmpty()) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = "Export all results")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.query.value = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search scans, text, notes…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { viewModel.query.value = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = favouritesOnly,
                        onClick = { viewModel.favouritesOnly.value = !favouritesOnly },
                        label = { Text("Favourites") },
                        leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    )
                }
                items(categories, key = { it.id }) { cat ->
                    FilterChip(
                        selected = categoryId == cat.id,
                        onClick = { viewModel.categoryId.value = if (categoryId == cat.id) null else cat.id },
                        label = { Text(cat.name) },
                    )
                }
            }
            val list = sessions
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.History,
                    title = if (query.isBlank() && !favouritesOnly && categoryId == null) "No scans yet" else "No matching scans",
                    message = "Scans you save appear here. They are stored only on this phone.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.id }) { session ->
                        HistoryRow(
                            session = session,
                            onOpen = { onOpenScan(session.id) },
                            onFavourite = { viewModel.toggleFavourite(session) },
                            onDelete = { toDelete = session },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete scan?") },
            text = { Text("\"${session.displayTitle}\" and its image, results and conversation will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(session); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun HistoryRow(
    session: ScanSession,
    onOpen: () -> Unit,
    onFavourite: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(session.mode.color().copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                val thumb = session.thumbnailPath
                if (thumb != null) {
                    AsyncImage(model = File(thumb), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(session.mode.icon(), contentDescription = null, tint = session.mode.color())
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(session.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = session.analysis?.summary?.takeIf { it.isNotBlank() } ?: session.notes.ifBlank { session.mode.tagline }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${session.mode.title} · ${TextFormat.dateTime(session.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = session.mode.color(),
                )
            }
            if (onFavourite != null) {
                IconButton(onClick = onFavourite) {
                    Icon(
                        if (session.isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (session.isFavourite) "Remove from favourites" else "Add to favourites",
                        tint = if (session.isFavourite) androidx.compose.ui.graphics.Color(0xFFFF6F91) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, contentDescription = "Delete scan") }
            }
            trailing?.invoke()
        }
    }
}
