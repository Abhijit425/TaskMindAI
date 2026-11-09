@file:OptIn(ExperimentalMaterial3Api::class)

package com.abhijit.airealityscanner.features.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhijit.airealityscanner.ai.AiEngine
import com.abhijit.airealityscanner.ai.AiException
import com.abhijit.airealityscanner.core.ui.components.CloudConsentDialog
import com.abhijit.airealityscanner.core.ui.components.EmptyState
import com.abhijit.airealityscanner.core.util.ImageUtils
import com.abhijit.airealityscanner.data.local.ImageStorage
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.model.StoredImage
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import com.abhijit.airealityscanner.features.history.HistoryRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class CompareState(
    val selected: List<String> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
    val needsConsent: Boolean = false,
)

@HiltViewModel
class CompareViewModel @Inject constructor(
    private val repository: ScanRepository,
    private val settingsRepository: SettingsRepository,
    private val aiEngine: AiEngine,
    private val imageStorage: ImageStorage,
) : ViewModel() {
    val candidates: StateFlow<List<ScanSession>?> = repository.observeHistory("", false, null)
        .map { list -> list.filter { it.images.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _state = MutableStateFlow(CompareState())
    val state: StateFlow<CompareState> = _state.asStateFlow()
    private val _done = Channel<String>(Channel.BUFFERED)
    val done: Flow<String> = _done.receiveAsFlow()

    fun toggle(id: String) = _state.update { s ->
        when {
            id in s.selected -> s.copy(selected = s.selected - id)
            s.selected.size < 2 -> s.copy(selected = s.selected + id)
            else -> s.copy(selected = listOf(s.selected.last(), id))
        }
    }

    fun acceptConsent() {
        viewModelScope.launch {
            settingsRepository.update { it.copy(cloudConsentGiven = true) }
            _state.update { it.copy(needsConsent = false) }
            compare()
        }
    }

    fun declineConsent() = _state.update { it.copy(needsConsent = false) }

    fun compare() {
        val ids = _state.value.selected
        if (ids.size != 2) return
        viewModelScope.launch {
            _state.update { it.copy(running = true, error = null) }
            try {
                val a = repository.getSession(ids[0]) ?: error("Scan not found")
                val b = repository.getSession(ids[1]) ?: error("Scan not found")
                val (bmpA, bmpB) = withContext(Dispatchers.IO) {
                    val first = ImageUtils.decodeFile(a.images.first().path, 1280) ?: error("Image for the first scan is missing")
                    val second = ImageUtils.decodeFile(b.images.first().path, 1280) ?: error("Image for the second scan is missing")
                    first to second
                }
                val analysis = aiEngine.compare(bmpA, bmpB, a.displayTitle, b.displayTitle)
                val imgA = imageStorage.save(bmpA, source = "compare")
                val imgB = imageStorage.save(bmpB, source = "compare")
                val id = repository.createSession(
                    ScanMode.GENERAL,
                    "Compare: ${a.displayTitle} vs ${b.displayTitle}",
                    listOf<StoredImage>(imgA, imgB),
                    saved = true,
                )
                repository.saveResult(id, analysis)
                _done.send(id)
            } catch (e: AiException.ConsentRequired) {
                _state.update { it.copy(needsConsent = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Comparison failed") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }
}

@Composable
fun CompareScreen(
    onBack: () -> Unit,
    onOpenResult: (String) -> Unit,
    viewModel: CompareViewModel = hiltViewModel(),
) {
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.done.collect { onOpenResult(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare objects") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(
                    onClick = viewModel::compare,
                    enabled = state.selected.size == 2 && !state.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.running) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("  Comparing with ${settings.activeModelLabel}…")
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null)
                        Text("  Compare ${state.selected.size}/2 selected")
                    }
                }
            }
        },
    ) { padding ->
        val list = candidates
        when {
            list == null -> Unit
            list.size < 2 -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.CompareArrows,
                title = "Scan at least two objects",
                message = "Saved scans with images appear here. Select two to compare them.",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Text("Select two scans (A and B):", style = MaterialTheme.typography.bodyMedium) }
                items(list, key = { it.id }) { session ->
                    val index = state.selected.indexOf(session.id)
                    HistoryRow(
                        session = session,
                        onOpen = { viewModel.toggle(session.id) },
                        trailing = {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .background(
                                        if (index >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (index >= 0) Text(if (index == 0) "A" else "B", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        },
                    )
                }
            }
        }
    }

    if (state.needsConsent) {
        CloudConsentDialog(settings.provider, onAllow = viewModel::acceptConsent, onDecline = viewModel::declineConsent)
    }
}
