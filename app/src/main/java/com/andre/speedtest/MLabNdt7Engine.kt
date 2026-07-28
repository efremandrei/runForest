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
        log(LogLevel.INFO, "network", network.summary())

        if (!network.validated) {
            val evaluation = ConnectionEvaluator.evaluate(network, 0.0, 0.0, 0, 0, 0, 0, 0)
            log(LogLevel.ERROR, "network", evaluation.summary)
            send(
                SpeedTestEvent.Failed(
                    TestDiagnostic("preflight", "No validated internet connection is available."),
                    evaluation
                )
            )
            return@channelFlow
        }

        try {
            send(SpeedTestEvent.LocatingServer)
            val locateHost = URI(locateUrl).host
            val dnsStarted = SystemClock.elapsedRealtime()
            val locateAddresses = resolve(locateHost)
            log(
                LogLevel.INFO,
                "dns",
                "Resolved $locateHost to ${locateAddresses.size} address(es) in ${SystemClock.elapsedRealtime() - dnsStarted} ms."
            )

            val locateStarted = SystemClock.elapsedRealtime()
            val server = locateServer()
            log(LogLevel.INFO, "mlab", "Locate API selected ${server.machine} in ${SystemClock.elapsedRealtime() - locateStarted} ms.")
            send(SpeedTestEvent.ServerSelected(server))

            val endpoint = URI(server.downloadUrl)
            val host = endpoint.host ?: error("Selected M-Lab URL has no host.")
            val port = when {
                endpoint.port > 0 -> endpoint.port
                endpoint.scheme.equals("ws", true) -> 80
                else -> 443
            }

            send(SpeedTestEvent.Stage("Checking idle responsiveness"))
            val serverDnsStarted = SystemClock.elapsedRealtime()
            val serverAddresses = resolve(host)
            log(LogLevel.INFO, "dns", "Resolved M-Lab server to ${serverAddresses.size} address(es) in ${SystemClock.elapsedRealtime() - serverDnsStarted} ms.")

            val idleSamples = mutableListOf<Long>()
            var idleFailures = 0
            repeat(5) { index ->
                val sample = runCatching { tcpConnectMillis(host, port) }.getOrNull()
                if (sample == null) {
                    idleFailures += 1
                    log(LogLevel.WARN, "probe", "Idle probe ${index + 1}/5 failed.")
                } else {
                    idleSamples += sample
                    log(LogLevel.INFO, "probe", "Idle probe ${index + 1}/5: $sample ms.")
                }
                if (index < 4) delay(250)
            }

            val loadedSamples = Collections.synchronizedList(mutableListOf<Long>())
            val loadedAttempts = AtomicInteger(0)
            val loadedFailures = AtomicInteger(0)

            suspend fun runLoadedProbe(phase: String, block: suspend () -> PhaseMeasurement): PhaseMeasurement {
                val probeJob = launch(Dispatchers.IO) {
                    delay(350)
                    while (currentCoroutineContext().isActive) {
                        loadedAttempts.incrementAndGet()
                        runCatching { tcpConnectMillis(host, port) }
                            .onSuccess { millis ->
                                loadedSamples += millis
                                trySend(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.INFO, source = "probe", message = "$phase loaded probe: $millis ms.")))
                            }
                            .onFailure { error ->
                                loadedFailures.incrementAndGet()
                                trySend(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.WARN, source = "probe", message = "$phase loaded probe failed: ${error.message}.")))
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
                    url = server.downloadUrl,
                    upload = false,
                    scope = this@channelFlow,
                    onProgress = { mbps, bytes, elapsed -> trySend(SpeedTestEvent.DownloadProgress(mbps, bytes, elapsed)) },
                    onLog = { level, message -> trySend(SpeedTestEvent.Log(LiveLogEntry(level = level, source = "ndt7", message = message))) }
                )
            }

            delay(600)
            send(SpeedTestEvent.Stage("Measuring upload and loaded latency"))
            val upload = runLoadedProbe("Upload") {
                runPhase(
                    url = server.uploadUrl,
                    upload = true,
                    scope = this@channelFlow,
                    onProgress = { mbps, bytes, elapsed -> trySend(SpeedTestEvent.UploadProgress(mbps, bytes, elapsed)) },
                    onLog = { level, message -> trySend(SpeedTestEvent.Log(LiveLogEntry(level = level, source = "ndt7", message = message))) }
                )
            }

            val idleLatency = SpeedMath.median(idleSamples)
            val loadedLatency = SpeedMath.median(loadedSamples.toList()).takeIf { it > 0 } ?: idleLatency
            val jitter = SpeedMath.jitter(idleSamples)
            val probeFailures = idleFailures + loadedFailures.get()
            val probeAttempts = 5 + loadedAttempts.get()
            val retransmissions = listOfNotNull(download.totalRetransmissions, upload.totalRetransmissions).sum()
            val evaluation = ConnectionEvaluator.evaluate(
                network,
                download.megabitsPerSecond,
                upload.megabitsPerSecond,
                idleLatency,
                loadedLatency,
                jitter,
                probeFailures,
                probeAttempts,
                retransmissions
            )

            log(LogLevel.INFO, "evaluation", "Score ${evaluation.score}/100 (${evaluation.verdict}): ${evaluation.summary}.")
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
                            put("idleProbeSamplesMs", idleSamples.joinToString(","))
                            put("loadedProbeSamplesMs", loadedSamples.joinToString(","))
                            put("probeFailures", probeFailures)
                            put("probeAttempts", probeAttempts)
                            put("downloadServerRttMs", download.serverRttMillis)
                            put("uploadServerRttMs", upload.serverRttMillis)
                            put("tcpRetransmissions", retransmissions)
                        }.toString()
                    )
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            trySend(SpeedTestEvent.Log(LiveLogEntry(level = LogLevel.WARN, source = "test", message = "Evaluation cancelled.")))
            trySend(SpeedTestEvent.Cancelled)
        } catch (error: Throwable) {
            log(LogLevel.ERROR, "test", "${error.javaClass.simpleName}: ${error.message}")
            send(
                SpeedTestEvent.Failed(
                    TestDiagnostic(
                        stage = "failed",
                        message = error.message ?: error.javaClass.simpleName,
                        elapsedMillis = SystemClock.elapsedRealtime() - started,
                        rawDetails = error.stackTraceToString().take(4000)
                    )
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

    private suspend fun resolve(host: String): List<InetAddress> = withContext(Dispatchers.IO) {
        InetAddress.getAllByName(host).toList().ifEmpty { error("DNS returned no addresses for $host.") }
    }

    private suspend fun tcpConnectMillis(host: String, port: Int): Long = withContext(Dispatchers.IO) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val socket: Socket = cm.activeNetwork?.socketFactory?.createSocket() ?: Socket()
        socket.use {
            val started = SystemClock.elapsedRealtime()
            it.connect(InetSocketAddress(host, port), 2500)
            SystemClock.elapsedRealtime() - started
        }
    }

    private suspend fun locateServer(): ServerInfo = CompletableDeferred<String>().also { deferred ->
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
        val result = results.getJSONObject(0)
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
                val measuredBytes = telemetry.appBytes?.takeIf { it > 0 } ?: transferred.get()
                val measuredElapsed = telemetry.appElapsedMillis?.takeIf { it > 0 } ?: elapsed
                val measured = PhaseMeasurement(
                    SpeedMath.mbps(measuredBytes, measuredElapsed),
                    measuredBytes,
                    measuredElapsed,
                    samples.get(),
                    telemetry.rttMillis,
                    telemetry.rttVariationMillis,
                    telemetry.totalRetransmissions
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

    private class PhaseTelemetry {
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
                    if (app.has("ElapsedTime")) appElapsedMillis = app.optLong("ElapsedTime").div(1000).coerceAtLeast(0)
                }
                val tcp = root.optJSONObject("TCPInfo") ?: return
                if (tcp.has("RTT")) rttMillis = tcp.optLong("RTT").div(1000).coerceAtLeast(0)
                if (tcp.has("RTTVar")) rttVariationMillis = tcp.optLong("RTTVar").div(1000).coerceAtLeast(0)
                if (tcp.has("TotalRetrans")) totalRetransmissions = tcp.optLong("TotalRetrans").coerceAtLeast(0)
            }
        }
    }

    private fun NetworkSnapshot.summary(): String = buildString {
        append("type=$type, validated=$validated, captive=$captivePortal, metered=$metered, roaming=$roaming, vpn=$vpn")
        append(", estimated=${estimatedDownstreamMbps}/${estimatedUpstreamMbps} Mbps")
        wifiSignalDbm?.let { append(", wifiRssi=$it dBm") }
        if (interfaceName.isNotBlank()) append(", interface=$interfaceName")
        append(", dnsServers=${dnsServers.size}, privateDns=$privateDnsActive")
    }
}
