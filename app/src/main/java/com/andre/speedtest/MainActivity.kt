package com.andre.speedtest

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val model: SpeedTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by model.uiState.collectAsState()
            SpeedTestTheme(dark = state.darkTheme) {
                SpeedTestApp(state = state, actions = model)
            }
        }
    }
}

data class SpeedUiState(
    val darkTheme: Boolean = true,
    val consentAccepted: Boolean = false,
    val running: Boolean = false,
    val stage: String = "Ready",
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val latencyMillis: Long = 0,
    val jitterMillis: Long = 0,
    val server: String = "Not selected",
    val network: NetworkSnapshot? = null,
    val history: List<SpeedResultEntity> = emptyList(),
    val diagnostic: TestDiagnostic? = null,
    val showConsent: Boolean = false,
    val showAbout: Boolean = false,
    val showDiagnostics: Boolean = false,
    val exportMessage: String = ""
)

class SpeedTestViewModel(app: Application) : AndroidViewModel(app) {
    private val db = SpeedDatabase.get(app)
    private val settings = SettingsStore(app)
    private val engine = MLabNdt7Engine(app)
    private val _uiState = MutableStateFlow(SpeedUiState(network = NetworkInspector.snapshot(app)))
    val uiState: StateFlow<SpeedUiState> = _uiState.asStateFlow()
    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            settings.darkThemeFlow.collect { dark ->
                _uiState.value = _uiState.value.copy(darkTheme = dark)
            }
        }
        viewModelScope.launch {
            settings.consentAcceptedFlow.collect { accepted ->
                _uiState.value = _uiState.value.copy(consentAccepted = accepted)
            }
        }
        viewModelScope.launch {
            db.results().observeAll().collect { results ->
                _uiState.value = _uiState.value.copy(history = results)
            }
        }
    }

    fun setDarkTheme(enabled: Boolean) {
        viewModelScope.launch { settings.setDarkTheme(enabled) }
    }

    fun requestAbout(show: Boolean) {
        _uiState.value = _uiState.value.copy(showAbout = show)
    }

    fun requestDiagnostics(show: Boolean) {
        _uiState.value = _uiState.value.copy(showDiagnostics = show)
    }

    fun acceptConsent() {
        viewModelScope.launch {
            settings.setConsentAccepted(true)
            _uiState.value = _uiState.value.copy(showConsent = false)
            startTest()
        }
    }

    fun dismissConsent() {
        _uiState.value = _uiState.value.copy(showConsent = false)
    }

    fun startTest() {
        if (_uiState.value.running) return
        if (!_uiState.value.consentAccepted) {
            _uiState.value = _uiState.value.copy(showConsent = true)
            return
        }
        testJob = viewModelScope.launch {
            engine.startTest().collect { event ->
                when (event) {
                    SpeedTestEvent.LocatingServer -> _uiState.value = _uiState.value.copy(
                        running = true,
                        stage = "Locating M-Lab server",
                        downloadMbps = 0.0,
                        uploadMbps = 0.0,
                        network = NetworkInspector.snapshot(getApplication()),
                        exportMessage = ""
                    )
                    is SpeedTestEvent.ServerSelected -> _uiState.value = _uiState.value.copy(
                        stage = "Server selected",
                        server = "${event.server.city}, ${event.server.country} (${event.server.machine})"
                    )
                    is SpeedTestEvent.DownloadProgress -> _uiState.value = _uiState.value.copy(
                        stage = "Download test",
                        downloadMbps = event.mbps
                    )
                    is SpeedTestEvent.UploadProgress -> _uiState.value = _uiState.value.copy(
                        stage = "Upload test",
                        uploadMbps = event.mbps
                    )
                    is SpeedTestEvent.Completed -> saveCompleted(event)
                    is SpeedTestEvent.Failed -> _uiState.value = _uiState.value.copy(
                        running = false,
                        stage = "Failed",
                        diagnostic = event.diagnostic
                    )
                    SpeedTestEvent.Cancelled -> _uiState.value = _uiState.value.copy(running = false, stage = "Cancelled")
                }
            }
        }
    }

    fun cancelTest() {
        engine.cancel()
        testJob?.cancel()
        _uiState.value = _uiState.value.copy(running = false, stage = "Cancelled")
    }

    fun deleteResult(id: String) {
        viewModelScope.launch { db.results().delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { db.results().clear() }
    }

    fun exportHistory() {
        viewModelScope.launch {
            val file = File(getApplication<Application>().getExternalFilesDir(null), "speed-test-history.json")
            val array = JSONArray()
            _uiState.value.history.forEach { result ->
                array.put(JSONObject().apply {
                    put("timestampMillis", result.timestampMillis)
                    put("downloadMbps", result.downloadMbps)
                    put("uploadMbps", result.uploadMbps)
                    put("latencyMillis", result.latencyMillis)
                    put("jitterMillis", result.jitterMillis)
                    put("server", result.serverMachine)
                    put("networkType", result.networkType)
                    put("quality", result.qualityLabel)
                    put("syncStatus", result.syncStatus)
                })
            }
            file.writeText(array.toString(2))
            _uiState.value = _uiState.value.copy(exportMessage = "Exported to ${file.absolutePath}")
        }
    }

    private suspend fun saveCompleted(event: SpeedTestEvent.Completed) {
        val network = NetworkInspector.snapshot(getApplication())
        val quality = SpeedMath.quality(
            event.download.megabitsPerSecond,
            event.upload.megabitsPerSecond,
            event.latencyMillis
        )
        val diagnosticJson = JSONObject().apply {
            put("stage", event.diagnostic.stage)
            put("message", event.diagnostic.message)
            put("serverMachine", event.diagnostic.serverMachine)
            put("downloadBytes", event.diagnostic.downloadBytes)
            put("uploadBytes", event.diagnostic.uploadBytes)
            put("elapsedMillis", event.diagnostic.elapsedMillis)
            put("rawDetails", event.diagnostic.rawDetails)
        }.toString()
        db.results().insert(
            SpeedResultEntity(
                timestampMillis = System.currentTimeMillis(),
                downloadMbps = event.download.megabitsPerSecond,
                uploadMbps = event.upload.megabitsPerSecond,
                latencyMillis = event.latencyMillis,
                jitterMillis = event.jitterMillis,
                serverMachine = event.server.machine,
                serverCity = event.server.city,
                serverCountry = event.server.country,
                networkType = network.type,
                networkMetered = network.metered,
                networkRoaming = network.roaming,
                networkVpn = network.vpn,
                qualityLabel = quality,
                diagnosticJson = diagnosticJson
            )
        )
        _uiState.value = _uiState.value.copy(
            running = false,
            stage = "Complete",
            downloadMbps = event.download.megabitsPerSecond,
            uploadMbps = event.upload.megabitsPerSecond,
            latencyMillis = event.latencyMillis,
            jitterMillis = event.jitterMillis,
            server = "${event.server.city}, ${event.server.country} (${event.server.machine})",
            network = network,
            diagnostic = event.diagnostic
        )
    }
}

