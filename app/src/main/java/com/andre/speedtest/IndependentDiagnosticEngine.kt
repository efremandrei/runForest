package com.andre.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

internal data class DiagnosticTarget(
    val name: String,
    val host: String,
    val httpsUrl: String
)

internal data class ProbeObservation(
    val method: String,
    val target: String,
    val success: Boolean,
    val latencyMillis: Long?,
    val value: String,
    val detail: String,
    val fallbackUsed: Boolean = false,
    val sampleIndex: Int = 1,
    val sampleCount: Int = 1
) {
    fun toEvidence() = InvestigationEvidence(
        method = "$method $target",
        status = if (success) EvidenceStatus.PASS else EvidenceStatus.FAIL,
        value = value,
        detail = detail,
        fallbackUsed = fallbackUsed
    )
}

internal data class TargetLatencyProfile(
    val target: String,
    val tcpSamples: List<Long>,
    val httpsSamples: List<Long>,
    val temporalJitterMillis: Long,
    val medianTcpMillis: Long,
    val p95TcpMillis: Long,
    val medianHttpsMillis: Long,
    val failures: Int,
    val attempts: Int
)

internal interface IndependentProbeClient {
    suspend fun dns(target: DiagnosticTarget): ProbeObservation
    suspend fun tcp(target: DiagnosticTarget): ProbeObservation
    suspend fun https(target: DiagnosticTarget): ProbeObservation
    fun cancel()
}

internal data class IndependentDiagnosis(
    val latencyMillis: Long,
    val jitterMillis: Long,
    val probeFailures: Int,
    val probeAttempts: Int,
    val report: InvestigationReport,
    val observations: List<ProbeObservation>,
    val targetProfiles: List<TargetLatencyProfile>,
    val destinationSpreadMillis: Long
)

