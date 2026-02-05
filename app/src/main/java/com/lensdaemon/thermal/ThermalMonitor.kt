package com.lensdaemon.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile

/**
 * Thermal monitoring service
 *
 * Reads CPU and battery temperatures from Android system sources:
 * - /sys/class/thermal/ thermal zones
 * - BatteryManager for battery temperature
 * - PowerManager for thermal status (Android 10+)
 *
 * Features:
 * - Continuous temperature polling
 * - Multiple thermal zone detection
 * - Battery state monitoring
 * - StateFlow for reactive updates
 */
class ThermalMonitor(
    private val context: Context,
    private val config: ThermalConfig = ThermalConfig.DEFAULT
) {
    companion object {
        private const val TAG = "ThermalMonitor"

        // Common thermal zone paths on Android devices
        private val THERMAL_ZONE_PATHS = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp",
            "/sys/class/thermal/thermal_zone3/temp",
            "/sys/class/thermal/thermal_zone4/temp",
            "/sys/class/thermal/thermal_zone5/temp",
            "/sys/class/thermal/thermal_zone6/temp",
            "/sys/class/thermal/thermal_zone7/temp"
        )

        // CPU thermal zone type names (varies by device)
        private val CPU_ZONE_TYPES = listOf(
            "cpu", "cpu-0-0", "cpu-0-1", "cpu-1-0", "cpu-1-1",
            "tsens_tz_sensor", "mtktscpu", "soc_thermal",
            "cpu_thermal", "cpuss-0", "cpuss-1", "cpuss"
        )

        // GPU thermal zone type names
        private val GPU_ZONE_TYPES = listOf(
            "gpu", "gpu-0", "gpu-1", "gpuss", "gpuss-0",
            "g3d", "mali"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    // System services
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // Discovered thermal zones
    private val thermalZones = mutableMapOf<String, ThermalZone>()

    // Current temperatures
    private val _cpuTemperature = MutableStateFlow(0f)
    val cpuTemperature: StateFlow<Float> = _cpuTemperature.asStateFlow()

    private val _batteryTemperature = MutableStateFlow(0f)
    val batteryTemperature: StateFlow<Float> = _batteryTemperature.asStateFlow()

    private val _gpuTemperature = MutableStateFlow(0f)
    val gpuTemperature: StateFlow<Float> = _gpuTemperature.asStateFlow()

    // Battery state
    private val _batteryPercent = MutableStateFlow(0)
    val batteryPercent: StateFlow<Int> = _batteryPercent.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _batteryStatus = MutableStateFlow(BatteryManager.BATTERY_STATUS_UNKNOWN)
    val batteryStatus: StateFlow<Int> = _batteryStatus.asStateFlow()

    // Android thermal status (API 29+)
    private val _thermalStatus = MutableStateFlow(PowerManager.THERMAL_STATUS_NONE)
    val thermalStatus: StateFlow<Int> = _thermalStatus.asStateFlow()

    // Listeners
    private val listeners = mutableListOf<ThermalMonitorListener>()

    // Battery broadcast receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateBatteryState(it) }
        }
    }

    // Thermal status callback (API 29+)
    private var thermalStatusListener: Any? = null

    init {
        discoverThermalZones()
    }

    /**
     * Thermal zone information
     */
    data class ThermalZone(
        val path: String,
        val type: String,
        val source: ThermalSource
    )

    /**
     * Listener interface for temperature updates
     */
    interface ThermalMonitorListener {
        fun onTemperatureUpdate(cpu: Float, battery: Float, gpu: Float)
        fun onBatteryStateChanged(percent: Int, isCharging: Boolean)
        fun onThermalStatusChanged(status: Int)
    }

    /**
     * Start temperature monitoring
     */
    fun start() {
        if (monitorJob?.isActive == true) {
            Timber.tag(TAG).d("Monitor already running")
            return
        }

        Timber.tag(TAG).i("Starting thermal monitor")

        // Register battery receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryIntent = context.registerReceiver(batteryReceiver, filter)
        batteryIntent?.let { updateBatteryState(it) }

        // Register thermal status listener (API 29+)
        registerThermalStatusListener()

        // Start polling
        monitorJob = scope.launch {
            while (isActive) {
                try {
                    pollTemperatures()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error polling temperatures")
                }
                delay(config.monitorIntervalMs)
            }
        }
    }

    /**
     * Stop temperature monitoring
     */
    fun stop() {
        Timber.tag(TAG).i("Stopping thermal monitor")

        monitorJob?.cancel()
        monitorJob = null

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }

        unregisterThermalStatusListener()
    }

    /**
     * Release resources
     */
    fun release() {
        stop()
        scope.cancel()
        listeners.clear()
    }

    /**
     * Add temperature listener
     */
    fun addListener(listener: ThermalMonitorListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove temperature listener
     */
    fun removeListener(listener: ThermalMonitorListener) {
        listeners.remove(listener)
    }

    /**
     * Get current temperature reading for a source
     */
    fun getTemperature(source: ThermalSource): Float {
        return when (source) {
            ThermalSource.CPU -> _cpuTemperature.value
            ThermalSource.BATTERY -> _batteryTemperature.value
            ThermalSource.GPU -> _gpuTemperature.value
            else -> 0f
        }
    }

    /**
     * Get all current readings
     */
    fun getAllReadings(): List<TemperatureReading> {
        val now = System.currentTimeMillis()
        return listOf(
            TemperatureReading(ThermalSource.CPU, _cpuTemperature.value, now),
            TemperatureReading(ThermalSource.BATTERY, _batteryTemperature.value, now),
            TemperatureReading(ThermalSource.GPU, _gpuTemperature.value, now)
        )
    }

    /**
     * Get battery information
     */
    fun getBatteryInfo(): BatteryInfo {
        return BatteryInfo(
            percent = _batteryPercent.value,
            temperatureC = _batteryTemperature.value,
            isCharging = _isCharging.value,
            status = _batteryStatus.value
        )
    }

    /**
     * Discover available thermal zones
     */
    private fun discoverThermalZones() {
        thermalZones.clear()

        val thermalDir = File("/sys/class/thermal")
        if (!thermalDir.exists()) {
            Timber.tag(TAG).w("Thermal directory not found")
            return
        }

        thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zoneDir ->
            try {
                val tempFile = File(zoneDir, "temp")
                val typeFile = File(zoneDir, "type")

                if (tempFile.exists() && tempFile.canRead()) {
                    val type = if (typeFile.exists() && typeFile.canRead()) {
                        typeFile.readText().trim().lowercase()
                    } else {
                        "unknown"
                    }

                    val source = classifyThermalZone(type)
                    thermalZones[zoneDir.name] = ThermalZone(
                        path = tempFile.absolutePath,
                        type = type,
                        source = source
                    )

                    Timber.tag(TAG).d("Found thermal zone: ${zoneDir.name} ($type) -> $source")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error reading thermal zone ${zoneDir.name}: ${e.message}")
            }
        }

        Timber.tag(TAG).i("Discovered ${thermalZones.size} thermal zones")
    }

    /**
     * Classify thermal zone type
     */
    private fun classifyThermalZone(type: String): ThermalSource {
        return when {
            CPU_ZONE_TYPES.any { type.contains(it) } -> ThermalSource.CPU
            GPU_ZONE_TYPES.any { type.contains(it) } -> ThermalSource.GPU
            type.contains("battery") -> ThermalSource.BATTERY
            type.contains("skin") -> ThermalSource.SKIN
            else -> ThermalSource.UNKNOWN
        }
    }

    /**
     * Poll temperatures from all sources
     */
    private fun pollTemperatures() {
        // Read from thermal zones
        val cpuTemps = mutableListOf<Float>()
        val gpuTemps = mutableListOf<Float>()

        thermalZones.values.forEach { zone ->
            val temp = readTemperatureFile(zone.path)
            if (temp != null) {
                when (zone.source) {
                    ThermalSource.CPU -> cpuTemps.add(temp)
                    ThermalSource.GPU -> gpuTemps.add(temp)
                    else -> {}
                }
            }
        }

        // Use highest CPU temperature
        val cpuTemp = cpuTemps.maxOrNull() ?: readFallbackCpuTemp() ?: 0f
        _cpuTemperature.value = cpuTemp

        // Use highest GPU temperature
        val gpuTemp = gpuTemps.maxOrNull() ?: 0f
        _gpuTemperature.value = gpuTemp

        // Notify listeners
        val batteryTemp = _batteryTemperature.value
        listeners.forEach { it.onTemperatureUpdate(cpuTemp, batteryTemp, gpuTemp) }
    }

    /**
     * Read temperature from a sysfs file
     */
    private fun readTemperatureFile(path: String): Float? {
        return try {
            RandomAccessFile(path, "r").use { file ->
                val value = file.readLine()?.trim()?.toIntOrNull() ?: return null
                // Temperature is usually in millidegrees Celsius
                if (value > 1000) {
                    value / 1000f
                } else {
                    value.toFloat()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fallback CPU temperature reading
     */
    private fun readFallbackCpuTemp(): Float? {
        // Try common paths in order
        THERMAL_ZONE_PATHS.forEach { path ->
            readTemperatureFile(path)?.let { return it }
        }
        return null
    }

    /**
     * Update battery state from intent
     */
    private fun updateBatteryState(intent: Intent) {
        // Temperature (in tenths of degrees Celsius)
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempTenths / 10f
        _batteryTemperature.value = tempC

        // Charge level
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = (level * 100) / scale
        _batteryPercent.value = percent

        // Charging status
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        _batteryStatus.value = status

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        _isCharging.value = isCharging

        // Notify listeners
        listeners.forEach { it.onBatteryStateChanged(percent, isCharging) }
    }

    /**
     * Register thermal status listener (API 29+)
     */
    private fun registerThermalStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val listener = PowerManager.OnThermalStatusChangedListener { status ->
                    _thermalStatus.value = status
                    listeners.forEach { it.onThermalStatusChanged(status) }
                }
                powerManager.addThermalStatusListener(context.mainExecutor, listener)
                thermalStatusListener = listener
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to register thermal status listener: ${e.message}")
            }
        }
    }

    /**
     * Unregister thermal status listener
     */
    private fun unregisterThermalStatusListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusListener?.let { listener ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    powerManager.removeThermalStatusListener(
                        listener as PowerManager.OnThermalStatusChangedListener
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w("Failed to unregister thermal status listener: ${e.message}")
                }
            }
        }
        thermalStatusListener = null
    }

    /**
     * Battery information data class
     */
    data class BatteryInfo(
        val percent: Int,
        val temperatureC: Float,
        val isCharging: Boolean,
        val status: Int
    ) {
        val statusText: String
            get() = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                else -> "Unknown"
            }
    }

    /**
     * Get Android thermal status text
     */
    fun getThermalStatusText(): String {
        return when (_thermalStatus.value) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    }
}
