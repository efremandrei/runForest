package com.andre.speedtest

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ConsentDialog(actions: SpeedTestViewModel) {
    AlertDialog(
        onDismissRequest = actions::dismissConsent,
        title = { Text("M-Lab data consent") },
        text = {
            Text(
                "The optional full speed mode uses M-Lab NDT7. M-Lab test data can include " +
                    "your public IP address, test time, and measurement details and may be retained " +
                    "or published by M-Lab. Independent diagnosis does not use M-Lab. No paid " +
                    "services, app-owned cloud sync, GPS, Firebase, advertising, or paid analytics " +
                    "are used. Tests consume network data."
            )
        },
        confirmButton = { Button(onClick = actions::acceptConsent) { Text("I agree") } },
        dismissButton = { TextButton(onClick = actions::dismissConsent) { Text("Cancel") } }
    )
}

@Composable
internal fun AboutDialog(actions: SpeedTestViewModel) {
    val context = LocalContext.current
    val activity = context as? Activity
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = { actions.requestAbout(false) },
        title = { Text("About runForest") },
        text = {
            Column {
                Text("Developer: Andrei Efremushkin")
                Text(
                    "Email: andrei.efr@gmail.com",
                    modifier = Modifier.clickable {
                        uriHandler.openUri("mailto:andrei.efr@gmail.com")
                    }
                )
                Text(
                    "GitHub: https://github.com/efremandrei/runForest",
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/efremandrei/runForest")
                    }
                )
                Text("Version: ${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}")
                Text(
                    "\nNo paid runtime services are used. Independent diagnosis sends small DNS, " +
                        "TCP, and HTTPS requests to Cloudflare, Google, and IETF endpoints; those " +
                        "operators can observe normal request metadata such as public IP and time."
                )
            }
        },
        confirmButton = { Button(onClick = { actions.requestAbout(false) }) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = { activity?.let(AppUpdateChecker::checkNow) }) {
                Text("Check for updates")
            }
        }
    )
}

@Composable
internal fun DiagnosticsDialog(state: SpeedUiState, actions: SpeedTestViewModel) {
    val network = state.network
    val diag = state.diagnostic
    AlertDialog(
        onDismissRequest = { actions.requestDiagnostics(false) },
        title = { Text("Technical details") },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { Text("Device: ${network?.device ?: "Unknown"}") }
                item { Text("Android: ${network?.android ?: "Unknown"}") }
                item { Text("ABI: ${network?.abi ?: "Unknown"}") }
                item { Text("Network: ${networkSummary(network)}") }
                item { Text("Interface: ${network?.interfaceName?.ifBlank { "Unknown" } ?: "Unknown"}") }
                item { Text("DNS: ${network?.dnsServers?.joinToString()?.ifBlank { "Unknown" } ?: "Unknown"}") }
                item { Text("Private DNS: ${network?.privateDnsActive ?: false}") }
                item { Text("Estimated link: ${network?.estimatedDownstreamMbps ?: 0}/${network?.estimatedUpstreamMbps ?: 0} Mbps") }
                item { Text("Wi-Fi: RSSI ${network?.wifiSignalDbm ?: "Unknown"} dBm, RX/TX ${network?.wifiRxLinkMbps ?: "?"}/${network?.wifiTxLinkMbps ?: "?"} Mbps, ${network?.wifiFrequencyMhz ?: "?"} MHz") }
                item { Text("Stage: ${diag?.stage ?: state.stage}") }
                item { Text("Message: ${diag?.message ?: "No completed diagnostic event yet."}") }
                item { Text("Targets/server: ${diag?.serverMachine ?: state.server}") }
                item { Text("Bytes: down=${diag?.downloadBytes ?: 0} up=${diag?.uploadBytes ?: 0}") }
                item { Text("Elapsed: ${diag?.elapsedMillis ?: 0} ms") }
                state.evaluation?.let { evaluation ->
                    item { Text("Evidence confidence: ${evaluation.confidence}") }
                    item { Text("Cross-checks: ${evaluation.evidenceSummary}") }
                }
                if (!diag?.rawDetails.isNullOrBlank()) {
                    item { Text("Raw: ${diag?.rawDetails}", fontFamily = FontFamily.Monospace) }
                }
            }
        },
        confirmButton = { Button(onClick = { actions.requestDiagnostics(false) }) { Text("Close") } }
    )
}

@Composable
internal fun LiveLogsDialog(state: SpeedUiState, actions: SpeedTestViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.lastIndex)
    }
    AlertDialog(
        onDismissRequest = { actions.requestLiveLogs(false) },
        title = { Text("Live diagnostic log") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 520.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.logs) { entry ->
                    Text(
                        entry.formatted(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = entry.logColor()
                    )
                }
                if (state.logs.isEmpty()) item { Text("The live log is empty.") }
            }
        },
        confirmButton = { Button(onClick = { actions.requestLiveLogs(false) }) { Text("Close") } },
        dismissButton = {
            Row {
                TextButton(onClick = actions::exportLogs, enabled = state.logs.isNotEmpty()) {
                    Text("Export")
                }
                TextButton(onClick = actions::clearLogs, enabled = state.logs.isNotEmpty()) {
                    Text("Clear")
                }
            }
        }
    )
}

private fun networkSummary(network: NetworkSnapshot?): String = network?.let {
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

internal fun LiveLogEntry.formatted(): String {
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
    return "$time ${level.name.padEnd(5)} [$source] $message"
}

@Composable
private fun LiveLogEntry.logColor(): Color = when (level) {
    LogLevel.ERROR -> MaterialTheme.colorScheme.error
    LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
    LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
}
