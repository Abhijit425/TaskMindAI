@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.abhijit.airealityscanner.features.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.abhijit.airealityscanner.ai.AiEngine
import com.abhijit.airealityscanner.core.ui.components.BulletList
import com.abhijit.airealityscanner.core.ui.components.color
import com.abhijit.airealityscanner.core.ui.components.icon
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DiscoverTopic(val title: String, val description: String, val mode: ScanMode, val prompt: String)

val DiscoverTopics = listOf(
    DiscoverTopic("Scan something interesting", "Point at anything and let AI explain it.", ScanMode.GENERAL, "everyday objects and how they are made"),
    DiscoverTopic("Learn how machines work", "Motors, appliances, gadgets and their parts.", ScanMode.MACHINE, "how common machines and electronics work"),
    DiscoverTopic("Discover plants around you", "Identify plants and learn how to care for them.", ScanMode.PLANT, "plants and botany"),
    DiscoverTopic("Explore architecture", "Buildings, monuments and their history.", ScanMode.LANDMARK, "architecture and famous buildings of India and the world"),
    DiscoverTopic("Everyday objects", "Materials, design and hidden engineering.", ScanMode.PRODUCT, "the hidden engineering in everyday products"),
    DiscoverTopic("Animals & nature", "Species, habitats and behaviour.", ScanMode.ANIMAL, "animal behaviour"),
)

val FactTopics = listOf(
    "Everyday science", "Space", "The human body", "Plants", "Machines", "Architecture", "Animals", "Light & colour",
)

data class DiscoverState(
    val topic: String = FactTopics.first(),
    val facts: List<String> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val cached: Boolean = false,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val aiEngine: AiEngine,
    private val repository: ScanRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    init {
        load(FactTopics.first(), forceRefresh = false)
    }

    fun startScan(mode: ScanMode, onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(lastMode = mode) }
            onDone()
        }
    }

    fun load(topic: String, forceRefresh: Boolean) {
        _state.update { it.copy(topic = topic, loading = true, error = null) }
        viewModelScope.launch {
            val language = settingsRepository.settings.first().language
            val key = "facts:${language.code}:$topic:${LocalDate.now()}"
            try {
                val cached = if (forceRefresh) null else repository.getPreference(key)
                val facts = if (cached != null) {
                    cached.split("\n").filter { it.isNotBlank() }
                } else {
                    aiEngine.facts(topic).also { repository.setPreference(key, it.joinToString("\n")) }
                }
                _state.update { if (it.topic == topic) it.copy(facts = facts, loading = false, cached = cached != null) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.topic == topic) it.copy(loading = false, facts = emptyList(), error = e.message) else it }
            }
        }
    }
}

@Composable
fun DiscoverScreen(
    onStartScan: () -> Unit,
    onCompare: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DiscoverViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Discover") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Learn something new about the world around you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DiscoverTopics.forEach { topic ->
                item(key = topic.title) {
                    TopicCard(topic) { viewModel.startScan(topic.mode, onStartScan) }
                }
            }
            item(key = "compare") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onCompare),
                ) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Compare two scanned objects", style = MaterialTheme.typography.titleMedium)
                            Text("Pick two saved scans and let AI explain the differences.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item(key = "facts-header") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Interesting facts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.load(state.topic, forceRefresh = true) }, enabled = !state.loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Generate new facts")
                    }
                }
            }
            item(key = "fact-topics") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FactTopics.forEach { t ->
                        FilterChip(selected = state.topic == t, onClick = { viewModel.load(t, forceRefresh = false) }, label = { Text(t) })
                    }
                }
            }
            item(key = "facts") {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Generating facts about ${state.topic}…")
                            }
                            state.error != null -> {
                                Text(state.error ?: "", style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = onOpenSettings) { Text("Open Settings") }
                            }
                            else -> BulletList(state.facts)
                        }
                        Text(
                            "AI-generated" + (if (state.cached) " (saved earlier today)" else "") +
                                ". Facts may contain mistakes — check important ones with a reliable source.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun TopicCard(topic: DiscoverTopic, onClick: () -> Unit) {
    val accent = topic.mode.color()
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.32f), accent.copy(alpha = 0.06f))))
            .clickable(onClick = onClick)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(topic.mode.icon(), contentDescription = null, tint = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(topic.title, style = MaterialTheme.typography.titleMedium)
                Text(topic.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onClick) { Text("Scan") }
        }
    }
}

@Suppress("unused")
private val Transparent = Color.Transparent
