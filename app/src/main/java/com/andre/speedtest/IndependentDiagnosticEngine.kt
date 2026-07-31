package com.andre.speedtest

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
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
    val fallbackUsed: Boolean = false
) {
    fun toEvidence() = InvestigationEvidence(
        method = "$method $target",
        status = if (success) EvidenceStatus.PASS else EvidenceStatus.FAIL,
        value = value,
        detail = detail,
        fallbackUsed = fallbackUsed
    )
}

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
    val observations: List<ProbeObservation>
)

internal class IndependentInvestigator(
    private val client: IndependentProbeClient,
    private val targets: List<DiagnosticTarget> = defaultDiagnosticTargets
) {
    suspend fun run(
        network: NetworkSnapshot,
        onObservation: suspend (ProbeObservation) -> Unit = {}
    ): IndependentDiagnosis {
        val observations = mutableListOf<ProbeObservation>()
        for (target in targets) {
            listOf<suspend () -> ProbeObservation>(
                { client.dns(target) },
                { client.tcp(target) },
                { client.https(target) }
            ).forEachIndexed { index, probe ->
                val method = listOf("DNS", "TCP", "HTTPS")[index]
                val observation = try {
                    probe()
                } catch (error: Throwable) {
                    ProbeObservation(
                        method = method,
                        target = target.name,
                        success = false,
                        latencyMillis = null,
                        value = "failed",
                        detail = error.message ?: error.javaClass.simpleName
                    )
                }
                observations += observation
                onObservation(observation)
            }
        }

        val dnsPass = observations.count { it.method == "DNS" && it.success }
        val tcpPass = observations.count { it.method == "TCP" && it.success }
        val httpsPass = observations.count { it.method == "HTTPS" && it.success }
        val activeQuorum = httpsPass >= 2
        val crossCheckAgrees = network.validated == activeQuorum
        val evidence = observations.map { it.toEvidence() }.toMutableList()
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
        val summary = "$dnsPass/${targets.size} DNS, $tcpPass/${targets.size} TCP, and " +
            "$httpsPass/${targets.size} HTTPS targets passed; $contradictionCount cross-check disagreement(s)."
        val report = InvestigationReport(
            confidence = confidence,
            summary = summary,
            fallbackCount = fallbackCount,
            contradictionCount = contradictionCount,
            evidence = evidence
        )
        val tcpLatencies = observations.mapNotNull { observation ->
            observation.latencyMillis?.takeIf {
                observation.method == "TCP" && observation.success
            }
        }
        return IndependentDiagnosis(
            latencyMillis = SpeedMath.median(tcpLatencies),
            jitterMillis = SpeedMath.jitter(tcpLatencies),
            probeFailures = failed,
            probeAttempts = observations.size,
            report = report,
            observations = observations
        )
    }

    fun cancel() = client.cancel()
}

private val defaultDiagnosticTargets = listOf(
    DiagnosticTarget("Cloudflare", "www.cloudflare.com", "https://www.cloudflare.com/cdn-cgi/trace"),
    DiagnosticTarget("Google", "www.google.com", "https://www.google.com/generate_204"),
    DiagnosticTarget("IETF", "www.ietf.org", "https://www.ietf.org/")
)

internal class AndroidIndependentProbeClient(
    context: Context,
    private val networkFallbacks: NetworkFallbacks = NetworkFallbacks(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) : IndependentProbeClient {
    private val activeCalls = Collections.synchronizedList(mutableListOf<Call>())

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
        val request = Request.Builder()
            .url(target.httpsUrl)
            .header("User-Agent", "runForest/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()
        val call = httpClient.newCall(request)
        activeCalls += call
        val started = SystemClock.elapsedRealtime()
        try {
            call.execute().use { response ->
                val elapsed = SystemClock.elapsedRealtime() - started
                if (response.code !in 100..599) throw IOException("Unexpected HTTP ${response.code}.")
                ProbeObservation(
                    method = "HTTPS",
                    target = target.name,
                    success = true,
                    latencyMillis = elapsed,
                    value = "HTTP ${response.code} in $elapsed ms",
                    detail = "TLS and HTTP completed for ${target.httpsUrl}."
                )
            }
        } finally {
            activeCalls -= call
        }
    }

    override fun cancel() {
        activeCalls.toList().forEach(Call::cancel)
    }
}

internal class IndependentDiagnosticEngine(
    private val context: Context,
    private val investigator: IndependentInvestigator =
        IndependentInvestigator(AndroidIndependentProbeClient(context))
) : SpeedTestEngine {
    override fun startTest(): Flow<SpeedTestEvent> = channelFlow {
        val started = SystemClock.elapsedRealtime()
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
            val rawDetails = JSONObject().apply {
                put("mode", "independent")
                put("network", network.summary())
                put("evidenceConfidence", diagnosis.report.confidence)
                put("evidenceSummary", diagnosis.report.summary)
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
}
