package com.andre.speedtest

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max

internal object InvestigationAnalyzer {
    fun buildCrossChecks(measurement: ServerMeasurement): List<InvestigationEvidence> {
        val evidence = mutableListOf<InvestigationEvidence>()
        val tcpRtt = SpeedMath.median(measurement.idleSamples)
        val serverRtt = SpeedMath.median(
            listOfNotNull(
                measurement.download.serverRttMillis,
                measurement.upload.serverRttMillis
            ).filter { it > 0 }
        )
        if (tcpRtt > 0 && serverRtt > 0) {
            val difference = abs(tcpRtt - serverRtt)
            val agrees = difference <= 35 || difference.toDouble() / max(tcpRtt, serverRtt) <= 0.6
            evidence += InvestigationEvidence(
                "Latency cross-check",
                if (agrees) EvidenceStatus.PASS else EvidenceStatus.WARN,
                "TCP connect $tcpRtt ms vs server RTT $serverRtt ms",
                if (agrees) {
                    "Independent client and server latency signals broadly agree."
                } else {
                    "The methods differ materially; they measure different protocol layers, so the result is retained with lower confidence."
                }
            )
        }
        addThroughputCrossCheck(evidence, "Download", measurement.download)
        addThroughputCrossCheck(evidence, "Upload", measurement.upload)
        return evidence
    }

    fun buildReport(evidence: List<InvestigationEvidence>): InvestigationReport {
        val snapshot = evidence.toList()
        val passCount = snapshot.count { it.status == EvidenceStatus.PASS }
        val failCount = snapshot.count { it.status == EvidenceStatus.FAIL }
        val fallbackCount = snapshot.count { it.fallbackUsed && it.status == EvidenceStatus.PASS }
        val contradictionCount = snapshot.count {
            "cross-check" in it.method.lowercase() && it.status != EvidenceStatus.PASS
        }
        val completedMeasurement = snapshot.any {
            it.method == "NDT7 download" && it.status == EvidenceStatus.PASS
        } && snapshot.any {
            it.method == "NDT7 upload" && it.status == EvidenceStatus.PASS
        }
        val activeReachability = snapshot.any {
            it.method == "HTTPS reachability" && it.status == EvidenceStatus.PASS
        }
        val confidence = when {
            completedMeasurement && activeReachability && failCount == 0 &&
                contradictionCount == 0 && passCount >= 7 -> "High"
            completedMeasurement && activeReachability && contradictionCount <= 1 -> "Medium"
            else -> "Low"
        }
        val summary = "$passCount methods passed; $fallbackCount successful fallback(s); " +
            "$contradictionCount cross-check disagreement(s); $failCount terminal failure(s)."
        return InvestigationReport(confidence, summary, fallbackCount, contradictionCount, snapshot)
    }

    private fun addThroughputCrossCheck(
        evidence: MutableList<InvestigationEvidence>,
        phase: String,
        measurement: PhaseMeasurement
    ) {
        val client = measurement.clientMegabitsPerSecond ?: return
        val server = measurement.serverMegabitsPerSecond ?: return
        if (client <= 0 || server <= 0) return
        val relativeDifference = abs(client - server) / max(client, server)
        val agrees = relativeDifference <= 0.25
        evidence += InvestigationEvidence(
            "$phase throughput cross-check",
            if (agrees) EvidenceStatus.PASS else EvidenceStatus.WARN,
            "client ${SpeedMath.formatMbps(client)} vs server ${SpeedMath.formatMbps(server)} Mbps",
            if (agrees) {
                "Client byte timing and M-Lab AppInfo agree within 25%."
            } else {
                "Client and server accounting differ by ${(relativeDifference * 100).toInt()}%; the server-reported result is retained with lower confidence."
            }
        )
    }
}

