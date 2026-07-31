package com.andre.speedtest

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import java.util.UUID

val Context.speedTestSettings by preferencesDataStore("speed_test_settings")

data class ServerInfo(
    val machine: String,
    val city: String,
    val country: String,
    val downloadUrl: String,
    val uploadUrl: String
)

data class PhaseMeasurement(
    val megabitsPerSecond: Double,
    val bytesTransferred: Long,
    val durationMillis: Long,
    val sampleCount: Int,
    val serverRttMillis: Long? = null,
    val serverRttVariationMillis: Long? = null,
    val totalRetransmissions: Long? = null,
    val clientMegabitsPerSecond: Double? = null,
    val serverMegabitsPerSecond: Double? = null
)

enum class EvidenceStatus { PASS, WARN, FAIL }

data class InvestigationEvidence(
    val method: String,
    val status: EvidenceStatus,
    val value: String,
    val detail: String,
    val fallbackUsed: Boolean = false
)

data class InvestigationReport(
    val confidence: String,
    val summary: String,
    val fallbackCount: Int,
    val contradictionCount: Int,
    val evidence: List<InvestigationEvidence>
) {
    fun hasPassed(method: String): Boolean =
        evidence.any { it.method == method && it.status == EvidenceStatus.PASS }
}

data class NetworkSnapshot(
    val type: String,
    val metered: Boolean,
    val roaming: Boolean,
    val vpn: Boolean,
    val validated: Boolean,
    val captivePortal: Boolean,
    val estimatedDownstreamMbps: Int,
    val estimatedUpstreamMbps: Int,
    val interfaceName: String,
    val dnsServers: List<String>,
    val privateDnsActive: Boolean,
    val wifiSignalDbm: Int? = null,
    val wifiRxLinkMbps: Int? = null,
    val wifiTxLinkMbps: Int? = null,
    val wifiFrequencyMhz: Int? = null,
    val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    val android: String = "Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}",
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
)

data class TestDiagnostic(
    val stage: String,
    val message: String,
    val locateStatus: String = "",
    val serverMachine: String = "",
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val elapsedMillis: Long = 0,
    val rawDetails: String = ""
)

enum class LogLevel { INFO, WARN, ERROR }

data class LiveLogEntry(
    val timestampMillis: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val source: String,
    val message: String
)

sealed interface SpeedTestEvent {
    data object LocatingServer : SpeedTestEvent
    data class Stage(val name: String) : SpeedTestEvent
    data class ServerSelected(val server: ServerInfo) : SpeedTestEvent
    data class DownloadProgress(val mbps: Double, val bytes: Long, val elapsedMillis: Long) : SpeedTestEvent
    data class UploadProgress(val mbps: Double, val bytes: Long, val elapsedMillis: Long) : SpeedTestEvent
    data class Log(val entry: LiveLogEntry) : SpeedTestEvent
    data class Completed(
        val download: PhaseMeasurement,
        val upload: PhaseMeasurement,
        val latencyMillis: Long,
        val loadedLatencyMillis: Long,
        val jitterMillis: Long,
        val probeFailures: Int,
        val probeAttempts: Int,
        val server: ServerInfo,
        val evaluation: ConnectionEvaluation,
        val diagnostic: TestDiagnostic
    ) : SpeedTestEvent
    data class DiagnosticCompleted(
        val latencyMillis: Long,
        val jitterMillis: Long,
        val probeFailures: Int,
        val probeAttempts: Int,
        val evaluation: ConnectionEvaluation,
        val diagnostic: TestDiagnostic
    ) : SpeedTestEvent
    data class Failed(
        val diagnostic: TestDiagnostic,
        val evaluation: ConnectionEvaluation? = null
    ) : SpeedTestEvent
    data object Cancelled : SpeedTestEvent
}

interface SpeedTestEngine {
    fun startTest(): Flow<SpeedTestEvent>
    fun cancel()
}

enum class EvaluationMode(val label: String) {
    INDEPENDENT("Independent diagnosis"),
    FULL_SPEED("Full speed test")
}