internal class IndependentInvestigator(
    private val client: IndependentProbeClient,
    private val targets: List<DiagnosticTarget> = defaultDiagnosticTargets
) {
    private val tcpSamplesPerTarget = 5
    private val httpsSamplesPerTarget = 3

    suspend fun run(
        network: NetworkSnapshot,
        onObservation: suspend (ProbeObservation) -> Unit = {}
    ): IndependentDiagnosis {
        val observations = mutableListOf<ProbeObservation>()
        for (target in targets) {
            observations += runProbe("DNS", target, 1, 1, onObservation) { client.dns(target) }
            repeat(tcpSamplesPerTarget) { index ->
                observations += runProbe("TCP", target, index + 1, tcpSamplesPerTarget, onObservation) {
                    client.tcp(target).copy(sampleIndex = index + 1, sampleCount = tcpSamplesPerTarget)
                }
            }
            repeat(httpsSamplesPerTarget) { index ->
                observations += runProbe("HTTPS", target, index + 1, httpsSamplesPerTarget, onObservation) {
                    client.https(target).copy(sampleIndex = index + 1, sampleCount = httpsSamplesPerTarget)
                }
            }
        }

        val profiles = targets.map { target -> buildProfile(target, observations) }
        val dnsPass = targets.count { target ->
            observations.any { it.target == target.name && it.method == "DNS" && it.success }
        }
        val tcpPass = profiles.count { it.tcpSamples.isNotEmpty() }
        val httpsPass = profiles.count { it.httpsSamples.isNotEmpty() }
        val activeQuorum = httpsPass >= 2
        val crossCheckAgrees = network.validated == activeQuorum
        val evidence = observations.map { it.toEvidence() }.toMutableList()
        profiles.forEach { profile ->
            evidence += InvestigationEvidence(
                method = "Latency profile ${profile.target}",
                status = if (profile.tcpSamples.isNotEmpty()) EvidenceStatus.PASS else EvidenceStatus.FAIL,
                value = "TCP median ${profile.medianTcpMillis} ms, p95 ${profile.p95TcpMillis} ms, jitter ${profile.temporalJitterMillis} ms",
                detail = "${profile.tcpSamples.size} TCP and ${profile.httpsSamples.size} HTTPS latency sample(s); ${profile.failures}/${profile.attempts} failed."
            )
        }
        val destinationSpread = SpeedMath.spread(profiles.map { it.medianTcpMillis }.filter { it > 0 })
        if (destinationSpread > 0) {
            evidence += InvestigationEvidence(
                method = "Destination spread",
                status = EvidenceStatus.PASS,
                value = "$destinationSpread ms between target medians",
                detail = "Cross-target latency spread is reported separately from jitter because it reflects different network paths and server locations."
            )
        }
        evidence += InvestigationEvidence(
            method = "Availability cross-check",
            status = if (crossCheckAgrees) EvidenceStatus.PASS else EvidenceStatus.WARN,
            value = "Android validated=${network.validated}; HTTPS quorum=$activeQuorum",
            detail = if (crossCheckAgrees) {
                "Android validation and the independent HTTPS quorum agree."
            } else {
                "Platform and active probes disagree; the result is retained with reduced confidence."
            }
        )

        val failed = observations.count { !it.success }
        val fallbackCount = observations.count { it.success && it.fallbackUsed }
        val contradictionCount = if (crossCheckAgrees) 0 else 1
        val confidence = when {
            dnsPass >= 2 && tcpPass >= 2 && httpsPass >= 2 && crossCheckAgrees -> "High"
            httpsPass >= 1 && (tcpPass >= 1 || dnsPass >= 1) -> "Medium"
            else -> "Low"
        }
        val summary = "$dnsPass/${targets.size} DNS targets, $tcpPass/${targets.size} TCP target profiles, and " +
            "$httpsPass/${targets.size} HTTPS target profiles passed; $contradictionCount cross-check disagreement(s)."
        val report = InvestigationReport(
            confidence = confidence,
            summary = summary,
            fallbackCount = fallbackCount,
            contradictionCount = contradictionCount,
            evidence = evidence
        )
        val tcpTargetMedians = profiles.map { it.medianTcpMillis }.filter { it > 0 }
        val temporalJitters = profiles.map { it.temporalJitterMillis }.filter { it > 0 }
        return IndependentDiagnosis(
            latencyMillis = SpeedMath.median(tcpTargetMedians),
            jitterMillis = SpeedMath.median(temporalJitters),
            probeFailures = failed,
            probeAttempts = observations.size,
            report = report,
            observations = observations,
            targetProfiles = profiles,
            destinationSpreadMillis = destinationSpread
        )
    }

    fun cancel() = client.cancel()

    private suspend fun runProbe(
        method: String,
        target: DiagnosticTarget,
        sampleIndex: Int,
        sampleCount: Int,
        onObservation: suspend (ProbeObservation) -> Unit,
        probe: suspend () -> ProbeObservation
    ): ProbeObservation {
        val observation = try {
            probe()
        } catch (error: Throwable) {
            ProbeObservation(
                method = method,
                target = target.name,
                success = false,
                latencyMillis = null,
                value = "failed",
                detail = error.message ?: error.javaClass.simpleName,
                sampleIndex = sampleIndex,
                sampleCount = sampleCount
            )
        }
        val normalized = observation.copy(sampleIndex = sampleIndex, sampleCount = sampleCount)
        onObservation(normalized)
        return normalized
    }

    private fun buildProfile(
        target: DiagnosticTarget,
        observations: List<ProbeObservation>
    ): TargetLatencyProfile {
        val targetObservations = observations.filter { it.target == target.name }
        val tcpSamples = targetObservations.mapNotNull {
            it.latencyMillis?.takeIf { _ -> it.method == "TCP" && it.success }
        }
        val httpsSamples = targetObservations.mapNotNull {
            it.latencyMillis?.takeIf { _ -> it.method == "HTTPS" && it.success }
        }
        return TargetLatencyProfile(
            target = target.name,
            tcpSamples = tcpSamples,
            httpsSamples = httpsSamples,
            temporalJitterMillis = SpeedMath.jitter(tcpSamples),
            medianTcpMillis = SpeedMath.median(tcpSamples),
            p95TcpMillis = SpeedMath.percentile95(tcpSamples),
            medianHttpsMillis = SpeedMath.median(httpsSamples),
            failures = targetObservations.count { !it.success },
            attempts = targetObservations.size
        )
    }
}

