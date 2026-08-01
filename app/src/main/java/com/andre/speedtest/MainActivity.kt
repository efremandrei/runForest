package com.andre.speedtest

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: SpeedTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdateChecker.checkDaily(this)
        setContent {
            val state by model.uiState.collectAsState()
            RunForestTheme(dark = state.darkTheme) {
                RunForestApp(state = state, actions = model)
            }
        }
    }
}

data class SpeedUiState(
    val darkTheme: Boolean = true,
    val consentAccepted: Boolean = false,
    val running: Boolean = false,
    val mode: EvaluationMode = EvaluationMode.INDEPENDENT,
    val stage: String = "Ready to evaluate",
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val latencyMillis: Long = 0,
    val loadedLatencyMillis: Long = 0,
    val jitterMillis: Long = 0,
    val throughputMeasured: Boolean = false,
    val latencyMeasured: Boolean = false,
    val server: String = "Multiple independent targets",
    val network: NetworkSnapshot? = null,
    val history: List<SpeedResultEntity> = emptyList(),
    val evaluation: ConnectionEvaluation? = null,
    val diagnostic: TestDiagnostic? = null,
    val logs: List<LiveLogEntry> = emptyList(),
    val showConsent: Boolean = false,
    val showAbout: Boolean = false,
    val showDiagnostics: Boolean = false,
    val showLiveLogs: Boolean = false,
    val exportMessage: String = ""
)

class SpeedTestViewModel(app: Application) : AndroidViewModel(app) {
    private val db = SpeedDatabase.get(app)
    private val settings = SettingsStore(app)
    private val _uiState = MutableStateFlow(SpeedUiState(network = NetworkInspector.snapshot(app)))
    val uiState: StateFlow<SpeedUiState> = _uiState.asStateFlow()
    private var testJob: Job? = null
    private var activeEngine: SpeedTestEngine? = null

    init {
        appendLog(LogLevel.INFO, "app", "runForest ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} started.")
        viewModelScope.launch {
            settings.darkThemeFlow.collect { dark -> _uiState.update { it.copy(darkTheme = dark) } }
        }
        viewModelScope.launch {
            settings.consentAcceptedFlow.collect { accepted -> _uiState.update { it.copy(consentAccepted = accepted) } }
        }
        viewModelScope.launch {
            db.results().observeAll().collect { results -> _uiState.update { it.copy(history = results) } }
        }
    }

    fun setDarkTheme(enabled: Boolean) = viewModelScope.launch { settings.setDarkTheme(enabled) }
    fun requestAbout(show: Boolean) = _uiState.update { it.copy(showAbout = show) }
    fun requestDiagnostics(show: Boolean) = _uiState.update { it.copy(showDiagnostics = show) }
    fun requestLiveLogs(show: Boolean) = _uiState.update { it.copy(showLiveLogs = show) }
    fun setMode(mode: EvaluationMode) {
        if (_uiState.value.running) return
        _uiState.update {
            it.copy(
                mode = mode,
                stage = if (mode == EvaluationMode.INDEPENDENT) {
                    "Ready for independent diagnosis"
                } else {
                    "Ready for full M-Lab speed test"
                },
                server = if (mode == EvaluationMode.INDEPENDENT) {
                    "Multiple independent targets"
                } else {
                    "Not selected"
                },
                throughputMeasured = false,
                latencyMeasured = false
            )
        }
    }

    fun acceptConsent() {
        viewModelScope.launch {
            settings.setConsentAccepted(true)
            _uiState.update { it.copy(showConsent = false) }
            launchSelectedTest()
        }
    }

    fun dismissConsent() = _uiState.update { it.copy(showConsent = false) }

    fun startTest() {
        if (_uiState.value.running) return
        if (_uiState.value.mode == EvaluationMode.FULL_SPEED && !_uiState.value.consentAccepted) {
            _uiState.update { it.copy(showConsent = true) }
            return
        }
        launchSelectedTest()
    }