@Composable
fun SpeedTestTheme(dark: Boolean, content: @Composable () -> Unit) {
    val darkScheme = darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF38BDF8),
        secondary = androidx.compose.ui.graphics.Color(0xFF22C55E),
        tertiary = androidx.compose.ui.graphics.Color(0xFFF4D35E)
    )
    val lightScheme = lightColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFF0369A1),
        secondary = androidx.compose.ui.graphics.Color(0xFF15803D),
        tertiary = androidx.compose.ui.graphics.Color(0xFFB45309)
    )
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}

@Composable
fun SpeedTestApp(state: SpeedUiState, actions: SpeedTestViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Header(state, actions)
            Metrics(state, actions)
            DiagnosticsControls(state, actions)
            HistoryList(state, actions)
        }
        if (state.showConsent) ConsentDialog(actions)
        if (state.showAbout) AboutDialog(actions)
        if (state.showDiagnostics) DiagnosticsDialog(state, actions)
    }
}

@Composable
private fun Header(state: SpeedUiState, actions: SpeedTestViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Speed Test", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(state.stage, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { actions.setDarkTheme(true) }, enabled = !state.darkTheme) { Text("Moon") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = { actions.setDarkTheme(false) }, enabled = state.darkTheme) { Text("Sun") }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { actions.requestAbout(true) }) { Text("About") }
    }
}

