package com.andre.speedtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMathTest {
    @Test
    fun convertsBytesToMegabitsPerSecond() {
        assertEquals(8.0, SpeedMath.mbps(bytes = 1_000_000, millis = 1_000), 0.0001)
    }

    @Test
    fun handlesEmptyMeasurement() {
        assertEquals(0.0, SpeedMath.mbps(bytes = 0, millis = 1_000), 0.0001)
        assertEquals(0.0, SpeedMath.mbps(bytes = 1_000, millis = 0), 0.0001)
    }

    @Test
    fun calculatesMedianAndConnectionTimeJitter() {
        assertEquals(24, SpeedMath.median(listOf(20, 24, 40)))
        assertEquals(15, SpeedMath.median(listOf(5, 10, 20, 40)))
        assertEquals(4, SpeedMath.jitter(listOf(20, 24, 21, 29)))
    }

    @Test
    fun identifiesQueueingUnderLoad() {
        val result = ConnectionEvaluator.evaluate(
            network = network(),
            downloadMbps = 100.0,
            uploadMbps = 30.0,
            idleLatencyMillis = 20,
            loadedLatencyMillis = 240,
            jitterMillis = 3,
            probeFailures = 0,
            probeAttempts = 20
        )

        assertTrue(result.findings.any { it.title == "Severe queueing under load" })
        assertTrue(result.score < 90)
    }

    @Test
    fun reportsUnvalidatedNetworkAsCritical() {
        val result = ConnectionEvaluator.evaluate(
            network = network(validated = false),
            downloadMbps = 0.0,
            uploadMbps = 0.0,
            idleLatencyMillis = 0,
            loadedLatencyMillis = 0,
            jitterMillis = 0,
            probeFailures = 0,
            probeAttempts = 0
        )

        assertTrue(result.findings.any { it.severity == FindingSeverity.CRITICAL })
        assertEquals("Poor", result.verdict)
    }

    @Test
    fun crossChecksCanConfirmInternetWhenAndroidValidationIsInconclusive() {
        val report = InvestigationReport(
            confidence = "Medium",
            summary = "Active HTTPS succeeded after Android validation was inconclusive.",
            fallbackCount = 0,
            contradictionCount = 1,
            evidence = listOf(
                InvestigationEvidence(
                    method = "HTTPS reachability",
                    status = EvidenceStatus.PASS,
                    value = "HTTP 200",
                    detail = "M-Lab Locate succeeded."
                )
            )
        )

        val result = ConnectionEvaluator.evaluate(
            network = network(validated = false),
            downloadMbps = 100.0,
            uploadMbps = 30.0,
            idleLatencyMillis = 20,
            loadedLatencyMillis = 25,
            jitterMillis = 2,
            probeFailures = 0,
            probeAttempts = 10,
            investigation = report
        )

        assertTrue(result.findings.any { it.title == "Android validation disagrees with active checks" })
        assertTrue(result.findings.none { it.title == "Internet access is not validated" })
        assertEquals("Medium", result.confidence)
    }

    @Test
    fun failedInvestigationDoesNotInventZeroSpeedFindings() {
        val result = ConnectionEvaluator.evaluate(
            network = network(),
            downloadMbps = 0.0,
            uploadMbps = 0.0,
            idleLatencyMillis = 0,
            loadedLatencyMillis = 0,
            jitterMillis = 0,
            probeFailures = 0,
            probeAttempts = 0,
            investigation = InvestigationReport("Low", "All active methods failed.", 0, 0, emptyList()),
            measurementCoverage = MeasurementCoverage.AVAILABILITY_ONLY
        )

        assertTrue(result.findings.none { it.title.contains("capacity", ignoreCase = true) })
        assertTrue(result.findings.any { it.title == "Evidence confidence: Low" })
    }

    private fun network(validated: Boolean = true) = NetworkSnapshot(
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