    private fun launchSelectedTest() {
        if (_uiState.value.running) return
        val mode = _uiState.value.mode
        val network = NetworkInspector.snapshot(getApplication())
        val engine = when (mode) {
            EvaluationMode.INDEPENDENT -> IndependentDiagnosticEngine(getApplication())
            EvaluationMode.FULL_SPEED -> MLabNdt7Engine(getApplication())
        }
        activeEngine = engine
        _uiState.update {
            it.copy(
                running = true,
                stage = if (mode == EvaluationMode.INDEPENDENT) {
                    "Starting independent diagnosis"
                } else {
                    "Starting full speed test"
                },
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                latencyMillis = 0,
                loadedLatencyMillis = 0,
                jitterMillis = 0,
                throughputMeasured = false,
                latencyMeasured = false,
                server = if (mode == EvaluationMode.INDEPENDENT) {
                    "Cloudflare, Google, IETF"
                } else {
                    "Not selected"
                },
                network = network,
                evaluation = null,
                diagnostic = null,
                exportMessage = ""
            )
        }
        appendLog(LogLevel.INFO, "test", "${mode.label} started.")
        testJob = viewModelScope.launch {
            engine.startTest().collect { event ->
                when (event) {
                    SpeedTestEvent.LocatingServer -> _uiState.update { it.copy(stage = "Locating M-Lab server") }
                    is SpeedTestEvent.Stage -> _uiState.update { it.copy(stage = event.name) }
                    is SpeedTestEvent.ServerSelected -> _uiState.update {
                        it.copy(server = "${event.server.city}, ${event.server.country} (${event.server.machine})")
                    }
                    is SpeedTestEvent.DownloadProgress -> _uiState.update {
                        it.copy(stage = "Download and responsiveness under load", downloadMbps = event.mbps)
                    }
                    is SpeedTestEvent.UploadProgress -> _uiState.update {
                        it.copy(stage = "Upload and responsiveness under load", uploadMbps = event.mbps)
                    }
                    is SpeedTestEvent.Log -> appendLog(event.entry)
                    is SpeedTestEvent.Completed -> saveCompleted(event)
                    is SpeedTestEvent.DiagnosticCompleted -> saveDiagnostic(event)
                    is SpeedTestEvent.Failed -> {
                        _uiState.update {
                            it.copy(
                                running = false,
                                stage = "Evaluation failed",
                                evaluation = event.evaluation,
                                diagnostic = event.diagnostic,
                                throughputMeasured = false,
                                latencyMeasured = false
                            )
                        }
                        appendLog(LogLevel.ERROR, "test", event.diagnostic.message)
                    }
                    SpeedTestEvent.Cancelled -> _uiState.update { it.copy(running = false, stage = "Cancelled") }
                }
            }
        }
    }

    fun cancelTest() {
        activeEngine?.cancel()
        testJob?.cancel()
        appendLog(LogLevel.WARN, "test", "Evaluation cancelled by user.")
        _uiState.update { it.copy(running = false, stage = "Cancelled") }
    }

    fun deleteResult(id: String) = viewModelScope.launch { db.results().delete(id) }
    fun clearHistory() = viewModelScope.launch { db.results().clear() }
    fun clearLogs() = _uiState.update { it.copy(logs = emptyList(), exportMessage = "") }

    fun exportHistory() {
        viewModelScope.launch {
            val file = File(getApplication<Application>().getExternalFilesDir(null), "runForest-history.json")
            val array = JSONArray()
            _uiState.value.history.forEach { result ->
                array.put(JSONObject().apply {
                    put("timestampMillis", result.timestampMillis)
                    put("downloadMbps", result.downloadMbps)
                    put("uploadMbps", result.uploadMbps)
                    put("idleLatencyMillis", result.latencyMillis)
                    put("jitterMillis", result.jitterMillis)
                    put("server", result.serverMachine)
                    put("networkType", result.networkType)
                    put("verdict", result.qualityLabel)
                    put("diagnostic", runCatching { JSONObject(result.diagnosticJson) }.getOrElse { result.diagnosticJson })
                })
            }
            file.writeText(array.toString(2))
            appendLog(LogLevel.INFO, "export", "History exported to ${file.absolutePath}.")
            _uiState.update { it.copy(exportMessage = "History: ${file.absolutePath}") }
            shareFile(file, "application/json", "Share runForest history")
        }
    }

    fun exportLogs() {
        viewModelScope.launch {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(getApplication<Application>().getExternalFilesDir(null), "runForest-live-log-$stamp.txt")
            val header = buildString {
                appendLine("runForest ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE}")
                appendLine("Exported: ${DateFormat.getDateTimeInstance().format(Date())}")
                appendLine("Device: ${_uiState.value.network?.device ?: "Unknown"}")
                appendLine()
            }
            file.writeText(header + _uiState.value.logs.joinToString("\n") { it.formatted() })
            _uiState.update { it.copy(exportMessage = "Log: ${file.absolutePath}") }
            shareFile(file, "text/plain", "Share runForest log")
        }
    }