@Composable
private fun Metrics(state: SpeedUiState, actions: SpeedTestViewModel) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Metric("Download", "${SpeedMath.formatMbps(state.downloadMbps)} Mbps")
                Metric("Upload", "${SpeedMath.formatMbps(state.uploadMbps)} Mbps")
                Metric("Latency", "${state.latencyMillis} ms")
            }
            Text("Server: ${state.server}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Network: ${state.network?.type ?: "Unknown"}  Metered: ${state.network?.metered ?: false}  VPN: ${state.network?.vpn ?: false}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = actions::startTest, enabled = !state.running) { Text("Start Test") }
                OutlinedButton(onClick = actions::cancelTest, enabled = state.running) { Text("Cancel") }
                if (state.running) CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiagnosticsControls(state: SpeedUiState, actions: SpeedTestViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.requestDiagnostics(true) }) { Text("Diagnostics") }
        OutlinedButton(onClick = actions::exportHistory, enabled = state.history.isNotEmpty()) { Text("Export History") }
        OutlinedButton(onClick = actions::clearHistory, enabled = state.history.isNotEmpty()) { Text("Clear History") }
    }
    if (state.exportMessage.isNotBlank()) {
        Text(state.exportMessage, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun HistoryList(state: SpeedUiState, actions: SpeedTestViewModel) {
    Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.history, key = { it.id }) { result ->
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(DateFormat.getDateTimeInstance().format(Date(result.timestampMillis)), fontWeight = FontWeight.SemiBold)
                        Text("${SpeedMath.formatMbps(result.downloadMbps)} down / ${SpeedMath.formatMbps(result.uploadMbps)} up  ${result.qualityLabel}")
                        Text("${result.serverCity}, ${result.serverCountry}  ${result.networkType}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { actions.deleteResult(result.id) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun ConsentDialog(actions: SpeedTestViewModel) {
    AlertDialog(
        onDismissRequest = actions::dismissConsent,
        title = { Text("M-Lab data consent") },
        text = {
            Text("This app runs real NDT7 speed tests against M-Lab public infrastructure. M-Lab test data can include your public IP address, test time, and network measurement details. No paid services, app-owned cloud sync, GPS, Firebase, or paid analytics are used in this version.")
        },
        confirmButton = { Button(onClick = actions::acceptConsent) { Text("I Agree") } },
        dismissButton = { TextButton(onClick = actions::dismissConsent) { Text("Cancel") } }
    )
}

@Composable
private fun AboutDialog(actions: SpeedTestViewModel) {
    AlertDialog(
        onDismissRequest = { actions.requestAbout(false) },
        title = { Text("About Speed Test") },
        text = {
            Text("Developer: Andrei Efremuahkin\nEmail: andrei.efr@gmail.com\nGitHub: pending repository URL\nVersion: ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE}")
        },
        confirmButton = { Button(onClick = { actions.requestAbout(false) }) { Text("Close") } }
    )
}

@Composable
private fun DiagnosticsDialog(state: SpeedUiState, actions: SpeedTestViewModel) {
    val network = state.network
    val diag = state.diagnostic
    AlertDialog(
        onDismissRequest = { actions.requestDiagnostics(false) },
        title = { Text("Technical Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Device: ${network?.device ?: "Unknown"}")
                Text("Android: ${network?.android ?: "Unknown"}")
                Text("ABI: ${network?.abi ?: "Unknown"}")
                Text("Network: ${network?.type ?: "Unknown"} validated=${network?.validated} metered=${network?.metered} roaming=${network?.roaming} vpn=${network?.vpn}")
                Text("Stage: ${diag?.stage ?: state.stage}")
                Text("Message: ${diag?.message ?: "No diagnostic event yet."}")
                Text("Server: ${diag?.serverMachine ?: state.server}")
                Text("Bytes: down=${diag?.downloadBytes ?: 0} up=${diag?.uploadBytes ?: 0}")
                Text("Elapsed: ${diag?.elapsedMillis ?: 0} ms")
            }
        },
        confirmButton = { Button(onClick = { actions.requestDiagnostics(false) }) { Text("Close") } }
    )
}