private val defaultDiagnosticTargets = listOf(
    DiagnosticTarget("Cloudflare", "www.cloudflare.com", "https://www.cloudflare.com/cdn-cgi/trace"),
    DiagnosticTarget("Google", "www.google.com", "https://www.google.com/generate_204"),
    DiagnosticTarget("IETF", "www.ietf.org", "https://www.ietf.org/")
)

internal class AndroidIndependentProbeClient(
    context: Context,
    private val networkFallbacks: NetworkFallbacks = NetworkFallbacks(context),
    httpClient: OkHttpClient? = null
) : IndependentProbeClient {
    private val activeCalls = Collections.synchronizedList(mutableListOf<Call>())
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val httpClient: OkHttpClient = httpClient ?: buildBoundHttpClient()

    override suspend fun dns(target: DiagnosticTarget): ProbeObservation {
        val started = SystemClock.elapsedRealtime()
        val resolution = networkFallbacks.resolve(target.host)
        val elapsed = SystemClock.elapsedRealtime() - started
        if (resolution.addresses.isEmpty()) {
            throw IOException(resolution.evidence.lastOrNull()?.detail ?: "No DNS addresses returned.")
        }
        val fallback = resolution.evidence.any { it.fallbackUsed && it.status == EvidenceStatus.PASS }
        val families = resolution.addresses.map {
            if (it.hostAddress?.contains(':') == true) "IPv6" else "IPv4"
        }.distinct().joinToString("+")
        return ProbeObservation(
            method = "DNS",
            target = target.name,
            success = true,
            latencyMillis = elapsed,
            value = "${resolution.addresses.size} address(es) in $elapsed ms",
            detail = "Resolved ${target.host} using $families.",
            fallbackUsed = fallback
        )
    }

    override suspend fun tcp(target: DiagnosticTarget): ProbeObservation {
        val resolution = networkFallbacks.resolve(target.host)
        if (resolution.addresses.isEmpty()) throw IOException("TCP precheck could not resolve ${target.host}.")
        val result = networkFallbacks.tcpConnect(resolution.addresses, 443)
        return ProbeObservation(
            method = "TCP",
            target = target.name,
            success = true,
            latencyMillis = result.millis,
            value = "${result.millis} ms via ${result.family}",
            detail = "Connected to ${result.address.hostAddress}:443.",
            fallbackUsed = result.fallbackUsed
        )
    }

    override suspend fun https(target: DiagnosticTarget): ProbeObservation = withContext(Dispatchers.IO) {
        val timing = HttpTiming()
        val callClient = httpClient.newBuilder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .eventListener(HttpTimingEventListener(timing))
            .build()
        val request = Request.Builder()
            .url(target.httpsUrl)
            .header("User-Agent", "runForest/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()
        val call = callClient.newCall(request)
        activeCalls += call
        val started = SystemClock.elapsedRealtime()
        try {
            call.execute().use { response ->
                val elapsed = SystemClock.elapsedRealtime() - started
                if (response.code !in 100..599) throw IOException("Unexpected HTTP ${response.code}.")
                val phaseDetails = timing.describe(response)
                ProbeObservation(
                    method = "HTTPS",
                    target = target.name,
                    success = true,
                    latencyMillis = elapsed,
                    value = "HTTP ${response.code} in $elapsed ms",
                    detail = "TLS and HTTP completed for ${target.httpsUrl}. $phaseDetails"
                )
            }
        } finally {
            activeCalls -= call
        }
    }

    override fun cancel() {
        activeCalls.toList().forEach(Call::cancel)
    }

    private fun buildBoundHttpClient(): OkHttpClient {
        val activeNetwork = connectivityManager.activeNetwork
        return OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .apply {
                activeNetwork?.socketFactory?.let { socketFactory(it) }
                dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        return activeNetwork?.let { network ->
                            runCatching { network.getAllByName(hostname).toList() }.getOrNull()
                        }?.takeIf { it.isNotEmpty() } ?: try {
                            Dns.SYSTEM.lookup(hostname)
                        } catch (error: UnknownHostException) {
                            throw error
                        }
                    }
                })
            }
            .build()
    }
}