    private suspend fun saveCompleted(event: SpeedTestEvent.Completed) {
        val network = NetworkInspector.snapshot(getApplication())
        val diagnosticJson = JSONObject().apply {
            put("stage", event.diagnostic.stage)
            put("message", event.diagnostic.message)
            put("serverMachine", event.diagnostic.serverMachine)
            put("downloadBytes", event.diagnostic.downloadBytes)
            put("uploadBytes", event.diagnostic.uploadBytes)
            put("elapsedMillis", event.diagnostic.elapsedMillis)
            put("loadedLatencyMillis", event.loadedLatencyMillis)
            put("probeFailures", event.probeFailures)
            put("probeAttempts", event.probeAttempts)
            put("score", event.evaluation.score)
            put("summary", event.evaluation.summary)
            put("evidenceConfidence", event.evaluation.confidence)
            put("evidenceSummary", event.evaluation.evidenceSummary)
            put("findings", JSONArray().apply {
                event.evaluation.findings.forEach { finding ->
                    put(JSONObject().apply {
                        put("severity", finding.severity.name)
                        put("title", finding.title)
                        put("evidence", finding.evidence)
                        put("action", finding.action)
                    })
                }
            })
            put("rawDetails", runCatching { JSONObject(event.diagnostic.rawDetails) }.getOrElse { event.diagnostic.rawDetails })
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
                qualityLabel = event.evaluation.verdict,
                diagnosticJson = diagnosticJson
            )
        )
        _uiState.update {
            it.copy(
                running = false,
                stage = "Evaluation complete",
                downloadMbps = event.download.megabitsPerSecond,
                uploadMbps = event.upload.megabitsPerSecond,
                latencyMillis = event.latencyMillis,
                loadedLatencyMillis = event.loadedLatencyMillis,
                jitterMillis = event.jitterMillis,
                throughputMeasured = true,
                latencyMeasured = true,
                server = "${event.server.city}, ${event.server.country} (${event.server.machine})",
                network = network,
                evaluation = event.evaluation,
                diagnostic = event.diagnostic
            )
        }
    }

    private fun saveDiagnostic(event: SpeedTestEvent.DiagnosticCompleted) {
        val network = NetworkInspector.snapshot(getApplication())
        _uiState.update {
            it.copy(
                running = false,
                stage = "Independent diagnosis complete",
                latencyMillis = event.latencyMillis,
                loadedLatencyMillis = 0,
                jitterMillis = event.jitterMillis,
                throughputMeasured = false,
                latencyMeasured = event.latencyMillis > 0,
                server = "Cloudflare, Google, IETF",
                network = network,
                evaluation = event.evaluation,
                diagnostic = event.diagnostic
            )
        }
        appendLog(
            LogLevel.INFO,
            "evaluation",
            "Independent diagnosis: ${event.evaluation.score}/100, ${event.evaluation.confidence} confidence."
        )
    }

    private fun appendLog(entry: LiveLogEntry) {
        _uiState.update { state -> state.copy(logs = (state.logs + entry).takeLast(600)) }
    }

    private fun appendLog(level: LogLevel, source: String, message: String) =
        appendLog(LiveLogEntry(level = level, source = source, message = message))

    private fun shareFile(file: File, mimeType: String, title: String) {
        val app = getApplication<Application>()
        val uri = FileProvider.getUriForFile(app, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun RunForestTheme(dark: Boolean, content: @Composable () -> Unit) {
    val darkScheme = darkColorScheme(
        primary = Color(0xFF4ADE80),
        secondary = Color(0xFF38BDF8),
        tertiary = Color(0xFFF4D35E),
        surface = Color(0xFF121714),
        background = Color(0xFF0B0F0D)
    )
    val lightScheme = lightColorScheme(
        primary = Color(0xFF137A3D),
        secondary = Color(0xFF0369A1),
        tertiary = Color(0xFFA65D00),
        surface = Color(0xFFF8FAF8),
        background = Color(0xFFFFFFFF)
    )
    MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, content = content)
}

@Composable
fun RunForestApp(state: SpeedUiState, actions: SpeedTestViewModel) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(state, actions)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { EvaluationCard(state, actions) }
                if (state.evaluation != null) item { FindingsSection(state.evaluation) }
                item { DiagnosticControls(state, actions) }
                item { Text("Evaluation history", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(state.history, key = { it.id }) { result -> HistoryCard(result, actions) }
                if (state.history.isEmpty()) item { Text("No completed evaluations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (state.showConsent) ConsentDialog(actions)
        if (state.showAbout) AboutDialog(actions)
        if (state.showDiagnostics) DiagnosticsDialog(state, actions)
        if (state.showLiveLogs) LiveLogsDialog(state, actions)
    }
}

@Composable
private fun Header(state: SpeedUiState, actions: SpeedTestViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("runForest", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(state.stage, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.requestAbout(true) }) { Text("About") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { actions.setDarkTheme(true) }, enabled = !state.darkTheme) { Text("Moon") }
            TextButton(onClick = { actions.setDarkTheme(false) }, enabled = state.darkTheme) { Text("Sun") }
        }
    }
}

@Composable
private fun EvaluationCard(state: SpeedUiState, actions: SpeedTestViewModel) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                EvaluationMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { actions.setMode(mode) },
                        enabled = !state.running,
                        shape = SegmentedButtonDefaults.itemShape(index, EvaluationMode.entries.size)
                    ) {
                        Text(if (mode == EvaluationMode.INDEPENDENT) "Independent" else "Full speed")
                    }
                }
            }
            state.evaluation?.let { evaluation ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Column {
                        Text("Connection evaluation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(evaluation.verdict, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("${evaluation.confidence} evidence confidence", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${evaluation.score}/100", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Text(evaluation.summary)
                HorizontalDivider()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Download", if (state.throughputMeasured) "${SpeedMath.formatMbps(state.downloadMbps)} Mbps" else "Not measured")
                Metric("Upload", if (state.throughputMeasured) "${SpeedMath.formatMbps(state.uploadMbps)} Mbps" else "Not measured")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Idle", if (state.latencyMeasured) "${state.latencyMillis} ms" else "Not measured")
                Metric("Under load", if (state.throughputMeasured) "${state.loadedLatencyMillis} ms" else "Not measured")
                Metric("Jitter", if (state.latencyMeasured) "${state.jitterMillis} ms" else "Not measured")
            }
            Text(
                if (state.mode == EvaluationMode.INDEPENDENT) "Targets: ${state.server}" else "Server: ${state.server}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(networkLine(state.network), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = actions::startTest, enabled = !state.running) {
                    Text(if (state.mode == EvaluationMode.INDEPENDENT) "Diagnose connection" else "Run full speed test")
                }
                OutlinedButton(onClick = actions::cancelTest, enabled = state.running) { Text("Cancel") }
                if (state.running) CircularProgressIndicator(modifier = Modifier.width(26.dp).height(26.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FindingsSection(evaluation: ConnectionEvaluation) {
    Text("What runForest found", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    if (evaluation.findings.isEmpty()) {
        Text("No major issue was detected in this snapshot.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        evaluation.findings.forEach { finding ->
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = finding.containerColor())) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(finding.title, fontWeight = FontWeight.Bold)
                    Text(finding.evidence)
                    Text(finding.action, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticControls(state: SpeedUiState, actions: SpeedTestViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { actions.requestLiveLogs(true) }) { Text("Live log (${state.logs.size})") }
            OutlinedButton(onClick = { actions.requestDiagnostics(true) }) { Text("Technical details") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::exportLogs, enabled = state.logs.isNotEmpty()) { Text("Export log") }
            OutlinedButton(onClick = actions::exportHistory, enabled = state.history.isNotEmpty()) { Text("Export history") }
        }
        TextButton(onClick = actions::clearHistory, enabled = state.history.isNotEmpty()) { Text("Clear history") }
        if (state.exportMessage.isNotBlank()) Text(state.exportMessage, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun HistoryCard(result: SpeedResultEntity, actions: SpeedTestViewModel) {
    Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(DateFormat.getDateTimeInstance().format(Date(result.timestampMillis)), fontWeight = FontWeight.SemiBold)
                Text("${result.qualityLabel}: ${SpeedMath.formatMbps(result.downloadMbps)} down / ${SpeedMath.formatMbps(result.uploadMbps)} up")
                Text("${result.latencyMillis} ms idle, ${result.jitterMillis} ms jitter | ${result.serverCity}, ${result.serverCountry}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.deleteResult(result.id) }) { Text("Delete") }
        }
    }
}

private fun networkLine(network: NetworkSnapshot?): String = network?.let {
    buildString {
        append(it.type)
        append(" | validated=${it.validated}")
        if (it.captivePortal) append(" | captive portal")
        if (it.metered) append(" | metered")
        if (it.roaming) append(" | roaming")
        if (it.vpn) append(" | VPN")
        it.wifiSignalDbm?.let { rssi -> append(" | $rssi dBm") }
    }
} ?: "Unknown network"

@Composable
private fun EvaluationFinding.containerColor(): Color = when (severity) {
    FindingSeverity.CRITICAL -> MaterialTheme.colorScheme.errorContainer
    FindingSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
    FindingSeverity.INFO -> MaterialTheme.colorScheme.secondaryContainer
}
