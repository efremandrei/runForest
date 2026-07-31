package com.andre.speedtest

enum class FindingSeverity { CRITICAL, WARNING, INFO }

data class EvaluationFinding(
    val severity: FindingSeverity,
    val title: String,
    val evidence: String,
    val action: String
)

data class ConnectionEvaluation(
    val score: Int,
    val verdict: String,
    val summary: String,
    val findings: List<EvaluationFinding>,
    val confidence: String = "Standard",
    val evidenceSummary: String = "Single-run evidence"
)

enum class MeasurementCoverage {
    FULL,
    LATENCY_ONLY,
    AVAILABILITY_ONLY
}

object ConnectionEvaluator {
    fun evaluate(
        network: NetworkSnapshot,
        downloadMbps: Double,
        uploadMbps: Double,
        idleLatencyMillis: Long,
        loadedLatencyMillis: Long,
        jitterMillis: Long,
        probeFailures: Int,
        probeAttempts: Int,
        retransmissions: Long = 0,
        investigation: InvestigationReport? = null,
        measurementCoverage: MeasurementCoverage = MeasurementCoverage.FULL
    ): ConnectionEvaluation {
        val findings = mutableListOf<EvaluationFinding>()
        var score = 100

        fun add(
            severity: FindingSeverity,
            deduction: Int,
            title: String,
            evidence: String,
            action: String
        ) {
            score -= deduction
            findings += EvaluationFinding(severity, title, evidence, action)
        }

        val activeInternetConfirmed = investigation?.evidence?.any {
            it.status == EvidenceStatus.PASS &&
                (it.method.startsWith("HTTPS ") || it.method == "NDT7 download")
        } == true

        if (network.captivePortal) {
            add(FindingSeverity.CRITICAL, 80, "Sign-in portal detected", "Android marked this network as captive.", "Open the Wi-Fi sign-in page, complete it, then evaluate again.")
        } else if (!network.validated) {
            if (activeInternetConfirmed) {
                add(
                    FindingSeverity.WARNING,
                    10,
                    "Android validation disagrees with active checks",
                    "Android did not mark the network validated, but HTTPS or NDT7 succeeded.",
                    "Repeat the evaluation and check Private DNS or captive-portal detection if this disagreement persists."
                )
            } else {
                add(FindingSeverity.CRITICAL, 60, "Internet access is not validated", "Android could not confirm public internet access and active checks did not confirm it.", "Check airplane mode, mobile data, Wi-Fi login, DNS, and router uplink.")
            }
        } else if (investigation != null && !activeInternetConfirmed) {
            add(
                FindingSeverity.WARNING,
                18,
                "Active checks could not confirm internet access",
                "Android marked the network validated, but the app's HTTPS checks did not complete.",
                "Check Private DNS, VPN, firewall filtering, and the live log; then repeat the independent diagnosis."
            )
        }

        network.wifiSignalDbm?.let { rssi ->
            when {
                rssi <= -75 -> add(FindingSeverity.WARNING, 18, "Weak Wi-Fi signal", "Wi-Fi RSSI is $rssi dBm.", "Move closer to the access point or reduce walls and interference, then retest.")
                rssi <= -67 -> add(FindingSeverity.INFO, 7, "Moderate Wi-Fi signal", "Wi-Fi RSSI is $rssi dBm.", "Compare beside the router to separate Wi-Fi limits from provider limits.")
            }
        }

        if (measurementCoverage != MeasurementCoverage.AVAILABILITY_ONLY) when {
            idleLatencyMillis > 200 -> add(FindingSeverity.WARNING, 25, "Very high idle latency", "Median server connection time is $idleLatencyMillis ms.", "Retest without VPN and compare Wi-Fi with mobile data; a distant route or busy link may be involved.")
            idleLatencyMillis > 100 -> add(FindingSeverity.WARNING, 15, "High idle latency", "Median server connection time is $idleLatencyMillis ms.", "Compare another network and a test near the router.")
            idleLatencyMillis > 60 -> add(FindingSeverity.INFO, 7, "Elevated idle latency", "Median server connection time is $idleLatencyMillis ms.", "Latency-sensitive calls and games may benefit from a closer or less congested path.")
        }

        if (measurementCoverage != MeasurementCoverage.AVAILABILITY_ONLY) when {
            jitterMillis > 50 -> add(FindingSeverity.WARNING, 20, "Unstable latency", "Connection-time jitter is $jitterMillis ms.", "Pause other traffic and retest; on Wi-Fi, compare close to the router.")
            jitterMillis > 25 -> add(FindingSeverity.WARNING, 12, "Variable latency", "Connection-time jitter is $jitterMillis ms.", "Check for Wi-Fi interference or competing traffic.")
            jitterMillis > 10 -> add(FindingSeverity.INFO, 5, "Some latency variation", "Connection-time jitter is $jitterMillis ms.", "Repeat the evaluation at another time to see whether it persists.")
        }

        val loadIncrease = (loadedLatencyMillis - idleLatencyMillis).coerceAtLeast(0)
        if (measurementCoverage == MeasurementCoverage.FULL) when {
            loadIncrease > 200 -> add(FindingSeverity.WARNING, 25, "Severe queueing under load", "Loaded connection time rose by $loadIncrease ms.", "Enable SQM/QoS on the router or cap heavy transfers slightly below line rate.")
            loadIncrease > 100 -> add(FindingSeverity.WARNING, 18, "Queueing under load", "Loaded connection time rose by $loadIncrease ms.", "Check router SQM/QoS and retest with other downloads paused.")
            loadIncrease > 50 -> add(FindingSeverity.INFO, 10, "Noticeable latency under load", "Loaded connection time rose by $loadIncrease ms.", "Interactive apps may improve with router queue management.")
        }

        if (probeAttempts > 0 && probeFailures > 0) {
            val percent = probeFailures * 100 / probeAttempts
            if (investigation?.confidence == "High") {
                findings += EvaluationFinding(
                    FindingSeverity.INFO,
                    "Some endpoints were unavailable",
                    "$probeFailures of $probeAttempts active checks failed, but the independent quorum remained strong.",
                    "Inspect the live log to identify the affected operator; repeat later if the same endpoint keeps failing."
                )
            } else {
                add(
                    if (percent >= 50) FindingSeverity.WARNING else FindingSeverity.INFO,
                    if (percent >= 50) 20 else 8,
                    "Active checks failed",
                    "$probeFailures of $probeAttempts active connection checks failed ($percent%).",
                    "Repeat the diagnosis; persistent failures can indicate DNS, filtering, routing, or an endpoint-specific outage."
                )
            }
        }

        if (measurementCoverage == MeasurementCoverage.FULL) when {
            downloadMbps < 5 -> add(FindingSeverity.WARNING, 25, "Low download capacity", "Measured download is ${SpeedMath.formatMbps(downloadMbps)} Mbps.", "Compare beside the router and on another device before contacting the provider.")
            downloadMbps < 25 -> add(FindingSeverity.WARNING, 12, "Limited download capacity", "Measured download is ${SpeedMath.formatMbps(downloadMbps)} Mbps.", "Check competing traffic and compare Ethernet or a near-router Wi-Fi test.")
            downloadMbps < 50 -> add(FindingSeverity.INFO, 5, "Moderate download capacity", "Measured download is ${SpeedMath.formatMbps(downloadMbps)} Mbps.", "This may be adequate, but compare it with your plan and workload.")
        }

        if (measurementCoverage == MeasurementCoverage.FULL) when {
            uploadMbps < 1.5 -> add(FindingSeverity.WARNING, 20, "Low upload capacity", "Measured upload is ${SpeedMath.formatMbps(uploadMbps)} Mbps.", "Video calls and backups may struggle; pause uploads and retest.")
            uploadMbps < 5 -> add(FindingSeverity.WARNING, 10, "Limited upload capacity", "Measured upload is ${SpeedMath.formatMbps(uploadMbps)} Mbps.", "Check cloud backup and other upstream traffic.")
        }

        if (retransmissions > 0) {
            add(FindingSeverity.INFO, 5, "TCP retransmissions observed", "M-Lab reported $retransmissions retransmitted segments.", "Repeat the test; persistent retransmissions can accompany congestion or a noisy link.")
        }

        if (network.vpn) {
            findings += EvaluationFinding(FindingSeverity.INFO, "VPN is active", "The measured path includes a VPN.", "Retest without the VPN only if you want to isolate its effect.")
        }
        if (network.metered) {
            findings += EvaluationFinding(FindingSeverity.INFO, "Metered network", "Android marks this connection as metered.", "The evaluation transfers data; avoid repeated tests on a limited plan.")
        }

        investigation?.let { report ->
            findings += EvaluationFinding(
                if (report.confidence == "Low") FindingSeverity.WARNING else FindingSeverity.INFO,
                "Evidence confidence: ${report.confidence}",
                report.summary,
                if (report.confidence == "Low") "Repeat the evaluation; runForest used incomplete or conflicting evidence." else "Use the technical details or exported log to inspect each method."
            )
        }

        score = score.coerceIn(0, 100)
        val verdict = when {
            score >= 90 -> "Excellent"
            score >= 75 -> "Good"
            score >= 60 -> "Fair"
            else -> "Poor"
        }
        val primary = findings.firstOrNull { it.severity != FindingSeverity.INFO }
        val summary = primary?.title ?: "No major issue detected in this snapshot"
        return ConnectionEvaluation(
            score = score,
            verdict = verdict,
            summary = summary,
            findings = findings,
            confidence = investigation?.confidence ?: "Standard",
            evidenceSummary = investigation?.summary ?: "Single-run evidence"
        )
    }
}
