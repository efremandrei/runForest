package com.andre.speedtest

import android.content.Context
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
import java.net.URI
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    private val networkFallbacks = NetworkFallbacks(context)

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
            val locateDns = networkFallbacks.resolve(locateHost)
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
                val resolution = networkFallbacks.resolve(host)
                resolution.evidence.forEach { record(it.copy(method = "Server DNS ${index + 1}")) }
                if (resolution.addresses.isNotEmpty()) {
                    val probe = runCatching { networkFallbacks.tcpConnect(resolution.addresses, port) }.getOrNull()
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

            InvestigationAnalyzer.buildCrossChecks(measured).forEach { record(it) }
            val investigation = InvestigationAnalyzer.buildReport(evidence)
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
            val investigation = InvestigationAnalyzer.buildReport(evidence)
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
                measurementCoverage = MeasurementCoverage.AVAILABILITY_ONLY
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

    private suspend fun locateServers(): List<ServerInfo> = CompletableDeferred<String>().also { deferred ->
        val request = Request.Builder()
            .url("$locateUrl?client_name=runforest-android")
            .header("User-Agent", "runForest/${BuildConfig.VERSION_NAME}")
            .build()
        activeCall = client.newCall(request)
        activeCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                deferred.completeExceptionally(IOException("Locate API failed: ${e.message}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        deferred.completeExceptionally(IOException("Locate API HTTP ${it.code}"))
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    if (body.isBlank()) deferred.completeExceptionally(IOException("Locate API returned an empty body."))
                    else deferred.complete(body)
                }
            }
        })
    }.await().let { body ->
        val root = JSONObject(body)
        val results = root.getJSONArray("results")
        if (results.length() == 0) error("Locate API returned no NDT7 servers.")
        (0 until results.length()).map { index ->
            val result = results.getJSONObject(index)
            val urls = result.getJSONObject("urls")
            val location = result.optJSONObject("location")
            ServerInfo(
                machine = result.optString("machine", "unknown"),
                city = location?.optString("city", "Unknown").orEmpty().ifBlank { "Unknown" },
                country = location?.optString("country", "Unknown").orEmpty().ifBlank { "Unknown" },
                downloadUrl = findUrl(urls, "download"),
                uploadUrl = findUrl(urls, "upload")
            )
        }
    }

    private fun findUrl(urls: JSONObject, keyword: String): String {
        val keys = urls.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key.contains(keyword, ignoreCase = true)) {
                val value = urls.get(key)
                return when (value) {
                    is String -> value
                    is org.json.JSONArray -> value.getString(0)
                    else -> value.toString()
                }
            }
        }
        error("Locate API response did not include a $keyword URL.")
    }

    private suspend fun ProducerScope<SpeedTestEvent>.measureServer(endpoint: ServerEndpoint): ServerMeasurement {
        val evidence = mutableListOf<InvestigationEvidence>()
        val idleSamples = mutableListOf<Long>()
        var idleFailures = 0

        send(SpeedTestEvent.Stage("Checking idle responsiveness"))
        repeat(5) { index ->
            val probe = runCatching { networkFallbacks.tcpConnect(endpoint.addresses, endpoint.port) }.getOrNull()
            if (probe == null) {
                idleFailures += 1
                log(LogLevel.WARN, "probe", "Idle probe ${index + 1}/5 failed across all resolved addresses.")
            } else {
                idleSamples += probe.millis
                val fallback = if (probe.fallbackUsed) " using address fallback" else ""
                log(LogLevel.INFO, "probe", "Idle probe ${index + 1}/5: ${probe.millis} ms via ${probe.family}$fallback.")
            }
            if (index < 4) delay(250)
        }
        evidence += InvestigationEvidence(
            method = "Idle TCP probes",
            status = if (idleSamples.isNotEmpty()) EvidenceStatus.PASS else EvidenceStatus.WARN,
            value = "${idleSamples.size}/5 succeeded",
            detail = "Each probe tries every resolved IPv6/IPv4 address before failing.",
            fallbackUsed = idleFailures > 0
        )

        val loadedSamples = Collections.synchronizedList(mutableListOf<Long>())
        val loadedAttempts = AtomicInteger(0)
        val loadedFailures = AtomicInteger(0)

        suspend fun runLoadedProbe(phase: String, block: suspend () -> PhaseMeasurement): PhaseMeasurement {
            val probeJob = launch(Dispatchers.IO) {
                delay(350)
                while (currentCoroutineContext().isActive) {
                    loadedAttempts.incrementAndGet()
                    runCatching { networkFallbacks.tcpConnect(endpoint.addresses, endpoint.port) }
                        .onSuccess { probe ->
                            loadedSamples += probe.millis
                            val fallback = if (probe.fallbackUsed) " using address fallback" else ""
                            trySend(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.INFO, source = "probe", message = "$phase loaded probe: ${probe.millis} ms via ${probe.family}$fallback.")))
                        }
                        .onFailure { error ->
                            loadedFailures.incrementAndGet()
                            trySend(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.WARN, source = "probe", message = "$phase loaded probe failed across all addresses: ${error.message}.")))
                        }
                    delay(750)
                }
            }
            return try {
                block()
            } finally {
                probeJob.cancelAndJoin()
            }
        }

        send(SpeedTestEvent.Stage("Measuring download and loaded latency"))
        val download = runLoadedProbe("Download") {
            runPhase(
                url = endpoint.server.downloadUrl,
                upload = false,
                scope = this@measureServer,
                onProgress = { mbps, bytes, elapsed -> trySend(SpeedTestEvent.DownloadProgress(mbps, bytes, elapsed)) },
                onLog = { level, message -> trySend(SpeedTestEvent.Log(LiveLogEntry(level = level, source = "ndt7", message = message))) }
            )
        }
        evidence += InvestigationEvidence(
            "NDT7 download",
            EvidenceStatus.PASS,
            "${SpeedMath.formatMbps(download.megabitsPerSecond)} Mbps",
            "WebSocket measurement completed on ${endpoint.server.machine}."
        )

        delay(600)
        send(SpeedTestEvent.Stage("Measuring upload and loaded latency"))
        val upload = runLoadedProbe("Upload") {
            runPhase(
                url = endpoint.server.uploadUrl,
                upload = true,
                scope = this@measureServer,
                onProgress = { mbps, bytes, elapsed -> trySend(SpeedTestEvent.UploadProgress(mbps, bytes, elapsed)) },
                onLog = { level, message -> trySend(SpeedTestEvent.Log(LiveLogEntry(level = level, source = "ndt7", message = message))) }
            )
        }
        evidence += InvestigationEvidence(
            "NDT7 upload",
            EvidenceStatus.PASS,
            "${SpeedMath.formatMbps(upload.megabitsPerSecond)} Mbps",
            "WebSocket measurement completed on ${endpoint.server.machine}."
        )

        val serverRttSamples = listOfNotNull(download.serverRttMillis, upload.serverRttMillis).filter { it > 0 }
        val tcpIdle = SpeedMath.median(idleSamples)
        val idleLatency = tcpIdle.takeIf { it > 0 } ?: SpeedMath.median(serverRttSamples)
        if (tcpIdle == 0L && idleLatency > 0) {
            evidence += InvestigationEvidence(
                "Latency fallback",
                EvidenceStatus.PASS,
                "$idleLatency ms server RTT",
                "TCP connection probes were unavailable, so runForest used M-Lab TCPInfo RTT and labels confidence accordingly.",
                fallbackUsed = true
            )
        }
        val loadedLatency = SpeedMath.median(loadedSamples.toList()).takeIf { it > 0 }
            ?: SpeedMath.median(serverRttSamples).takeIf { it > 0 }
            ?: idleLatency
        val jitter = SpeedMath.jitter(idleSamples).takeIf { idleSamples.size >= 2 }
            ?: SpeedMath.median(listOfNotNull(download.serverRttVariationMillis, upload.serverRttVariationMillis).filter { it > 0 })
        val probeFailures = idleFailures + loadedFailures.get()
        val probeAttempts = 5 + loadedAttempts.get()
        evidence += InvestigationEvidence(
            method = "Loaded TCP probes",
            status = if (loadedSamples.isNotEmpty()) EvidenceStatus.PASS else EvidenceStatus.WARN,
            value = "${loadedSamples.size} loaded sample(s); $probeAttempts total idle/loaded attempts",
            detail = "Loaded probes ran concurrently with both NDT7 transfer directions.",
            fallbackUsed = loadedSamples.isEmpty()
        )

        return ServerMeasurement(
            server = endpoint.server,
            download = download,
            upload = upload,
            idleLatencyMillis = idleLatency,
            loadedLatencyMillis = loadedLatency,
            jitterMillis = jitter,
            probeFailures = probeFailures,
            probeAttempts = probeAttempts,
            idleSamples = idleSamples,
            loadedSamples = loadedSamples.toList(),
            evidence = evidence
        )
    }

    private suspend fun runPhase(
        url: String,
        upload: Boolean,
        scope: CoroutineScope,
        onProgress: (Double, Long, Long) -> Unit,
        onLog: (LogLevel, String) -> Unit
    ): PhaseMeasurement {
        val result = CompletableDeferred<PhaseMeasurement>()
        val transferred = AtomicLong(0)
        val done = AtomicBoolean(false)
        val samples = AtomicInteger(0)
        val started = SystemClock.elapsedRealtime()
        val payload = ByteString.of(*ByteArray(16 * 1024) { 7 })
        val telemetry = PhaseTelemetry()
        var senderJob: Job? = null
        var closerJob: Job? = null
        val phaseName = if (upload) "Upload" else "Download"
        val lastProgressLog = AtomicLong(0)

        fun finish() {
            if (done.compareAndSet(false, true)) {
                val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                val clientBytes = transferred.get()
                val clientMbps = SpeedMath.mbps(clientBytes, elapsed)
                val serverBytes = telemetry.appBytes?.takeIf { it > 0 }
                val serverElapsed = telemetry.appElapsedMillis?.takeIf { it > 0 }
                val serverMbps = if (serverBytes != null && serverElapsed != null) SpeedMath.mbps(serverBytes, serverElapsed) else null
                val measuredBytes = serverBytes ?: clientBytes
                val measuredElapsed = serverElapsed ?: elapsed
                val measured = PhaseMeasurement(
                    megabitsPerSecond = serverMbps ?: clientMbps,
                    bytesTransferred = measuredBytes,
                    durationMillis = measuredElapsed,
                    sampleCount = samples.get(),
                    serverRttMillis = telemetry.rttMillis,
                    serverRttVariationMillis = telemetry.rttVariationMillis,
                    totalRetransmissions = telemetry.totalRetransmissions,
                    clientMegabitsPerSecond = clientMbps,
                    serverMegabitsPerSecond = serverMbps
                )
                onLog(LogLevel.INFO, "$phaseName complete: ${SpeedMath.formatMbps(measured.megabitsPerSecond)} Mbps, ${measured.bytesTransferred} bytes in ${measured.durationMillis} ms.")
                result.complete(measured)
            }
        }

        fun progress(total: Long, elapsed: Long) {
            val mbps = SpeedMath.mbps(total, elapsed)
            onProgress(mbps, total, elapsed)
            val now = SystemClock.elapsedRealtime()
            if (now - lastProgressLog.get() >= 1000 && lastProgressLog.compareAndSet(lastProgressLog.get(), now)) {
                onLog(LogLevel.INFO, "$phaseName progress: ${SpeedMath.formatMbps(mbps)} Mbps, $total bytes.")
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "runForest/${BuildConfig.VERSION_NAME}")
            .header("Sec-WebSocket-Protocol", "net.measurementlab.ndt.v7")
            .build()

        activeSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog(LogLevel.INFO, "$phaseName WebSocket opened with HTTP ${response.code}.")
                if (upload) {
                    senderJob = scope.launch(Dispatchers.IO) {
                        val deadline = SystemClock.elapsedRealtime() + 10_000
                        while (SystemClock.elapsedRealtime() < deadline && !done.get()) {
                            if (webSocket.queueSize() > 512 * 1024) {
                                delay(5)
                                continue
                            }
                            if (!webSocket.send(payload)) break
                            val total = transferred.addAndGet(payload.size.toLong())
                            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                            samples.incrementAndGet()
                            progress(total, elapsed)
                        }
                        webSocket.close(1000, "upload complete")
                        finish()
                    }
                } else {
                    closerJob = scope.launch {
                        delay(12_000)
                        webSocket.close(1000, "download timeout")
                        finish()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                samples.incrementAndGet()
                telemetry.update(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!upload) {
                    val total = transferred.addAndGet(bytes.size.toLong())
                    val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
                    samples.incrementAndGet()
                    progress(total, elapsed)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog(LogLevel.ERROR, "$phaseName WebSocket failed: ${t.message}.")
                if (done.compareAndSet(false, true)) {
                    result.completeExceptionally(IOException("NDT7 ${phaseName.lowercase()} failed: ${t.message}", t))
                }
            }
        })

        return try {
            result.await()
        } finally {
            senderJob?.cancel()
            closerJob?.cancel()
        }
    }

}
