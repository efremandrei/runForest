package com.andre.speedtest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentInvestigatorTest {
    private val targets = listOf(
        DiagnosticTarget("One", "one.test", "https://one.test/"),
        DiagnosticTarget("Two", "two.test", "https://two.test/"),
        DiagnosticTarget("Three", "three.test", "https://three.test/")
    )

    @Test
    fun oneProviderCanFailWithoutBlockingDiagnosis() = runTest {
        val client = FakeProbeClient(failures = setOf("One:DNS", "One:TCP", "One:HTTPS"))

        val result = IndependentInvestigator(client, targets).run(network(validated = true))

        assertEquals(27, result.probeAttempts)
        assertEquals(9, result.probeFailures)
        assertEquals("High", result.report.confidence)
        assertTrue(result.report.summary.contains("2/3 HTTPS target profiles"))
        assertEquals(27, client.calls.size)
    }

    @Test
    fun thrownProbeStillFallsThroughToEveryOtherMethod() = runTest {
        val client = FakeProbeClient(throws = setOf("One:DNS", "Two:HTTPS"))

        val result = IndependentInvestigator(client, targets).run(network(validated = true))

        assertEquals(27, client.calls.size)
        assertEquals(4, result.probeFailures)
        assertTrue(result.observations.any { it.target == "One" && it.method == "TCP" && it.success })
        assertTrue(result.observations.any { it.target == "Three" && it.method == "HTTPS" && it.success })
    }

    @Test
    fun platformDisagreementReducesConfidence() = runTest {
        val client = FakeProbeClient()

        val result = IndependentInvestigator(client, targets).run(network(validated = false))

        assertEquals("Medium", result.report.confidence)
        assertEquals(1, result.report.contradictionCount)
        assertTrue(result.report.evidence.any {
            it.method == "Availability cross-check" && it.status == EvidenceStatus.WARN
        })
    }

    @Test
    fun jitterUsesTemporalSamplesNotDestinationSpread() = runTest {
        val client = FakeProbeClient(
            latencies = mapOf(
                "One:TCP" to listOf(20, 22, 21, 23, 22),
                "Two:TCP" to listOf(80, 81, 82, 80, 81),
                "Three:TCP" to listOf(150, 151, 149, 150, 151)
            )
        )

        val result = IndependentInvestigator(client, targets).run(network(validated = true))

        assertEquals(81, result.latencyMillis)
        assertEquals(1, result.jitterMillis)
        assertEquals(128, result.destinationSpreadMillis)
        assertTrue(result.report.evidence.any { it.method == "Destination spread" })
    }

    @Test
    fun latencyOnlyEvaluationDoesNotInventThroughputOrQueueingProblems() {
        val report = InvestigationReport(
            confidence = "High",
            summary = "Three independent targets passed.",
            fallbackCount = 0,
            contradictionCount = 0,
            evidence = listOf(
                InvestigationEvidence("HTTPS One", EvidenceStatus.PASS, "HTTP 200", "ok")
            )
        )

        val result = ConnectionEvaluator.evaluate(
            network = network(validated = true),
            downloadMbps = 0.0,
            uploadMbps = 0.0,
            idleLatencyMillis = 30,
            loadedLatencyMillis = 30,
            jitterMillis = 4,
            probeFailures = 0,
            probeAttempts = 9,
            investigation = report,
            measurementCoverage = MeasurementCoverage.LATENCY_ONLY
        )

        assertTrue(result.findings.none { it.title.contains("capacity", ignoreCase = true) })
        assertTrue(result.findings.none { it.title.contains("load", ignoreCase = true) })
    }

    private fun network(validated: Boolean) = NetworkSnapshot(
        type = "Wi-Fi",
        metered = false,
        roaming = false,
        vpn = false,
        validated = validated,
        captivePortal = false,
        estimatedDownstreamMbps = 100,
        estimatedUpstreamMbps = 30,
        interfaceName = "wlan0",
        dnsServers = listOf("192.0.2.1"),
        privateDnsActive = true,
        wifiSignalDbm = -55,
        device = "Test device",
        android = "Android test",
        abi = "arm64-v8a"
    )
}

private class FakeProbeClient(
    private val failures: Set<String> = emptySet(),
    private val throws: Set<String> = emptySet(),
    private val latencies: Map<String, List<Long>> = emptyMap()
) : IndependentProbeClient {
    val calls = mutableListOf<String>()
    private val counters = mutableMapOf<String, Int>()

    override suspend fun dns(target: DiagnosticTarget) = outcome(target, "DNS", 8)
    override suspend fun tcp(target: DiagnosticTarget) = outcome(target, "TCP", 20)
    override suspend fun https(target: DiagnosticTarget) = outcome(target, "HTTPS", 35)
    override fun cancel() = Unit

    private fun outcome(target: DiagnosticTarget, method: String, latency: Long): ProbeObservation {
        val key = "${target.name}:$method"
        calls += key
        if (key in throws) error("$key synthetic exception")
        val success = key !in failures
        val scripted = latencies[key]
        val index = counters.getOrDefault(key, 0)
        counters[key] = index + 1
        val selectedLatency = scripted?.getOrElse(index) { scripted.last() } ?: latency
        return ProbeObservation(
            method = method,
            target = target.name,
            success = success,
            latencyMillis = selectedLatency.takeIf { success },
            value = if (success) "ok" else "failed",
            detail = "Synthetic $method outcome."
        )
    }
}
