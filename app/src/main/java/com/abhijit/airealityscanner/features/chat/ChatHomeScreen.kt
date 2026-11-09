@file:OptIn(ExperimentalMaterial3Api::class)

package com.abhijit.airealityscanner.features.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhijit.airealityscanner.core.ui.components.EmptyState
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.features.history.HistoryRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatHomeViewModel @Inject constructor(
    private val repository: ScanRepository,
) : ViewModel() {
    val conversations: StateFlow<List<ScanSession>?> =
        repository.observeHistory("", favouritesOnly = false, categoryId = null)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _open = Channel<String>(Channel.BUFFERED)
    val open: Flow<String> = _open.receiveAsFlow()

    /** Starts a text-only conversation (no image is involved, so nothing is uploaded but your text). */
    fun newTextChat() {
        viewModelScope.launch {
            val id = repository.createSession(ScanMode.GENERAL, "AI conversation", emptyList(), saved = true)
            _open.send(id)
        }
    }
}

@Composable
fun ChatHomeScreen(
    onOpenConversation: (String) -> Unit,
    viewModel: ChatHomeViewModel = hiltViewModel(),
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.open.collect { onOpenConversation(it) } }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("AI Chat") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::newTextChat,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New chat") },
            )
        },
    ) { padding ->
        val list = conversations
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.Chat,
                title = "No conversations yet",
                message = "Scan something and ask follow-up questions, or start a text chat. Every scan keeps its own conversation with the original image.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Continue a conversation about any saved scan. Questions are answered with the scan's original image as context.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                items(list, key = { it.id }) { session ->
                    HistoryRow(session = session, onOpen = { onOpenConversation(session.id) })
                }
            }
        }
    }
}