internal class NetworkFallbacks(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun resolve(host: String): DnsResolution = withContext(Dispatchers.IO) {
        val evidence = mutableListOf<InvestigationEvidence>()
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork != null) {
            val networkResult = runCatching { activeNetwork.getAllByName(host).toList() }
            val addresses = networkResult.getOrNull().orEmpty()
            if (addresses.isNotEmpty()) {
                evidence += InvestigationEvidence(
                    "Active-network DNS",
                    EvidenceStatus.PASS,
                    "${addresses.size} address(es)",
                    "Resolution was bound to Android's active network."
                )
                return@withContext DnsResolution(addresses, evidence)
            }
            evidence += InvestigationEvidence(
                "Active-network DNS",
                EvidenceStatus.WARN,
                "failed",
                networkResult.exceptionOrNull()?.message ?: "No addresses returned."
            )
        }
        val systemResult = runCatching { InetAddress.getAllByName(host).toList() }
        val addresses = systemResult.getOrNull().orEmpty()
        evidence += if (addresses.isNotEmpty()) {
            InvestigationEvidence(
                "System DNS fallback",
                EvidenceStatus.PASS,
                "${addresses.size} address(es)",
                "Used system resolver after the active-network method was unavailable or failed.",
                fallbackUsed = true
            )
        } else {
            InvestigationEvidence(
                "System DNS fallback",
                EvidenceStatus.FAIL,
                "failed",
                systemResult.exceptionOrNull()?.message ?: "No addresses returned.",
                fallbackUsed = true
            )
        }
        DnsResolution(addresses, evidence)
    }

    suspend fun tcpConnect(addresses: List<InetAddress>, port: Int): ProbeResult =
        withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            addresses.forEachIndexed { index, address ->
                try {
                    val socket: Socket = connectivityManager.activeNetwork
                        ?.socketFactory
                        ?.createSocket()
                        ?: Socket()
                    socket.use {
                        val started = android.os.SystemClock.elapsedRealtime()
                        it.connect(InetSocketAddress(address, port), 2500)
                        return@withContext ProbeResult(
                            millis = android.os.SystemClock.elapsedRealtime() - started,
                            address = address,
                            family = if (address.hostAddress?.contains(':') == true) "IPv6" else "IPv4",
                            fallbackUsed = index > 0
                        )
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IOException(
                "All ${addresses.size} resolved address(es) failed TCP connection: ${lastError?.message}",
                lastError
            )
        }
}

internal fun InvestigationReport.toJson() = JSONArray().apply {
    evidence.forEach { item ->
        put(JSONObject().apply {
            put("method", item.method)
            put("status", item.status.name)
            put("value", item.value)
            put("detail", item.detail)
            put("fallbackUsed", item.fallbackUsed)
        })
    }
}

internal fun URI.effectivePort(): Int = when {
    port > 0 -> port
    scheme.equals("ws", true) -> 80
    else -> 443
}

internal data class DnsResolution(
    val addresses: List<InetAddress>,
    val evidence: List<InvestigationEvidence>
)

internal data class ProbeResult(
    val millis: Long,
    val address: InetAddress,
    val family: String,
    val fallbackUsed: Boolean
)

internal data class ServerEndpoint(
    val server: ServerInfo,
    val host: String,
    val port: Int,
    val addresses: List<InetAddress>,
    val precheckMillis: Long?
)

internal data class ServerMeasurement(
    val server: ServerInfo,
    val download: PhaseMeasurement,
    val upload: PhaseMeasurement,
    val idleLatencyMillis: Long,
    val loadedLatencyMillis: Long,
    val jitterMillis: Long,
    val probeFailures: Int,
    val probeAttempts: Int,
    val idleSamples: List<Long>,
    val loadedSamples: List<Long>,
    val evidence: List<InvestigationEvidence>
)

internal class PhaseTelemetry {
    @Volatile var appBytes: Long? = null
    @Volatile var appElapsedMillis: Long? = null
    @Volatile var rttMillis: Long? = null
    @Volatile var rttVariationMillis: Long? = null
    @Volatile var totalRetransmissions: Long? = null

    fun update(text: String) {
        runCatching {
            val root = JSONObject(text)
            root.optJSONObject("AppInfo")?.let { app ->
                if (app.has("NumBytes")) appBytes = app.optLong("NumBytes").coerceAtLeast(0)
                if (app.has("ElapsedTime")) {
                    appElapsedMillis = app.optLong("ElapsedTime").div(1000).coerceAtLeast(0)
                }
            }
            val tcp = root.optJSONObject("TCPInfo") ?: return
            if (tcp.has("RTT")) rttMillis = tcp.optLong("RTT").div(1000).coerceAtLeast(0)
            if (tcp.has("RTTVar")) {
                rttVariationMillis = tcp.optLong("RTTVar").div(1000).coerceAtLeast(0)
            }
            if (tcp.has("TotalRetr")) {
                totalRetransmissions = tcp.optLong("TotalRetrans").coerceAtLeast(0)
            }
        }
    }
}

internal fun NetworkSnapshot.summary(): String = buildString {
    append("type=$type, validated=$validated, captive=$captivePortal, metered=$metered, roaming=$roaming, vpn=$vpn")
    append(", estimated=${estimatedDownstreamMbps}/${estimatedUpstreamMbps} Mbps")
    wifiSignalDbm?.let { append(", wifiRssi=$it dBm") }
    if (interfaceName.isNotBlank()) append(", interface=$interfaceName")
    append(", dnsServers=${dnsServers.size}, privateDns=$privateDnsActive")
}