@Entity(tableName = "speed_results")
data class SpeedResultEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMillis: Long,
    val jitterMillis: Long,
    val serverMachine: String,
    val serverCity: String,
    val serverCountry: String,
    val networkType: String,
    val networkMetered: Boolean,
    val networkRoaming: Boolean,
    val networkVpn: Boolean,
    val qualityLabel: String,
    val diagnosticJson: String,
    val syncStatus: String = "local_only",
    val remoteId: String? = null
)

@Dao
interface SpeedResultDao {
    @Query("SELECT * FROM speed_results ORDER BY timestampMillis DESC")
    fun observeAll(): Flow<List<SpeedResultEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: SpeedResultEntity)

    @Query("DELETE FROM speed_results WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM speed_results")
    suspend fun clear()
}

@Database(entities = [SpeedResultEntity::class], version = 1, exportSchema = true)
abstract class SpeedDatabase : RoomDatabase() {
    abstract fun results(): SpeedResultDao

    companion object {
        @Volatile private var instance: SpeedDatabase? = null

        fun get(context: Context): SpeedDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SpeedDatabase::class.java,
                    "speed_test.db"
                ).build().also { instance = it }
            }
    }
}

class SettingsStore(private val context: Context) {
    private val darkTheme = booleanPreferencesKey("dark_theme")
    private val consentAccepted = booleanPreferencesKey("mlab_consent_accepted")

    val darkThemeFlow: Flow<Boolean> = context.speedTestSettings.data.map { it[darkTheme] ?: true }
    val consentAcceptedFlow: Flow<Boolean> = context.speedTestSettings.data.map { it[consentAccepted] ?: false }

    suspend fun setDarkTheme(enabled: Boolean) {
        context.speedTestSettings.edit { it[darkTheme] = enabled }
    }

    suspend fun setConsentAccepted(accepted: Boolean) {
        context.speedTestSettings.edit { it[consentAccepted] = accepted }
    }
}

object SpeedMath {
    fun mbps(bytes: Long, millis: Long): Double {
        if (bytes <= 0 || millis <= 0) return 0.0
        return (bytes * 8.0) / millis / 1000.0
    }

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2
        } else {
            sorted[middle]
        }
    }

    fun jitter(values: List<Long>): Long {
        if (values.size < 2) return 0
        return median(values.zipWithNext { first, second -> kotlin.math.abs(second - first) })
    }

    fun formatMbps(value: Double): String = String.format(Locale.US, "%.1f", value)
}

object NetworkInspector {
    fun snapshot(context: Context): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link = network?.let { cm.getLinkProperties(it) }
        val type = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        return NetworkSnapshot(
            type = type,
            metered = cm.isActiveNetworkMetered,
            roaming = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            estimatedDownstreamMbps = (caps?.linkDownstreamBandwidthKbps ?: 0) / 1000,
            estimatedUpstreamMbps = (caps?.linkUpstreamBandwidthKbps ?: 0) / 1000,
            interfaceName = link?.interfaceName.orEmpty(),
            dnsServers = link?.dnsServers?.map { it.hostAddress ?: it.toString() }.orEmpty(),
            privateDnsActive = link.isPrivateDnsActiveCompat(),
            wifiSignalDbm = caps.wifiInfo()?.rssi?.takeIf { it in -126..-1 },
            wifiRxLinkMbps = caps.wifiInfo()?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.rxLinkSpeedMbps else it.linkSpeed
            }?.takeIf { it >= 0 },
            wifiTxLinkMbps = caps.wifiInfo()?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.txLinkSpeedMbps else it.linkSpeed
            }?.takeIf { it >= 0 },
            wifiFrequencyMhz = caps.wifiInfo()?.frequency?.takeIf { it > 0 }
        )
    }

    private fun NetworkCapabilities?.wifiInfo(): WifiInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) this?.transportInfo as? WifiInfo else null

    private fun LinkProperties?.isPrivateDnsActiveCompat(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && this?.isPrivateDnsActive == true
}
