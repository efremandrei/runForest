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
    val findings: List<EvaluationFinding>
)

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
        retransmissions: Long = 0
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

        if (network.captivePortal) {
            add(FindingSeverity.CRITICAL, 80, "Sign-in portal detected", "Android marked this network as captive.", "Open the Wi-Fi sign-in page, complete it, then evaluate again.")
        } else if (!network.validated) {
            add(FindingSeverity.CRITICAL, 60, "Internet access is not validated", "Android could not confirm public internet access.", "Check airplane mode, mobile data, Wi-Fi login, DNS, and router uplink.")
        }

        network.wifiSignalDbm?.let { rssi ->
            when {
                rssi <= -75 -> add(FindingSeverity.WARNING, 18, "Weak Wi-Fi signal", "Wi-Fi RSSI is $rssi dBm.", "Move closer to the access point or reduce walls and interference, then retest.")
                rssi <= -67 -> add(FindingSeverity.INFO, 7, "Moderate Wi-Fi signal", "Wi-Fi RSSI is $rssi dBm.", "Compare beside the router to separate Wi-Fi limits from provider limits.")
            }
        }

        when {
            idleLatencyMillis > 200 -> add(FindingSeverity.WARNING, 25, "Very high idle latency", "Median server connection time is $idleLatencyMillis ms.", "Retest without VPN and compare Wi-Fi with mobile data; a distant route or busy link may be involved.")
            idleLatencyMillis > 100 -> add(FindingSeverity.WARNING, 15, "High idle latency", "Median server connection time is $idleLatencyMillis ms.", "Compare another network and a test near the router.")
            idleLatencyMillis > 60 -> add(FindingSeverity.INFO, 7, "Elevated idle latency", "Median server connection time is $idleLatencyMillis ms.", "Latency-sensitive calls and games may benefit from a closer or less congested path.")
        }

        when {
            jitterMillis > 50 -> add(FindingSeverity.WARNING, 20, "Unstable latency", "Connection-time jitter is $jitterMillis ms.", "Pause other traffic and retest; on Wi-Fi, compare close to the router.")
            jitterMillis > 25 -> add(FindingSeverity.WARNING, 12, "Variable latency", "Connection-time jitter is $jitterMillis ms.", "Check for Wi-Fi interference or competing traffic.")
            jitterMillis > 10 -> add(FindingSeverity.INFO, 5, "Some latency variation", "Connection-time jitter is $jitterMillis ms.", "Repeat the evaluation at another time to see whether it persists.")
        }

        val loadIncrease = (loadedLatencyMillis - idleLatencyMillis).coerceAtLeast(0)
        when {
            loadIncrease > 200 -> add(FindingSeverity.WARNING, 25, "Severe queueing under load", "Loaded connection time rose by $loadIncrease ms.", "Enable SQM/QoS on the router or cap heavy transfers slightly below line rate.")
            loadIncrease > 100 -> add(FindingSeverity.WARNING, 18, "Queueing under load", "Loaded connection time rose by $loadIncrease ms.", "Check router SQM/QoS and retest with other downloads paused.")
            loadIncrease > 50 -> add(FindingSeverity.INFO, 10, "Noticeable latency under load", "Loaded connection time rose by $loadIncrease ms.", "Interactive apps may improve with router queue management.")
        }

        if (probeAttempts > 0 && probeFailures > 0) {
            val percent = probeFailures * 100 / probeAttempts
            add(
                if (percent >= 25) FindingSeverity.WARNING else FindingSeverity.INFO,
                if (percent >= 25) 25 else 10,
                "Connection probes failed",
                "$probeFailures of $probeAttempts TCP connection probes failed ($percent%).",
                "Repeat the test; persistent failures suggest an unstable path, filtering, or severe congestion."
            )
        }

        when {
            downloadMbps < 5 -> add(FindingSeverity.WARNING, 25, "Low download capacity", "Measured download is ${SpeedMath.formatMbps(downloadMbps)} Mbps.", "Compare beside the router and on another device before contacting the provider.")
            downloadMbps < 25 -> add(FindingSeverity.WARNING, 12, "Limited download capacity", "Measured download is ${SpeedMath.formatMbps(downloadMbps)} Mbps.", "Check competing traffic and compare Ethernet or a near-router Wi-Fi test.")
            downloadMbps < 50 -> add(FindingSeverity.INFO, 5, "Moderate download capacity", "Measured download is ${SpeedMath.formatMbps(downloadMbps)} Mbps.", "This may be adequate, but compare it with your plan and workload.")
        }

        when {
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

        score = score.coerceIn(0, 100)
        val verdict = when {
            score >= 90 -> "Excellent"
            score >= 75 -> "Good"
            score >= 60 -> "Fair"
            else -> "Poor"
        }
        val primary = findings.firstOrNull { it.severity != FindingSeverity.INFO }
        val summary = primary?.title ?: "No major issue detected in this snapshot"
        return ConnectionEvaluation(score, verdict, summary, findings)
    }
}
