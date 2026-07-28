package com.andre.speedtest

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

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
        val started = System.currentTimeMillis()
        val network = NetworkInspector.snapshot(context)
        if (!network.validated) {
            send(SpeedTestEvent.Failed(TestDiagnostic("network", "No validated internet connection is available.")))
            close()
            return@channelFlow
        }

        try {
            send(SpeedTestEvent.LocatingServer)
            val locateStarted = System.currentTimeMillis()
            val server = withContext(Dispatchers.IO) { locateServer() }
            val latency = System.currentTimeMillis() - locateStarted
            send(SpeedTestEvent.ServerSelected(server))

            val download = runPhase(
                url = server.downloadUrl,
                upload = false,
                scope = this,
                onProgress = { mbps, bytes, elapsed ->
                    trySend(SpeedTestEvent.DownloadProgress(mbps, bytes, elapsed))
                }
            )
            delay(800)
            val upload = runPhase(
                url = server.uploadUrl,
                upload = true,
                scope = this,
                onProgress = { mbps, bytes, elapsed ->
                    trySend(SpeedTestEvent.UploadProgress(mbps, bytes, elapsed))
                }
            )
            val jitter = abs(download.durationMillis - upload.durationMillis).coerceAtMost(999)
            send(
                SpeedTestEvent.Completed(
                    download = download,
                    upload = upload,
                    latencyMillis = latency,
                    jitterMillis = jitter,
                    server = server,
                    diagnostic = TestDiagnostic(
                        stage = "completed",
                        message = "M-Lab NDT7 test completed.",
                        locateStatus = "ok",
                        serverMachine = server.machine,
                        downloadBytes = download.bytesTransferred,
                        uploadBytes = upload.bytesTransferred,
                        elapsedMillis = System.currentTimeMillis() - started,
                        rawDetails = "network=${network.type},metered=${network.metered},vpn=${network.vpn}"
                    )
                )
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            send(SpeedTestEvent.Cancelled)
        } catch (error: Throwable) {
            send(
                SpeedTestEvent.Failed(
                    TestDiagnostic(
                        stage = "failed",
                        message = error.message ?: error.javaClass.simpleName,
                        elapsedMillis = System.currentTimeMillis() - started,
                        rawDetails = error.stackTraceToString().take(2000)
                    )
                )
            )
        } finally {
            activeSocket?.cancel()
            activeCall?.cancel()
        }

        awaitClose {
            activeSocket?.cancel()
            activeCall?.cancel()
        }
    }

    override fun cancel() {
        activeSocket?.cancel()
        activeCall?.cancel()
    }

    private suspend fun locateServer(): ServerInfo = CompletableDeferred<String>().also { deferred ->
        val request = Request.Builder()
            .url("$locateUrl?client_name=andrei-speed-test")
            .header("User-Agent", "AndreiSpeedTest/${BuildConfig.VERSION_NAME}")
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
                    if (body.isBlank()) {
                        deferred.completeExceptionally(IOException("Locate API returned an empty body."))
                    } else {
                        deferred.complete(body)
                    }
                }
            }
        })
    }.await().let { body ->
        val root = JSONObject(body)
        val results = root.getJSONArray("results")
        if (results.length() == 0) error("Locate API returned no NDT7 servers.")
        val result = results.getJSONObject(0)
        val urls = result.getJSONObject("urls")
        val download = findUrl(urls, "download")
        val upload = findUrl(urls, "upload")
        val location = result.optJSONObject("location")
        ServerInfo(
            machine = result.optString("machine", "unknown"),
            city = location?.optString("city", "Unknown").orEmpty().ifBlank { "Unknown" },
            country = location?.optString("country", "Unknown").orEmpty().ifBlank { "Unknown" },
            downloadUrl = download,
            uploadUrl = upload
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
        onProgress: (Double, Long, Long) -> Unit
    ): PhaseMeasurement {
        val result = CompletableDeferred<PhaseMeasurement>()
        val transferred = AtomicLong(0)
        val done = AtomicBoolean(false)
        val started = System.currentTimeMillis()
        val payload = ByteString.of(*ByteArray(16 * 1024) { 7 })
        var samples = 0
        var senderJob: Job? = null
        var closerJob: Job? = null

        fun finish() {
            if (done.compareAndSet(false, true)) {
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                result.complete(PhaseMeasurement(SpeedMath.mbps(transferred.get(), elapsed), transferred.get(), elapsed, samples))
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AndreiSpeedTest/${BuildConfig.VERSION_NAME}")
            .header("Sec-WebSocket-Protocol", "net.measurementlab.ndt.v7")
            .build()

        activeSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (upload) {
                    senderJob = scope.launch(Dispatchers.IO) {
                        val deadline = System.currentTimeMillis() + 10_000
                        while (System.currentTimeMillis() < deadline && !done.get()) {
                            if (!webSocket.send(payload)) break
                            val total = transferred.addAndGet(payload.size.toLong())
                            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                            samples += 1
                            onProgress(SpeedMath.mbps(total, elapsed), total, elapsed)
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
                samples += 1
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!upload) {
                    val total = transferred.addAndGet(bytes.size.toLong())
                    val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
                    samples += 1
                    onProgress(SpeedMath.mbps(total, elapsed), total, elapsed)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                finish()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                finish()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done.compareAndSet(false, true)) {
                    result.completeExceptionally(IOException("NDT7 ${if (upload) "upload" else "download"} failed: ${t.message}", t))
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