private class HttpTiming {
    private var callStartMillis = 0L
    private var dnsStartMillis = 0L
    private var connectStartMillis = 0L
    private var tlsStartMillis = 0L
    private var requestHeadersEndMillis = 0L
    private var responseHeadersStartMillis = 0L
    var dnsMillis: Long? = null
    var tcpMillis: Long? = null
    var tlsMillis: Long? = null
    var ttfbMillis: Long? = null
    var remoteAddress: String = ""
    var protocol: String = ""

    fun callStart() {
        callStartMillis = SystemClock.elapsedRealtime()
    }

    fun dnsStart() {
        dnsStartMillis = SystemClock.elapsedRealtime()
    }

    fun dnsEnd(addresses: List<InetAddress>) {
        dnsMillis = elapsedSince(dnsStartMillis)
        if (remoteAddress.isBlank()) remoteAddress = addresses.firstOrNull()?.hostAddress.orEmpty()
    }

    fun connectStart(address: InetSocketAddress) {
        connectStartMillis = SystemClock.elapsedRealtime()
        remoteAddress = address.address?.hostAddress ?: address.hostString
    }

    fun connectEnd(protocol: Protocol?) {
        tcpMillis = elapsedSince(connectStartMillis)
        this.protocol = protocol?.toString().orEmpty()
    }

    fun tlsStart() {
        tlsStartMillis = SystemClock.elapsedRealtime()
    }

    fun tlsEnd() {
        tlsMillis = elapsedSince(tlsStartMillis)
    }

    fun requestHeadersEnd() {
        requestHeadersEndMillis = SystemClock.elapsedRealtime()
    }

    fun responseHeadersStart() {
        responseHeadersStartMillis = SystemClock.elapsedRealtime()
        ttfbMillis = elapsedSince(requestHeadersEndMillis.takeIf { it > 0 } ?: callStartMillis)
    }

    fun describe(response: Response): String = listOfNotNull(
        dnsMillis?.let { "DNS ${it} ms" },
        tcpMillis?.let { "TCP ${it} ms" },
        tlsMillis?.let { "TLS ${it} ms" },
        ttfbMillis?.let { "TTFB ${it} ms" },
        remoteAddress.takeIf { it.isNotBlank() }?.let { "remote $it" },
        (response.protocol.toString().ifBlank { protocol }).takeIf { it.isNotBlank() }?.let { "protocol $it" },
        response.handshake?.tlsVersion?.javaName?.let { "TLS $it" }
    ).joinToString(", ")

    private fun elapsedSince(start: Long): Long? =
        if (start > 0) (SystemClock.elapsedRealtime() - start).coerceAtLeast(0) else null
}

private class HttpTimingEventListener(
    private val timing: HttpTiming
) : EventListener() {
    override fun callStart(call: Call) = timing.callStart()
    override fun dnsStart(call: Call, domainName: String) = timing.dnsStart()
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        timing.dnsEnd(inetAddressList)
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
        timing.connectStart(inetSocketAddress)
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        timing.connectEnd(protocol)
    override fun secureConnectStart(call: Call) = timing.tlsStart()
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = timing.tlsEnd()
    override fun requestHeadersEnd(call: Call, request: Request) = timing.requestHeadersEnd()
    override fun responseHeadersStart(call: Call) = timing.responseHeadersStart()
}

