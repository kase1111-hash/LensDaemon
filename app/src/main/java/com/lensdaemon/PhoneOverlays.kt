package com.lensdaemon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lensdaemon.databinding.ActivityMainBinding
import com.lensdaemon.output.LocalNetwork
import com.lensdaemon.thermal.ThermalLevel
import com.lensdaemon.thermal.ThermalService
import com.lensdaemon.thermal.ThermalStatus
import com.lensdaemon.web.WebServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The phone's information overlays: the address to open the web dashboard at,
 * which setting the phone up starts from, and the CPU and battery temperatures,
 * coloured by how close they are to throttling.
 */
class PhoneOverlays(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding
) {
    private companion object {
        const val ADDRESS_REFRESH_MS = 3000L
    }

    private var addressJob: Job? = null
    private var thermalJob: Job? = null
    private var thermalBound = false

    private val thermalConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val governor = (service as? ThermalService.ThermalBinder)?.getService()?.getGovernor() ?: return
            thermalJob?.cancel()
            thermalJob = activity.lifecycleScope.launch {
                governor.status.collect { showThermal(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            thermalJob?.cancel()
            binding.thermalStatus.visibility = View.GONE
        }
    }

    /** Show the overlays while the activity is visible. */
    fun start() {
        addressJob?.cancel()
        addressJob = activity.lifecycleScope.launch {
            while (isActive) {
                // The address changes with the network (Wi-Fi, hotspot, USB tethering)
                val ip = withContext(Dispatchers.IO) { LocalNetwork.lanIpv4Address() }
                binding.tvDashboardUrl.text = if (ip != null) {
                    activity.getString(R.string.dashboard_address, "http://$ip:${WebServerService.DEFAULT_PORT}")
                } else {
                    activity.getString(R.string.dashboard_no_network)
                }
                delay(ADDRESS_REFRESH_MS)
            }
        }
        thermalBound = activity.bindService(
            Intent(activity, ThermalService::class.java), thermalConnection, Context.BIND_AUTO_CREATE
        )
    }

    fun stop() {
        addressJob?.cancel()
        thermalJob?.cancel()
        if (thermalBound) {
            activity.unbindService(thermalConnection)
            thermalBound = false
        }
    }

    private fun showThermal(status: ThermalStatus) {
        // No reading yet: monitoring starts a moment after the service
        if (status.cpuTemperatureC <= 0f && status.batteryTemperatureC <= 0f) return
        binding.thermalStatus.visibility = View.VISIBLE
        binding.tvCpuTemp.text = String.format(Locale.US, "CPU %.0f°C", status.cpuTemperatureC)
        binding.tvBatteryTemp.text = String.format(Locale.US, "Battery %.0f°C", status.batteryTemperatureC)
        binding.tvCpuTemp.setTextColor(ContextCompat.getColor(activity, colorFor(status.cpuLevel)))
        binding.tvBatteryTemp.setTextColor(ContextCompat.getColor(activity, colorFor(status.batteryLevel)))
    }

    private fun colorFor(level: ThermalLevel): Int = when (level) {
        ThermalLevel.NORMAL, ThermalLevel.ELEVATED -> R.color.thermal_safe
        ThermalLevel.WARNING -> R.color.thermal_warning
        ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY -> R.color.thermal_critical
    }
}
