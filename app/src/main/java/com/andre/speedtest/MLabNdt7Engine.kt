package com.andre.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

class MLabNdt7Engine(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val locateUrl: String = "https://locate.measurementlab.net/v2/nearest/ndt/ndt7"
) : SpeedTestEngine {
    private var activeSocket: WebSocket? = null
    private var activeCall: Call? = null

    override fun startTest(): Flow<SpeedTestEvent> = channelFlow {
        val started = SystemClock.elapsedRealtime()
        val network = NetworkInspector.snapshot(context)
        val evidence = Collections.synchronizedList(mutableListOf<InvestigationEvidence>())

        suspend fun record(item: InvestigationEvidence) {
            evidence += item
            val level = when (item.status) {
                EvidenceStatus.PASS -> LogLevel.INFO
                EvidenceStatus.WARN -> LogLevel.WARN
                EvidenceStatus.FAIL -> LogLevel.ERROR
            }
            val label = if (item.fallbackUsed) "${item.status.name.lowercase()}, fallback" else item.status.name.lowercase()
            log(level, "evidence", "${item.method} [$label]: ${item.value}. ${item.detail}")
        }

        log(LogLevel.INFO, "network", network.summary())
        record(
            InvestigationEvidence(
                method = "Android network validation",
                status = if (network.validated) EvidenceStatus.PASS else EvidenceStatus.WARN,
                value = if (network.validated) "validated" else "not validated",
                detail = "Android NetworkCapabilities is one signal; active checks continue even when it is inconclusive."
            )
        )

        try {
            send(SpeedTestEvent.LocatingServer)
            val locateHost = URI(locateUrl).host
            val locateDns = resolveWithFallback(locateHost)
            locateDns.evidence.forEach { record(it) }

            var servers: List<ServerInfo>? = null
            var locateError: Throwable? = null
            repeat(3) { attempt ->
                if (servers == null) {
                    val locateStarted = SystemClock.elapsedRealtime()
                    runCatching { locateServers() }
                        .onSuccess { found ->
                            servers = found
                            val elapsed = SystemClock.elapsedRealtime() - locateStarted
                            record(
                                InvestigationEvidence(
                                    "HTTPS reachability",
                                    EvidenceStatus.PASS,
                                    "M-Lab Locate HTTP succeeded in $elapsed ms",
                                    "The API returned ${found.size} candidate server(s).",
                                    fallbackUsed = attempt > 0
                                )
                            )
                        }
                        .onFailure { error ->
                            locateError = error
                            record(
                                InvestigationEvidence(
                                    "HTTPS reachability",
                                    if (attempt == 2) EvidenceStatus.FAIL else EvidenceStatus.WARN,
                                    "Locate attempt ${attempt + 1} failed",
                                    error.message ?: error.javaClass.simpleName,
                                    fallbackUsed = attempt > 0
                                )
                            )
                        }
                    if (servers == null && attempt < 2) delay(350L * (attempt + 1))
                }
            }
            val locatedServers = servers ?: throw IOException("M-Lab Locate failed after three attempts: ${locateError?.message}", locateError)
            record(
                InvestigationEvidence(
                    method = "Availability cross-check",
                    status = if (network.validated) EvidenceStatus.PASS else EvidenceStatus.WARN,
                    value = if (network.validated) "Android and active HTTPS agree" else "Active HTTPS works while Android is not validated",
                    detail = if (network.validated) "Both the platform state and a real M-Lab HTTPS request confirm internet access." else "The active result is retained, but the disagreement lowers confidence."
                )
            )

            val checkedCandidates = mutableListOf<ServerEndpoint>()
            locatedServers.take(3).forEachIndexed { index, server ->
                val endpointUri = URI(server.downloadUrl)
                val host = endpointUri.host ?: return@forEachIndexed
                val port = endpointUri.effectivePort()
                val resolution = resolveWithFallback(host)
                resolution.evidence.forEach { record(it.copy(method = "Server DNS ${index + 1}")) }
                if (resolution.addresses.isNotEmpty()) {
                    val probe = runCatching { tcpConnectWithFallback(resolution.addresses, port) }.getOrNull()
                    checkedCandidates += ServerEndpoint(server, host, port, resolution.addresses, probe?.millis)
                    record(
                        InvestigationEvidence(
                            method = "Server candidate ${index + 1}",
                            status = if (probe != null) EvidenceStatus.PASS else EvidenceStatus.WARN,
                            value = if (probe != null) "${server.machine} reachable in ${probe.millis} ms" else "${server.machine} TCP precheck failed",
                            detail = if (probe != null) "Connected through ${probe.address.hostAddress} (${probe.family})." else "NDT7 is still attempted because a short TCP precheck can be inconclusive.",
                            fallbackUsed = index > 0 || probe?.fallbackUsed == true
                        )
                    )
                }
            }
            if (checkedCandidates.isEmpty()) error("No M-Lab candidate had a resolvable endpoint.")

            val orderedCandidates = checkedCandidates.sortedWith(compareBy<ServerEndpoint> { it.precheckMillis == null }.thenBy { it.precheckMillis ?: Long.MAX_VALUE })
            var measurement: ServerMeasurement? = null
            var measurementError: Throwable? = null
            for ((index, candidate) in orderedCandidates.withIndex()) {
                send(SpeedTestEvent.ServerSelected(candidate.server))
                log(LogLevel.INFO, "fallback", "Trying M-Lab candidate ${index + 1}/${orderedCandidates.size}: ${candidate.server.machine}.")
                try {
                    measurement = measureServer(candidate)
                    measurement.evidence.forEach { record(it.copy(fallbackUsed = it.fallbackUsed || index > 0)) }
                    break
                } catch (error: Throwable) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    measurementError = error
                    activeSocket?.cancel()
                    record(
                        InvestigationEvidence(
                            "NDT7 candidate ${index + 1}",
                            if (index == orderedCandidates.lastIndex) EvidenceStatus.FAIL else EvidenceStatus.WARN,
                            "${candidate.server.machine} failed",
                            error.message ?: error.javaClass.simpleName,
                            fallbackUsed = index > 0
                        )
                    )
                }
            }
            val measured = measurement ?: throw IOException("All M-Lab NDT7 candidates failed: ${measurementError?.message}", measurementError)
            val server = measured.server
            val download = measured.download
            val upload = measured.upload
            val idleLatency = measured.idleLatencyMillis
            val loadedLatency = measured.loadedLatencyMillis
            val jitter = measured.jitterMillis
            val probeFailures = measured.probeFailures
            val probeAttempts = measured.probeAttempts
            val retransmissions = listOfNotNull(download.totalRetransmissions, upload.totalRetransmissions).sum()

            buildCrossChecks(measured).forEach { record(it) }
            val investigation = buildInvestigationReport(evidence)
            val evaluation = ConnectionEvaluator.evaluate(
                network,
                download.megabitsPerSecond,
                upload.megabitsPerSecond,
                idleLatency,
                loadedLatency,
                jitter,
                probeFailures,
                probeAttempts,
                retransmissions,
                investigation
            )

            log(LogLevel.INFO, "evaluation", "Score ${evaluation.score}/100 (${evaluation.verdict}), evidence ${investigation.confidence}: ${evaluation.summary}.")
            send(
                SpeedTestEvent.Completed(
                    download = download,
                    upload = upload,
                    latencyMillis = idleLatency,
                    loadedLatencyMillis = loadedLatency,
                    jitterMillis = jitter,
                    probeFailures = probeFailures,
                    probeAttempts = probeAttempts,
                    server = server,
                    evaluation = evaluation,
                    diagnostic = TestDiagnostic(
                        stage = "completed",
                        message = "M-Lab NDT7 connection evaluation completed.",
                        locateStatus = "ok",
                        serverMachine = server.machine,
                        downloadBytes = download.bytesTransferred,
                        uploadBytes = upload.bytesTransferred,
                        elapsedMillis = SystemClock.elapsedRealtime() - started,
                        rawDetails = JSONObject().apply {
                            put("network", network.summary())
                            put("idleProbeSamplesMs", measured.idleSamples.joinToString(","))
                            put("loadedProbeSamplesMs", measured.loadedSamples.joinToString(","))
                            put("probeFailures", probeFailures)
                            put("probeAttempts", probeAttempts)
                            put("downloadServerRttMs", download.serverRttMillis)
                            put("uploadServerRttMs", upload.serverRttMillis)
                            put("tcpRetransmissions", retransmissions)
                            put("evidenceConfidence", investigation.confidence)
                            put("evidenceSummary", investigation.summary)
                            put("evidence", investigation.toJson())
                        }.toString()
                    )
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            trySend(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.WARN, source = "test", message = "Evaluation cancelled.")))
            trySend(SpeedTestEvent.Cancelled)
        } catch (error: Throwable) {
            log(LogLevel.ERROR, "test", "${error.javaClass.simpleName}: ${error.message}")
            val investigation = buildInvestigationReport(evidence)
            val evaluation = ConnectionEvaluator.evaluate(
                network = network,
                downloadMbps = 0.0,
                uploadMbps = 0.0,
                idleLatencyMillis = 0,
                loadedLatencyMillis = 0,
                jitterMillis = 0,
                probeFailures = 0,
                probeAttempts = 0,
                investigation = investigation,
                measurementsAvailable = false
            )
            send(
                SpeedTestEvent.Failed(
                    TestDiagnostic(
                        stage = "failed",
                        message = error.message ?: error.javaClass.simpleName,
                        elapsedMillis = SystemClock.elapsedRealtime() - started,
                        rawDetails = JSONObject().apply {
                            put("error", error.stackTraceToString().take(4000))
                            put("evidenceConfidence", investigation.confidence)
                            put("evidence", investigation.toJson())
                        }.toString()
                    ),
                    evaluation
                )
            )
        } finally {
            activeSocket?.cancel()
            activeCall?.cancel()
        }
    }

    override fun cancel() {
        activeSocket?.cancel()
        activeCall?.cancel()
    }

    private suspend fun ProducerScope<SpeedTestEvent>.log(level: LogLevel, source: String, message: String) {
        send(SpeedTestEvent.Log(LiveLogEntry(level = level, source = source, message = message)))
    }

    private suspend fun resolveWithFallback(host: String): DnsResolution = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val evidence = mutableListOf<InvestigationEvidence>()
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val networkResult = runCatching { activeNetwork.getAllByName(host).toList() }
            val addresses = networkResult.getOrNull().orEmpty()
            if (addresses.isNotEmpty()) {
                evidence += InvestigationEvidence("Active-network DNS", EvidenceStatus.PASS, "${addresses.size} address(es)", "Resolution was bound to Android's active network.")
                return@withContext DnsResolution(addresses, eviÛŞ:¶‰ËkºwµçH°µ•ÍÍ…”€ôµ•ÍÍ…”¤¤¤ô(€€€€€€€€€€€€¤(€€€€€€€ô(€€€€€€€•Ù¥‘•¹”€¬ô%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹” (€€€€€€€€€€€€‰9PÜÕÁ±½…ˆ°(€€€€€€€€€€€Ù¥‘•¹•MÑ…ÑÕÌ¹AML°(€€€€€€€€€€€€ˆ‘íMÁ••‘5…Ñ ¹™½Éµ…Ñ5‰ÁÌ¡ÕÁ±½…¹µ•…‰¥ÑÍA•ÉM•½¹¥ô5‰ÁÌˆ°(€€€€€€€€€€€€‰]•‰M½­•Ğµ•…ÍÕÉ•µ•¹Ğ½µÁ±•Ñ•½¸€‘í•¹‘Á½¥¹Ğ¹Í•ÉÙ•È¹µ…¡¥¹•ô¸ˆ(€€€€€€€€¤((€€€€€€€Ù…°Í•ÉÙ•ÉIÑÑM…µÁ±•Ì€ô±¥ÍÑ=™9½Ñ9Õ±°¡‘½İ¹±½…¹Í•ÉÙ•ÉIÑÑ5¥±±¥Ì°ÕÁ±½…¹Í•ÉÙ•ÉIÑÑ5¥±±¥Ì¤¹™¥±Ñ•Èì¥Ğ€ø€Àô(€€€€€€€Ù…°ÑÁ%‘±”€ôMÁ••‘5…Ñ ¹µ•‘¥…¸¡¥‘±•M…µÁ±•Ì¤(€€€€€€€Ù…°¥‘±•1…Ñ•¹ä€ôÑÁ%‘±”¹Ñ…­•%˜ì¥Ğ€ø€Àô€üèMÁ••‘5…Ñ ¹µ•‘¥…¸¡Í•ÉÙ•ÉIÑÑM…µÁ±•Ì¤(€€€€€€€¥˜€¡ÑÁ%‘±”€ôô€Á0€˜˜¥‘±•1…Ñ•¹ä€ø€À¤ì(€€€€€€€€€€€•Ù¥‘•¹”€¬ô%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹” (€€€€€€€€€€€€€€€€‰1…Ñ•¹ä™…±±‰…¬ˆ°(€€€€€€€€€€€€€€€Ù¥‘•¹•MÑ…ÑÕÌ¹AML°(€€€€€€€€€€€€€€€€ˆ‘¥‘±•1…Ñ•¹äµÌÍ•ÉÙ•ÈIQPˆ°(€€€€€€€€€€€€€€€€‰Q@½¹¹•Ñ¥½¸ÁÉ½‰•Ìİ•É”Õ¹…Ù…¥±…‰±”°Í¼ÉÕ¹½É•ÍĞÕÍ•4µ1…ˆQA%¹™¼IQP…¹±…‰•±Ì½¹™¥‘•¹”…½É‘¥¹±ä¸ˆ°(€€€€€€€€€€€€€€€™…±±‰…­UÍ•€ôÑÉÕ”(€€€€€€€€€€€€¤(€€€€€€€ô(€€€€€€€Ù…°±½…‘•‘1…Ñ•¹ä€ôMÁ••‘5…Ñ ¹µ•‘¥…¸¡±½…‘•‘M…µÁ±•Ì¹Ñ½1¥ÍĞ ¤¤¹Ñ…­•%˜ì¥Ğ€ø€Àô(€€€€€€€€€€€€üèMÁ••‘5…Ñ ¹µ•‘¥…¸¡Í•ÉÙ•ÉIÑÑM…µÁ±•Ì¤¹Ñ…­•%˜ì¥Ğ€ø€Àô(€€€€€€€€€€€€üè¥‘±•1…Ñ•¹ä(€€€€€€€Ù…°©¥ÑÑ•È€ôMÁ••‘5…Ñ ¹©¥ÑÑ•È¡¥‘±•M…µÁ±•Ì¤¹Ñ…­•%˜ì¥‘±•M…µÁ±•Ì¹Í¥é”€øô€Èô(€€€€€€€€€€€€üèMÁ••‘5…Ñ ¹µ•‘¥…¸¡±¥ÍÑ=™9½Ñ9Õ±°¡‘½İ¹±½…¹Í•ÉÙ•ÉIÑÑY…É¥…Ñ¥½¹5¥±±¥Ì°ÕÁ±½…¹Í•ÉÙ•ÉIÑÑY…É¥…Ñ¥½¹5¥±±¥Ì¤¹™¥±Ñ•Èì¥Ğ€ø€Àô¤(€€€€€€€Ù…°ÁÉ½‰•…¥±ÕÉ•Ì€ô¥‘±•…¥±ÕÉ•Ì€¬±½…‘•‘…¥±ÕÉ•Ì¹•Ğ ¤(€€€€€€€Ù…°ÁÉ½‰•ÑÑ•µÁÑÌ€ô€Ô€¬±½…‘•‘ÑÑ•µÁÑÌ¹•Ğ ¤(€€€€€€€•Ù¥‘•¹”€¬ô%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹” (€€€€€€€€€€€µ•Ñ¡½€ô€‰1½…‘•Q@ÁÉ½‰•Ìˆ°(€€€€€€€€€€€ÍÑ…ÑÕÌ€ô¥˜€¡±½…‘•‘M…µÁ±•Ì¹¥Í9½ÑµÁÑä ¤¤Ù¥‘•¹•MÑ…ÑÕÌ¹AML•±Í”Ù¥‘•¹•MÑ…ÑÕÌ¹]I8°(€€€€€€€€€€€Ù…±Õ”€ô€ˆ‘í±½…‘•‘M…µÁ±•Ì¹Í¥é•ô±½…‘•Í…µÁ±”¡Ì¤ì€‘ÁÉ½‰•ÑÑ•µÁÑÌÑ½Ñ…°¥‘±”½±½…‘•…ÑÑ•µÁÑÌˆ°(€€€€€€€€€€€‘•Ñ…¥°€ô€‰1½…‘•ÁÉ½‰•ÌÉ…¸½¹ÕÉÉ•¹Ñ±äİ¥Ñ ‰½Ñ 9PÜÑÉ…¹Í™•È‘¥É•Ñ¥½¹Ì¸ˆ°(€€€€€€€€€€€™…±±‰…­UÍ•€ô±½…‘•‘M…µÁ±•Ì¹¥ÍµÁÑä ¤(€€€€€€€€¤((€€€€€€€É•ÑÕÉ¸M•ÉÙ•É5•…ÍÕÉ•µ•¹Ğ (€€€€€€€€€€€Í•ÉÙ•È€ô•¹‘Á½¥¹Ğ¹Í•ÉÙ•È°(€€€€€€€€€€€‘½İ¹±½…€ô‘½İ¹±½…°(€€€€€€€€€€€ÕÁ±½…€ôÕÁ±½…°(€€€€€€€€€€€¥‘±•1…Ñ•¹å5¥±±¥Ì€ô¥‘±•1…Ñ•¹ä°(€€€€€€€€€€€±½…‘•‘1…Ñ•¹å5¥±±¥Ì€ô±½…‘•‘1…Ñ•¹ä°(€€€€€€€€€€€©¥ÑÑ•É5¥±±¥Ì€ô©¥ÑÑ•È°(€€€€€€€€€€€ÁÉ½‰•…¥±ÕÉ•Ì€ôÁÉ½‰•…¥±ÕÉ•Ì°(€€€€€€€€€€€ÁÉ½‰•ÑÑ•µÁÑÌ€ôÁÉ½‰•ÑÑ•µÁÑÌ°(€€€€€€€€€€€¥‘±•M…µÁ±•Ì€ô¥‘±•M…µÁ±•Ì°(€€€€€€€€€€€±½…‘•‘M…µÁ±•Ì€ô±½…‘•‘M…µÁ±•Ì¹Ñ½1¥ÍĞ ¤°(€€€€€€€€€€€•Ù¥‘•¹”€ô•Ù¥‘•¹”(€€€€€€€€¤(€€€ô((€€€ÁÉ¥Ù…Ñ”™Õ¸‰Õ¥±‘É½ÍÍ¡•­Ì¡µ•…ÍÕÉ•µ•¹ĞèM•ÉÙ•É5•…ÍÕÉ•µ•¹Ğ¤è1¥ÍĞñ%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹”øì(€€€€€€€Ù…°•Ù¥‘•¹”€ôµÕÑ…‰±•1¥ÍÑ=˜ñ%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹”ø ¤(€€€€€€€Ù…°ÑÁIÑĞ€ôMÁ••‘5…Ñ ¹µ•‘¥…¸¡µ•…ÍÕÉ•µ•¹Ğ¹¥‘±•M…µÁ±•Ì¤(€€€€€€€Ù…°Í•ÉÙ•ÉIÑĞ€ôMÁ••‘5…Ñ ¹µ•‘¥…¸¡±¥ÍÑ=™9½Ñ9Õ±°¡µ•…ÍÕÉ•µ•¹Ğ¹‘½İ¹±½…¹Í•ÉÙ•ÉIÑÑ5¥±±¥Ì°µ•…ÍÕÉ•µ•¹Ğ¹ÕÁ±½…¹Í•ÉÙ•ÉIÑÑ5¥±±¥Ì¤¹™¥±Ñ•Èì¥Ğ€ø€Àô¤(€€€€€€€¥˜€¡ÑÁIÑĞ€ø€À€˜˜Í•ÉÙ•ÉIÑĞ€ø€À¤ì(€€€€€€€€€€€Ù…°‘¥™™•É•¹”€ô…‰Ì¡ÑÁIÑĞ€´Í•ÉÙ•ÉIÑĞ¤(€€€€€€€€€€€Ù…°…É••Ì€ô‘¥™™•É•¹”€ğô€ÌÔñğ‘¥™™•É•¹”¹Ñ½½Õ‰±” ¤€¼µ…à¡ÑÁIÑĞ°Í•ÉÙ•ÉIÑĞ¤€ğô€À¸Ø(€€€€€€€€€€€•Ù¥‘•¹”€¬ô%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹” (€€€€€€€€€€€€€€€€‰1…Ñ•¹äÉ½ÍÌµ¡•¬ˆ°(€€€€€€€€€€€€€€€¥˜€¡…É••Ì¤Ù¥‘•¹•MÑ…ÑÕÌ¹AML•±Í”Ù¥‘•¹•MÑ…ÑÕÌ¹]I8°(€€€€€€€€€€€€€€€€‰Q@½¹¹•Ğ€‘ÑÁIÑĞµÌÙÌÍ•ÉÙ•ÈIQP€‘Í•ÉÙ•ÉIÑĞµÌˆ°(€€€€€€€€€€€€€€€¥˜€¡…É••Ì¤€‰%¹‘•Á•¹‘•¹Ğ±¥•¹Ğ…¹Í•ÉÙ•È±…Ñ•¹äÍ¥¹…±Ì‰É½…‘±ä…É•”¸ˆ•±Í”€‰Q¡”µ•Ñ¡½‘Ì‘¥™™•Èµ…Ñ•É¥…±±äìÑ¡•äµ•…ÍÕÉ”‘¥™™•É•¹ĞÁÉ½Ñ½½°±…å•ÉÌ°Í¼Ñ¡”É•ÍÕ±Ğ¥ÌÉ•Ñ…¥¹•İ¥Ñ ±½İ•È½¹™¥‘•¹”¸ˆ(€€€€€€€€€€€€¤(€€€€€€€ô(€€€€€€€…‘‘Q¡É½Õ¡ÁÕÑÉ½ÍÍ¡•¬¡•Ù¥‘•¹”°€‰½İ¹±½…ˆ°µ•…ÍÕÉ•µ•¹Ğ¹‘½İ¹±½…¤(€€€€€€€…‘‘Q¡É½Õ¡ÁÕÑÉ½ÍÍ¡•¬¡•Ù¥‘•¹”°€‰UÁ±½…ˆ°µ•…ÍÕÉ•µ•¹Ğ¹ÕÁ±½…¤(€€€€€€€É•ÑÕÉ¸•Ù¥‘•¹”(€€€ô((€€€ÁÉ¥Ù…Ñ”™Õ¸…‘‘Q¡É½Õ¡ÁÕÑÉ½ÍÍ¡•¬ (€€€€€€€•Ù¥‘•¹”è5ÕÑ…‰±•1¥ÍĞñ%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹”ø°(€€€€€€€Á¡…Í”èMÑÉ¥¹œ°(€€€€€€€µ•…ÍÕÉ•µ•¹ĞèA¡…Í•5•…ÍÕÉ•µ•¹Ğ(€€€€¤ì(€€€€€€€Ù…°±¥•¹Ğ€ôµ•…ÍÕÉ•µ•¹Ğ¹±¥•¹Ñ5•…‰¥ÑÍA•ÉM•½¹€üèÉ•ÑÕÉ¸(€€€€€€€Ù…°Í•ÉÙ•È€ôµ•…ÍÕÉ•µ•¹Ğ¹Í•ÉÙ•É5•…‰¥ÑÍA•ÉM•½¹€üèÉ•ÑÕÉ¸(€€€€€€€¥˜€¡±¥•¹Ğ€ğô€ÀñğÍ•ÉÙ•È€ğô€À¤É•ÑÕÉ¸(€€€€€€€Ù…°É•±…Ñ¥Ù•¥™™•É•¹”€ô…‰Ì¡±¥•¹Ğ€´Í•ÉÙ•È¤€¼µ…à¡±¥•¹Ğ°Í•ÉÙ•È¤(€€€€€€€Ù…°…É••Ì€ôÉ•±…Ñ¥Ù•¥™™•É•¹”€ğô€À¸ÈÔ(€€€€€€€•Ù¥‘•¹”€¬ô%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹” (€€€€€€€€€€€€ˆ‘Á¡…Í”Ñ¡É½Õ¡ÁÕĞÉ½ÍÌµ¡•¬ˆ°(€€€€€€€€€€€¥˜€¡…É••Ì¤Ù¥‘•¹•MÑ…ÑÕÌ¹AML•±Í”Ù¥‘•¹•MÑ…ÑÕÌ¹]I8°(€€€€€€€€€€€€‰±¥•¹Ğ€‘íMÁ••‘5…Ñ ¹™½Éµ…Ñ5‰ÁÌ¡±¥•¹Ğ¥ôÙÌÍ•ÉÙ•È€‘íMÁ••‘5…Ñ ¹™½Éµ…Ñ5‰ÁÌ¡Í•ÉÙ•È¥ô5‰ÁÌˆ°(€€€€€€€€€€€¥˜€¡…É••Ì¤€‰±¥•¹Ğ‰åÑ”Ñ¥µ¥¹œ…¹4µ1…ˆÁÁ%¹™¼…É•”İ¥Ñ¡¥¸€ÈÔ”¸ˆ•±Í”€‰±¥•¹Ğ…¹Í•ÉÙ•È…½Õ¹Ñ¥¹œ‘¥™™•È‰ä€‘ì¡É•±…Ñ¥Ù•¥™™•É•¹”€¨€ÄÀÀ¤¹Ñ½%¹Ğ ¥ô”ìÑ¡”Í•ÉÙ•ÈµÉ•Á½ÉÑ•É•ÍÕ±Ğ¥ÌÉ•Ñ…¥¹•İ¥Ñ ±½İ•È½¹™¥‘•¹”¸ˆ(€€€€€€€€¤(€€€ô((€€€ÁÉ¥Ù…Ñ”™Õ¸‰Õ¥±‘%¹Ù•ÍÑ¥…Ñ¥½¹I•Á½ÉĞ¡•Ù¥‘•¹”è1¥ÍĞñ%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹”ø¤è%¹Ù•ÍÑ¥…Ñ¥½¹I•Á½ÉĞì(€€€€€€€Ù…°Í¹…ÁÍ¡½Ğ€ô•Ù¥‘•¹”¹Ñ½1¥ÍĞ ¤(€€€€€€€Ù…°Á…ÍÍ½Õ¹Ğ€ôÍ¹…ÁÍ¡½Ğ¹½Õ¹Ğì¥Ğ¹ÍÑ…ÑÕÌ€ôôÙ¥‘•¹•MÑ…ÑÕÌ¹AMLô(€€€€€€€Ù…°™…¥±½Õ¹Ğ€ôÍ¹…ÁÍ¡½Ğ¹½Õ¹Ğì¥Ğ¹ÍÑ…ÑÕÌ€ôôÙ¥‘•¹•MÑ…ÑÕÌ¹%0ô(€€€€€€€Ù…°™…±±‰…­½Õ¹Ğ€ôÍ¹…ÁÍ¡½Ğ¹½Õ¹Ğì¥Ğ¹™…±±‰…­UÍ•€˜˜¥Ğ¹ÍÑ…ÑÕÌ€ôôÙ¥‘•¹•MÑ…ÑÕÌ¹AMLô(€€€€€€€Ù…°½¹ÑÉ…‘¥Ñ¥½¹½Õ¹Ğ€ôÍ¹…ÁÍ¡½Ğ¹½Õ¹Ğì€‰É½ÍÌµ¡•¬ˆ¥¸¥Ğ¹µ•Ñ¡½¹±½İ•É…Í” ¤€˜˜¥Ğ¹ÍÑ…ÑÕÌ€„ôÙ¥‘•¹•MÑ…ÑÕÌ¹AMLô(€€€€€€€Ù…°½µÁ±•Ñ•‘5•…ÍÕÉ•µ•¹Ğ€ôÍ¹…ÁÍ¡½Ğ¹…¹äì¥Ğ¹µ•Ñ¡½€ôô€‰9PÜ‘½İ¹±½…ˆ€˜˜¥Ğ¹ÍÑ…ÑÕÌ€ôôÙ¥‘•¹•MÑ…ÑÕÌ¹AMLô€˜˜(€€€€€€€€€€€Í¹…ÁÍ¡½Ğ¹…¹äì¥Ğ¹µ•Ñ¡½€ôô€‰9PÜÕÁ±½…ˆ€˜˜¥Ğ¹ÍÑ…ÑÕÌ€ôôÙ¥‘•¹•MÑ…ÑÕÌ¹AMLô(€€€€€€€Ù…°…Ñ¥Ù•I•…¡…‰¥±¥Ñä€ôÍ¹…ÁÍ¡½Ğ¹…¹äì¥Ğ¹µ•Ñ¡½€ôô€‰!QQALÉ•…¡…‰¥±¥Ñäˆ€˜˜¥Ğ¹ÍÑ…ÑÕÌ€ôôÙ¥‘•¹•MÑ…ÑÕÌ¹AMLô(€€€€€€€Ù…°½¹™¥‘•¹”€ôİ¡•¸ì(€€€€€€€€€€€½µÁ±•Ñ•‘5•…ÍÕÉ•µ•¹Ğ€˜˜…Ñ¥Ù•I•…¡…‰¥±¥Ñä€˜˜™…¥±½Õ¹Ğ€ôô€À€˜˜½¹ÑÉ…‘¥Ñ¥½¹½Õ¹Ğ€ôô€À€˜˜Á…ÍÍ½Õ¹Ğ€øô€Ü€´ø€‰!¥ ˆ(€€€€€€€€€€€½µÁ±•Ñ•‘5•…ÍÕÉ•µ•¹Ğ€˜˜…Ñ¥Ù•I•…¡…‰¥±¥Ñä€˜˜½¹ÑÉ…‘¥Ñ¥½¹½Õ¹Ğ€ğô€Ä€´ø€‰5•‘¥Õ´ˆ(€€€€€€€€€€€•±Í”€´ø€‰1½Üˆ(€€€€€€€ô(€€€€€€€Ù…°ÍÕµµ…Éä€ô€ˆ‘Á…ÍÍ½Õ¹Ğµ•Ñ¡½‘ÌÁ…ÍÍ•ì€‘™…±±‰…­½Õ¹ĞÍÕ•ÍÍ™Õ°™…±±‰…¬¡Ì¤ì€‘½¹ÑÉ…‘¥Ñ¥½¹½Õ¹ĞÉ½ÍÌµ¡•¬‘¥Í…É••µ•¹Ğ¡Ì¤ì€‘™…¥±½Õ¹ĞÑ•Éµ¥¹…°™…¥±ÕÉ”¡Ì¤¸ˆ(€€€€€€€É•ÑÕÉ¸%¹Ù•ÍÑ¥…Ñ¥½¹I•Á½ÉĞ¡½¹™¥‘•¹”°ÍÕµµ…Éä°™…±±‰…­½Õ¹Ğ°½¹ÑÉ…‘¥Ñ¥½¹½Õ¹Ğ°Í¹…ÁÍ¡½Ğ¤(€€€ô((€€€ÁÉ¥Ù…Ñ”™Õ¸%¹Ù•ÍÑ¥…Ñ¥½¹I•Á½ÉĞ¹Ñ½)Í½¸ ¤€ô½Éœ¹©Í½¸¹)M=9ÉÉ…ä ¤¹…ÁÁ±äì(€€€€€€€•Ù¥‘•¹”¹™½É… ì¥Ñ•´€´ø(€€€€€€€€€€€ÁÕĞ¡)M=9=‰©•Ğ ¤¹…ÁÁ±äì(€€€€€€€€€€€€€€€ÁÕĞ ‰µ•Ñ¡½ˆ°¥Ñ•´¹µ•Ñ¡½¤(€€€€€€€€€€€€€€€ÁÕĞ ‰ÍÑ…ÑÕÌˆ°¥Ñ•´¹ÍÑ…ÑÕÌ¹¹…µ”¤(€€€€€€€€€€€€€€€ÁÕĞ ‰Ù…±Õ”ˆ°¥Ñ•´¹Ù…±Õ”¤(€€€€€€€€€€€€€€€ÁÕĞ ‰‘•Ñ…¥°ˆ°¥Ñ•´¹‘•Ñ…¥°¤(€€€€€€€€€€€€€€€ÁÕĞ ‰™…±±‰…­UÍ•ˆ°¥Ñ•´¹™…±±‰…­UÍ•¤(€€€€€€€€€€€ô¤(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”™Õ¸UI$¹•™™•Ñ¥Ù•A½ÉĞ ¤è%¹Ğ€ôİ¡•¸ì(€€€€€€€Á½ÉĞ€ø€À€´øÁ½ÉĞ(€€€€€€€Í¡•µ”¹•ÅÕ…±Ì ‰İÌˆ°ÑÉÕ”¤€´ø€àÀ(€€€€€€€•±Í”€´ø€ĞĞÌ(€€€ô((€€€ÁÉ¥Ù…Ñ”‘…Ñ„±…ÍÌ¹ÍI•Í½±ÕÑ¥½¸ (€€€€€€€Ù…°…‘‘É•ÍÍ•Ìè1¥ÍĞñ%¹•Ñ‘‘É•ÍÌø°(€€€€€€€Ù…°•Ù¥‘•¹”è1¥ÍĞñ%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹”ø(€€€€¤((€€€ÁÉ¥Ù…Ñ”‘…Ñ„±…ÍÌAÉ½‰•I•ÍÕ±Ğ (€€€€€€€Ù…°µ¥±±¥Ìè1½¹œ°(€€€€€€€Ù…°…‘‘É•ÍÌè%¹•Ñ‘‘É•ÍÌ°(€€€€€€€Ù…°™…µ¥±äèMÑÉ¥¹œ°(€€€€€€€Ù…°™…±±‰…­UÍ•è	½½±•…¸(€€€€¤((€€€ÁÉ¥Ù…Ñ”‘…Ñ„±…ÍÌM•ÉÙ•É¹‘Á½¥¹Ğ (€€€€€€€Ù…°Í•ÉÙ•ÈèM•ÉÙ•É%¹™¼°(€€€€€€€Ù…°¡½ÍĞèMÑÉ¥¹œ°(€€€€€€€Ù…°Á½ÉĞè%¹Ğ°(€€€€€€€Ù…°…‘‘É•ÍÍ•Ìè1¥ÍĞñ%¹•Ñ‘‘É•ÍÌø°(€€€€€€€Ù…°ÁÉ•¡•­5¥±±¥Ìè1½¹œü(€€€€¤((€€€ÁÉ¥Ù…Ñ”‘…Ñ„±…ÍÌM•ÉÙ•É5•…ÍÕÉ•µ•¹Ğ (€€€€€€€Ù…°Í•ÉÙ•ÈèM•ÉÙ•É%¹™¼°(€€€€€€€Ù…°‘½İ¹±½…èA¡…Í•5•…ÍÕÉ•µ•¹Ğ°(€€€€€€€Ù…°ÕÁ±½…èA¡…Í•5•…ÍÕÉ•µ•¹Ğ°(€€€€€€€Ù…°¥‘±•1…Ñ•¹å5¥±±¥Ìè1½¹œ°(€€€€€€€Ù…°±½…‘•‘1…Ñ•¹å5¥±±¥Ìè1½¹œ°(€€€€€€€Ù…°©¥ÑÑ•É5¥±±¥Ìè1½¹œ°(€€€€€€€Ù…°ÁÉ½‰•…¥±ÕÉ•Ìè%¹Ğ°(€€€€€€€Ù…°ÁÉ½‰•ÑÑ•µÁÑÌè%¹Ğ°(€€€€€€€Ù…°¥‘±•M…µÁ±•Ìè1¥ÍĞñ1½¹œø°(€€€€€€€Ù…°±½…‘•‘M…µÁ±•Ìè1¥ÍĞñ1½¹œø°(€€€€€€€Ù…°•Ù¥‘•¹”è1¥ÍĞñ%¹Ù•ÍÑ¥…Ñ¥½¹Ù¥‘•¹”ø(€€€€¤((€€€ÁÉ¥Ù…Ñ”ÍÕÍÁ•¹™Õ¸ÉÕ¹A¡…Í” (€€€€€€€ÕÉ°èMÑÉ¥¹œ°(€€€€€€€ÕÁ±½…è	½½±•…¸°(€€€€€€€Í½Á”è½É½ÕÑ¥¹•M½Á”°(€€€€€€€½¹AÉ½É•ÍÌè€¡½Õ‰±”°1½¹œ°1½¹œ¤€´øU¹¥Ğ°(€€€€€€€½¹1½œè€¡1½1•Ù•°°MÑÉ¥¹œ¤€´øU¹¥Ğ(€€€€¤èA¡…Í•5•…ÍÕÉ•µ•¹Ğì(€€€€€€€Ù…°É•ÍÕ±Ğ€ô½µÁ±•Ñ…‰±••™•ÉÉ•ñA¡…Í•5•…ÍÕÉ•µ•¹Ğø ¤(€€€€€€€Ù…°ÑÉ…¹Í™•ÉÉ•€ôÑ½µ¥1½¹œ À¤(€€€€€€€Ù…°‘½¹”€ôÑ½µ¥	½½±•…¸¡™…±Í”¤(€€€€€€€Ù…°Í…µÁ±•Ì€ôÑ½µ¥%¹Ñ••È À¤(€€€€€€€Ù…°ÍÑ…ÉÑ•€ôMåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤(€€€€€€€Ù…°Á…å±½…€ô	åÑ•MÑÉ¥¹œ¹½˜ ©	åÑ•ÉÉ…ä ÄØ€¨€ÄÀÈĞ¤ì€Üô¤(€€€€€€€Ù…°Ñ•±•µ•ÑÉä€ôA¡…Í•Q•±•µ•ÑÉä ¤(€€€€€€€Ù…ÈÍ•¹‘•É)½ˆè)½ˆü€ô¹Õ±°(€€€€€€€Ù…È±½Í•É)½ˆè)½ˆü€ô¹Õ±°(€€€€€€€Ù…°Á¡…Í•9…µ”€ô¥˜€¡ÕÁ±½…¤€‰UÁ±½…ˆ•±Í”€‰½İ¹±½…ˆ(€€€€€€€Ù…°±…ÍÑAÉ½É•ÍÍ1½œ€ôÑ½µ¥1½¹œ À¤((€€€€€€€™Õ¸™¥¹¥Í  ¤ì(€€€€€€€€€€€¥˜€¡‘½¹”¹½µÁ…É•¹‘M•Ğ¡™…±Í”°ÑÉÕ”¤¤ì(€€€€€€€€€€€€€€€Ù…°•±…ÁÍ•€ô€¡MåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤€´ÍÑ…ÉÑ•¤¹½•É•Ñ1•…ÍĞ Ä¤(€€€€€€€€€€€€€€€Ù…°±¥•¹Ñ	åÑ•Ì€ôÑÉ…¹Í™•ÉÉ•¹•Ğ ¤(€€€€€€€€€€€€€€€Ù…°±¥•¹Ñ5‰ÁÌ€ôMÁ••‘5…Ñ ¹µ‰ÁÌ¡±¥•¹Ñ	åÑ•Ì°•±…ÁÍ•¤(€€€€€€€€€€€€€€€Ù…°Í•ÉÙ•É	åÑ•Ì€ôÑ•±•µ•ÑÉä¹…ÁÁ	åÑ•Ìü¹Ñ…­•%˜ì¥Ğ€ø€Àô(€€€€€€€€€€€€€€€Ù…°Í•ÉÙ•É±…ÁÍ•€ôÑ•±•µ•ÑÉä¹…ÁÁ±…ÁÍ•‘5¥±±¥Ìü¹Ñ…­•%˜ì¥Ğ€ø€Àô(€€€€€€€€€€€€€€€Ù…°Í•ÉÙ•É5‰ÁÌ€ô¥˜€¡Í•ÉÙ•É	åÑ•Ì€„ô¹Õ±°€˜˜Í•ÉÙ•É±…ÁÍ•€„ô¹Õ±°¤MÁ••‘5…Ñ ¹µ‰ÁÌ¡Í•ÉÙ•É	åÑ•Ì°Í•ÉÙ•É±…ÁÍ•¤•±Í”¹Õ±°(€€€€€€€€€€€€€€€Ù…°µ•…ÍÕÉ•‘	åÑ•Ì€ôÍ•ÉÙ•É	åÑ•Ì€üè±¥•¹Ñ	åÑ•Ì(€€€€€€€€€€€€€€€Ù…°µ•…ÍÕÉ•‘±…ÁÍ•€ôÍ•ÉÙ•É±…ÁÍ•€üè•±…ÁÍ•(€€€€€€€€€€€€€€€Ù…°µ•…ÍÕÉ•€ôA¡…Í•5•…ÍÕÉ•µ•¹Ğ (€€€€€€€€€€€€€€€€€€€µ•…‰¥ÑÍA•ÉM•½¹€ôÍ•ÉÙ•É5‰ÁÌ€üè±¥•¹Ñ5‰ÁÌ°(€€€€€€€€€€€€€€€€€€€‰åÑ•ÍQÉ…¹Í™•ÉÉ•€ôµ•…ÍÕÉ•‘	åÑ•Ì°(€€€€€€€€€€€€€€€€€€€‘ÕÉ…Ñ¥½¹5¥±±¥Ì€ôµ•…ÍÕÉ•‘±…ÁÍ•°(€€€€€€€€€€€€€€€€€€€Í…µÁ±•½Õ¹Ğ€ôÍ…µÁ±•Ì¹•Ğ ¤°(€€€€€€€€€€€€€€€€€€€Í•ÉÙ•ÉIÑÑ5¥±±¥Ì€ôÑ•±•µ•ÑÉä¹ÉÑÑ5¥±±¥Ì°(€€€€€€€€€€€€€€€€€€€Í•ÉÙ•ÉIÑÑY…É¥…Ñ¥½¹5¥±±¥Ì€ôÑ•±•µ•ÑÉä¹ÉÑÑY…É¥…Ñ¥½¹5¥±±¥Ì°(€€€€€€€€€€€€€€€€€€€Ñ½Ñ…±I•ÑÉ…¹Íµ¥ÍÍ¥½¹Ì€ôÑ•±•µ•ÑÉä¹Ñ½Ñ…±I•ÑÉ…¹Íµ¥ÍÍ¥½¹Ì°(€€€€€€€€€€€€€€€€€€€±¥•¹Ñ5•…‰¥ÑÍA•ÉM•½¹€ô±¥•¹Ñ5‰ÁÌ°(€€€€€€€€€€€€€€€€€€€Í•ÉÙ•É5•…‰¥ÑÍA•ÉM•½¹€ôÍ•ÉÙ•É5‰ÁÌ(€€€€€€€€€€€€€€€€¤(€€€€€€€€€€€€€€€½¹1½œ¡1½1•Ù•°¹%9<°€ˆ‘Á¡…Í•9…µ”½µÁ±•Ñ”è€‘íMÁ••‘5…Ñ ¹™½Éµ…Ñ5‰ÁÌ¡µ•…ÍÕÉ•¹µ•…‰¥ÑÍA•ÉM•½¹¥ô5‰ÁÌ°€‘íµ•…ÍÕÉ•¹‰åÑ•ÍQÉ…¹Í™•ÉÉ•‘ô‰åÑ•Ì¥¸€‘íµ•…ÍÕÉ•¹‘ÕÉ…Ñ¥½¹5¥±±¥ÍôµÌ¸ˆ¤(€€€€€€€€€€€€€€€É•ÍÕ±Ğ¹½µÁ±•Ñ”¡µ•…ÍÕÉ•¤(€€€€€€€€€€€ô(€€€€€€€ô((€€€€€€€™Õ¸ÁÉ½É•ÍÌ¡Ñ½Ñ…°è1½¹œ°•±…ÁÍ•è1½¹œ¤ì(€€€€€€€€€€€Ù…°µ‰ÁÌ€ôMÁ••‘5…Ñ ¹µ‰ÁÌ¡Ñ½Ñ…°°•±…ÁÍ•¤(€€€€€€€€€€€½¹AÉ½É•ÍÌ¡µ‰ÁÌ°Ñ½Ñ…°°•±…ÁÍ•¤(€€€€€€€€€€€Ù…°¹½Ü€ôMåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤(€€€€€€€€€€€¥˜€¡¹½Ü€´±…ÍÑAÉ½É•ÍÍ1½œ¹•Ğ ¤€øô€ÄÀÀÀ€˜˜±…ÍÑAÉ½É•ÍÍ1½œ¹½µÁ…É•¹‘M•Ğ¡±…ÍÑAÉ½É•ÍÍ1½œ¹•Ğ ¤°¹½Ü¤¤ì(€€€€€€€€€€€€€€€½¹1½œ¡1½1•Ù•°¹%9<°€ˆ‘Á¡…Í•9…µ”ÁÉ½É•ÍÌè€‘íMÁ••‘5…Ñ ¹™½Éµ…Ñ5‰ÁÌ¡µ‰ÁÌ¥ô5‰ÁÌ°€‘Ñ½Ñ…°‰åÑ•Ì¸ˆ¤(€€€€€€€€€€€ô(€€€€€€€ô((€€€€€€€Ù…°É•ÅÕ•ÍĞ€ôI•ÅÕ•ÍĞ¹	Õ¥±‘•È ¤(€€€€€€€€€€€€¹ÕÉ°¡ÕÉ°¤(€€€€€€€€€€€€¹¡•…‘•È ‰UÍ•Èµ•¹Ğˆ°€‰ÉÕ¹½É•ÍĞ¼‘í	Õ¥±‘½¹™¥œ¹YIM%=9}95ôˆ¤(€€€€€€€€€€€€¹¡•…‘•È ‰M•Œµ]•‰M½­•ĞµAÉ½Ñ½½°ˆ°€‰¹•Ğ¹µ•…ÍÕÉ•µ•¹Ñ±…ˆ¹¹‘Ğ¹ØÜˆ¤(€€€€€€€€€€€€¹‰Õ¥± ¤((€€€€€€€…Ñ¥Ù•M½­•Ğ€ô±¥•¹Ğ¹¹•İ]•‰M½­•Ğ¡É•ÅÕ•ÍĞ°½‰©•Ğ€è]•‰M½­•Ñ1¥ÍÑ•¹•È ¤ì(€€€€€€€€€€€½Ù•ÉÉ¥‘”™Õ¸½¹=Á•¸¡İ•‰M½­•Ğè]•‰M½­•Ğ°É•ÍÁ½¹Í”èI•ÍÁ½¹Í”¤ì(€€€€€€€€€€€€€€€½¹1½œ¡1½1•Ù•°¹%9<°€ˆ‘Á¡…Í•9…µ”]•‰M½­•Ğ½Á•¹•İ¥Ñ !QQ@€‘íÉ•ÍÁ½¹Í”¹½‘•ô¸ˆ¤(€€€€€€€€€€€€€€€¥˜€¡ÕÁ±½…¤ì(€€€€€€€€€€€€€€€€€€€Í•¹‘•É)½ˆ€ôÍ½Á”¹±…Õ¹ ¡¥ÍÁ…Ñ¡•ÉÌ¹%<¤ì(€€€€€€€€€€€€€€€€€€€€€€€Ù…°‘•…‘±¥¹”€ôMåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤€¬€ÄÁ|ÀÀÀ(€€€€€€€€€€€€€€€€€€€€€€€İ¡¥±”€¡MåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤€ğ‘•…‘±¥¹”€˜˜€…‘½¹”¹•Ğ ¤¤ì(€€€€€€€€€€€€€€€€€€€€€€€€€€€¥˜€¡İ•‰M½­•Ğ¹ÅÕ•Õ•M¥é” ¤€ø€ÔÄÈ€¨€ÄÀÈĞ¤ì(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€‘•±…ä Ô¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€€½¹Ñ¥¹Õ”(€€€€€€€€€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€€€€€€€€€€€€€¥˜€ …İ•‰M½­•Ğ¹Í•¹¡Á…å±½…¤¤‰É•…¬(€€€€€€€€€€€€€€€€€€€€€€€€€€€Ù…°Ñ½Ñ…°€ôÑÉ…¹Í™•ÉÉ•¹…‘‘¹‘•Ğ¡Á…å±½…¹Í¥é”¹Ñ½1½¹œ ¤¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€Ù…°•±…ÁÍ•€ô€¡MåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤€´ÍÑ…ÉÑ•¤¹½•É•Ñ1•…ÍĞ Ä¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€Í…µÁ±•Ì¹¥¹É•µ•¹Ñ¹‘•Ğ ¤(€€€€€€€€€€€€€€€€€€€€€€€€€€€ÁÉ½É•ÍÌ¡Ñ½Ñ…°°•±…ÁÍ•¤(€€€€€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€€€€€€€€€İ•‰M½­•Ğ¹±½Í” ÄÀÀÀ°€‰ÕÁ±½…½µÁ±•Ñ”ˆ¤(€€€€€€€€€€€€€€€€€€€€€€€™¥¹¥Í  ¤(€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€ô•±Í”ì(€€€€€€€€€€€€€€€€€€€±½Í•É)½ˆ€ôÍ½Á”¹±…Õ¹ ì(€€€€€€€€€€€€€€€€€€€€€€€‘•±…ä ÄÉ|ÀÀÀ¤(€€€€€€€€€€€€€€€€€€€€€€€İ•‰M½­•Ğ¹±½Í” ÄÀÀÀ°€‰‘½İ¹±½…Ñ¥µ•½ÕĞˆ¤(€€€€€€€€€€€€€€€€€€€€€€€™¥¹¥Í  ¤(€€€€€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô((€€€€€€€€€€€½Ù•ÉÉ¥‘”™Õ¸½¹5•ÍÍ…”¡İ•‰M½­•Ğè]•‰M½­•Ğ°Ñ•áĞèMÑÉ¥¹œ¤ì(€€€€€€€€€€€€€€€Í…µÁ±•Ì¹¥¹É•µ•¹Ñ¹‘•Ğ ¤(€€€€€€€€€€€€€€€Ñ•±•µ•ÑÉä¹ÕÁ‘…Ñ”¡Ñ•áĞ¤(€€€€€€€€€€€ô((€€€€€€€€€€€½Ù•ÉÉ¥‘”™Õ¸½¹5•ÍÍ…”¡İ•‰M½­•Ğè]•‰M½­•Ğ°‰åÑ•Ìè	åÑ•MÑÉ¥¹œ¤ì(€€€€€€€€€€€€€€€¥˜€ …ÕÁ±½…¤ì(€€€€€€€€€€€€€€€€€€€Ù…°Ñ½Ñ…°€ôÑÉ…¹Í™•ÉÉ•¹…‘‘¹‘•Ğ¡‰åÑ•Ì¹Í¥é”¹Ñ½1½¹œ ¤¤(€€€€€€€€€€€€€€€€€€€Ù…°•±…ÁÍ•€ô€¡MåÍÑ•µ±½¬¹•±…ÁÍ•‘I•…±Ñ¥µ” ¤€´ÍÑ…ÉÑ•¤¹½•É•Ñ1•…ÍĞ Ä¤(€€€€€€€€€€€€€€€€€€€Í…µÁ±•Ì¹¥¹É•µ•¹Ñ¹‘•Ğ ¤(€€€€€€€€€€€€€€€€€€€ÁÉ½É•ÍÌ¡Ñ½Ñ…°°•±…ÁÍ•¤(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô((€€€€€€€€€€€½Ù•ÉÉ¥‘”™Õ¸½¹±½Í¥¹œ¡İ•‰M½­•Ğè]•‰M½­•Ğ°½‘”è%¹Ğ°É•…Í½¸èMÑÉ¥¹œ¤ì(€€€€€€€€€€€€€€€İ•‰M½­•Ğ¹±½Í”¡½‘”°É•…Í½¸¤(€€€€€€€€€€€€€€€™¥¹¥Í  ¤(€€€€€€€€€€€ô((€€€€€€€€€€€½Ù•ÉÉ¥‘”™Õ¸½¹±½Í•¡İ•‰M½­•Ğè]•‰M½­•Ğ°½‘”è%¹Ğ°É•…Í½¸èMÑÉ¥¹œ¤€ô™¥¹¥Í  ¤((€€€€€€€€€€€½Ù•ÉÉ¥‘”™Õ¸½¹…¥±ÕÉ”¡İ•‰M½­•Ğè]•‰M½­•Ğ°ĞèQ¡É½İ…‰±”°É•ÍÁ½¹Í”èI•ÍÁ½¹Í”ü¤ì(€€€€€€€€€€€€€€€½¹1½œ¡1½1•Ù•°¹II=H°€ˆ‘Á¡…Í•9…µ”]•‰M½­•Ğ™…¥±•è€‘íĞ¹µ•ÍÍ…•ô¸ˆ¤(€€€€€€€€€€€€€€€¥˜€¡‘½¹”¹½µÁ…É•¹‘M•Ğ¡™…±Í”°ÑÉÕ”¤¤ì(€€€€€€€€€€€€€€€€€€€É•ÍÕ±Ğ¹½µÁ±•Ñ•á•ÁÑ¥½¹…±±ä¡%=á•ÁÑ¥½¸ ‰9PÜ€‘íÁ¡…Í•9…µ”¹±½İ•É…Í” ¥ô™…¥±•è€‘íĞ¹µ•ÍÍ…•ôˆ°Ğ¤¤(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€ô(€€€€€€€ô¤((€€€€€€€É•ÑÕÉ¸ÑÉäì(€€€€€€€€€€€É•ÍÕ±Ğ¹…İ…¥Ğ ¤(€€€€€€€ô™¥¹…±±äì(€€€€€€€€€€€Í•¹‘•É)½ˆü¹…¹•° ¤(€€€€€€€€€€€±½Í•É)½ˆü¹…¹•° ¤(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”±…ÍÌA¡…Í•Q•±•µ•ÑÉäì(€€€€€€€Y½±…Ñ¥±”Ù…È…ÁÁ	åÑ•Ìè1½¹œü€ô¹Õ±°(€€€€€€€Y½±…Ñ¥±”Ù…È…ÁÁ±…ÁÍ•‘5¥±±¥Ìè1½¹œü€ô¹Õ±°(€€€€€€€Y½±…Ñ¥±”Ù…ÈÉÑÑ5¥±±¥Ìè1½¹œü€ô¹Õ±°(€€€€€€€Y½±…Ñ¥±”Ù…ÈÉÑÑY…É¥…Ñ¥½¹5¥±±¥Ìè1½¹œü€ô¹Õ±°(€€€€€€€Y½±…Ñ¥±”Ù…ÈÑ½Ñ…±I•ÑÉ…¹Íµ¥ÍÍ¥½¹Ìè1½¹œü€ô¹Õ±°((€€€€€€€™Õ¸ÕÁ‘…Ñ”¡Ñ•áĞèMÑÉ¥¹œ¤ì(€€€€€€€€€€€ÉÕ¹…Ñ¡¥¹œì(€€€€€€€€€€€€€€€Ù…°É½½Ğ€ô)M=9=‰©•Ğ¡Ñ•áĞ¤(€€€€€€€€€€€€€€€É½½Ğ¹½ÁÑ)M=9=‰©•Ğ ‰ÁÁ%¹™¼ˆ¤ü¹±•Ğì…ÁÀ€´ø(€€€€€€€€€€€€€€€€€€€¥˜€¡…ÁÀ¹¡…Ì ‰9Õµ	åÑ•Ìˆ¤¤…ÁÁ	åÑ•Ì€ô…ÁÀ¹½ÁÑ1½¹œ ‰9Õµ	åÑ•Ìˆ¤¹½•É•Ñ1•…ÍĞ À¤(€€€€€€€€€€€€€€€€€€€¥˜€¡…ÁÀ¹¡…Ì ‰±…ÁÍ•‘Q¥µ”ˆ¤¤…ÁÁ±…ÁÍ•‘5¥±±¥Ì€ô…ÁÀ¹½ÁÑ1½¹œ ‰±…ÁÍ•‘Q¥µ”ˆ¤¹‘¥Ø ÄÀÀÀ¤¹½•É•Ñ1•…ÍĞ À¤(€€€€€€€€€€€€€€€ô(€€€€€€€€€€€€€€€Ù…°ÑÀ€ôÉ½½Ğ¹½ÁÑ)M=9=‰©•Ğ ‰QA%¹™¼ˆ¤€üèÉ•ÑÕÉ¸(€€€€€€€€€€€€€€€¥˜€¡ÑÀ¹¡…Ì ‰IQPˆ¤¤ÉÑÑ5¥±±¥Ì€ôÑÀ¹½ÁÑ1½¹œ ‰IQPˆ¤¹‘¥Ø ÄÀÀÀ¤¹½•É•Ñ1•…ÍĞ À¤(€€€€€€€€€€€€€€€¥˜€¡ÑÀ¹¡…Ì ‰IQQY…Èˆ¤¤ÉÑÑY…É¥…Ñ¥½¹5¥±±¥Ì€ôÑÀ¹½ÁÑ1½¹œ ‰IQQY…Èˆ¤¹‘¥Ø ÄÀÀÀ¤¹½•É•Ñ1•…ÍĞ À¤(€€€€€€€€€€€€€€€¥˜€¡ÑÀ¹¡…Ì ‰Q½Ñ…±I•ÑÉ…¹Ìˆ¤¤Ñ½Ñ…±I•ÑÉ…¹Íµ¥ÍÍ¥½¹Ì€ôÑÀ¹½ÁÑ1½¹œ ‰Q½Ñ…±I•ÑÉ…¹Ìˆ¤¹½•É•Ñ1•…ÍĞ À¤(€€€€€€€€€€€ô(€€€€€€€ô(€€€ô((€€€ÁÉ¥Ù…Ñ”™Õ¸9•Ñİ½É­M¹…ÁÍ¡½Ğ¹ÍÕµµ…Éä ¤èMÑÉ¥¹œ€ô‰Õ¥±‘MÑÉ¥¹œì(€€€€€€€…ÁÁ•¹ ‰ÑåÁ”ô‘ÑåÁ”°Ù…±¥‘…Ñ•ô‘Ù…±¥‘…Ñ•°…ÁÑ¥Ù”ô‘…ÁÑ¥Ù•A½ÉÑ…°°µ•Ñ•É•ô‘µ•Ñ•É•°É½…µ¥¹œô‘É½…µ¥¹œ°ÙÁ¸ô‘ÙÁ¸ˆ¤(€€€€€€€…ÁÁ•¹ ˆ°•ÍÑ¥µ…Ñ•ô‘í•ÍÑ¥µ…Ñ•‘½İ¹ÍÑÉ•…µ5‰ÁÍô¼‘í•ÍÑ¥µ…Ñ•‘UÁÍÑÉ•…µ5‰ÁÍô5‰ÁÌˆ¤(€€€€€€€İ¥™¥M¥¹…±‰´ü¹±•Ğì…ÁÁ•¹ ˆ°İ¥™¥IÍÍ¤ô‘¥Ğ‘	´ˆ¤ô(€€€€€€€¥˜€¡¥¹Ñ•É™…•9…µ”¹¥Í9½Ñ	±…¹¬ ¤¤…ÁÁ•¹ ˆ°¥¹Ñ•É™…”ô‘¥¹Ñ•É™…•9…µ”ˆ¤(€€€€€€€…ÁÁ•¹ ˆ°‘¹ÍM•ÉÙ•ÉÌô‘í‘¹ÍM•ÉÙ•ÉÌ¹Í¥é•ô°ÁÉ¥Ù…Ñ•¹Ìô‘ÁÉ¥Ù…Ñ•¹ÍÑ¥Ù”ˆ¤(€€€ô)ô(