internal class IndependentDiagnosticEngine(
    private val context: Context,
    private val investigator: IndependentInvestigator =
        IndependentInvestigator(AndroidIndependentProbeClient(context))
) : SpeedTestEngine {
    override fun startTest(): Flow<SpeedTestEvent> = channelFlow {
        val started = SystemClock.elapsedRealtime()
        val rxBefore = TrafficStats.getUidRxBytes(Process.myUid())
        val txBefore = TrafficStats.getUidTxBytes(Process.myUid())
        val network = NetworkInspector.snapshot(context)
        send(SpeedTestEvent.Stage("Running independent multi-endpoint checks"))
        send(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.INFO, source = "network", message = network.summary())))
        try {
            val diagnosis = investigator.run(network) { observation ->
                send(SpeedTestEvent.Stage("${observation.method}: ${observation.target}"))
                send(
                    SpeedTestEvent.Log(
                        LiveLogEntry(
                            level = if (observation.success) LogLevel.INFO else LogLevel.WARN,
                            source = observation.method.lowercase(),
                            message = "${observation.target}: ${observation.value}. ${observation.detail}"
                        )
                    )
                )
            }
            val evaluation = ConnectionEvaluator.evaluate(
                network = network,
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                idleLatencyMillis = diagnosis.latencyMillis,
                loadedLatencyMillis = diagnosis.latencyMillis,
                jitterMillis = diagnosis.jitterMillis,
                probeFailures = diagnosis.probeFailures,
                probeAttempts = diagnosis.probeAttempts,
                investigation = diagnosis.report,
                measurementCoverage = MeasurementCoverage.LATENCY_ONLY
            )
            val rxDelta = uidByteDelta(rxBefore, TrafficStats.getUidRxBytes(Process.myUid()))
            val txDelta = uidByteDelta(txBefore, TrafficStats.getUidTxBytes(Process.myUid()))
            send(
                SpeedTestEvent.Log(
                    LiveLogEntry(
                        level = LogLevel.INFO,
                        source = "traffic",
                        message = "Approx app data used: down=${rxDelta ?: "unknown"} bytes, up=${txDelta ?: "unknown"} bytes."
                    )
                )
            )
            val rawDetails = JSONObject().apply {
                put("mode", "independent")
                put("network", network.summary())
                put("linkAddresses", network.linkAddresses.joinToString())
                put("routes", network.routes.joinToString(" | "))
                put("mtu", network.mtu)
                put("nat64Prefix", network.nat64Prefix)
                put("httpProxy", network.httpProxy)
                put("privateDnsServerName", network.privateDnsServerName)
                rxDelta?.let { put("uidRxBytesDelta", it) }
                txDelta?.let { put("uidTxBytesDelta", it) }
                put("evidenceConfidence", diagnosis.report.confidence)
                put("evidenceSummary", diagnosis.report.summary)
                put("destinationSpreadMillis", diagnosis.destinationSpreadMillis)
                put("targetProfiles", diagnosis.targetProfiles.toJson())
                put("evidence", diagnosis.report.toJson())
            }.toString()
            send(
                SpeedTestEvent.DiagnosticCompleted(
                    latencyMillis = diagnosis.latencyMillis,
                    jitterMillis = diagnosis.jitterMillis,
                    probeFailures = diagnosis.probeFailures,
                    probeAttempts = diagnosis.probeAttempts,
                    evaluation = evaluation,
                    diagnostic = TestDiagnostic(
                        stage = "completed",
                        message = "Independent multi-endpoint diagnosis completed.",
                        serverMachine = "Cloudflare, Google, IETF",
                        downloadBytes = rxDelta ?: 0,
                        uploadBytes = txDelta ?: 0,
                        elapsedMillis = SystemClock.elapsedRealtime() - started,
                        rawDetails = rawDetails
                    )
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            trySend(SpeedTestEvent.Cancelled)
        } catch (error: Throwable) {
            send(
                SpeedTestEvent.Failed(
                    diagnostic = TestDiagnostic(
                        stage = "failed",
                        message = error.message ?: error.javaClass.simpleName,
                        elapsedMillis = SystemClock.elapsedRealtime() - started,
                        rawDetails = error.stackTraceToString().take(4000)
                    )
                )
            )
        }
    }

    override fun cancel() = investigator.cancel()

    private fun uidByteDelta(before: Long, after: Long): Long? {
        if (before == TrafficStats.UNSUPPORTED.toLong() || after == TrafficStats.UNSUPPORTED.toLong()) return null
        return (after - before).coerceAtLeast(0)
    }
}

private fun List<TargetLatencyProfile>.toJson() = org.json.JSONArray().apply {
    forEach { profile ->
        put(JSONObject().apply {
            put("target", profile.target)
            put("tcpSamples", org.json.JSONArray(profile.tcpSamples))
            put("httpsSamples", org.json.JSONArray(profile.httpsSamples))
            put("temporalJitterMillis", profile.temporalJitterMillis)
            put("medianTcpMillis", profile.medianTcpMillis)
            put("p95TcpMillis", profile.p95TcpMillis)
            put("medianHttpsMillis", profile.medianHttpsMillis)
            put("failures", profile.failures)
            put("attempts", profile.attempts)
        })
    }
}
