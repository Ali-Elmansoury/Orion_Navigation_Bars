package com.ities45.orion_navigation_bars

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import android.net.wifi.ScanResult

class WifiManager(private val activity: AppCompatActivity) {

    companion object {
        private const val REQUEST_WIFI_PERMISSION = 2
        private const val TAG = "WifiManager"
    }

    private val wifiManager: WifiManager by lazy { activity.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val wifiNetworks = mutableListOf<WifiNetworkWrapper>()
    private var wifiReceiver: BroadcastReceiver? = null
    private var wifiDialog: androidx.appcompat.app.AlertDialog? = null
    private var pendingStartWifiScan: Boolean = false

    data class WifiNetworkWrapper(
        val ssid: String,
        val bssid: String,
        val level: Int,
        val capabilities: String
    )

    private class WifiAdapter(
        private val networks: List<WifiNetworkWrapper>,
        private val currentSsid: String?,
        private val onClick: (WifiNetworkWrapper) -> Unit
    ) : RecyclerView.Adapter<WifiAdapter.ViewHolder>() {
        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(android.R.id.text1)
            val statusText: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val network = networks[position]
            holder.nameText.text = network.ssid
            val isConnected = network.ssid == currentSsid
            holder.statusText.text = buildString {
                append("Signal: ${network.level}dBm")
                if (network.capabilities.contains("PSK") || network.capabilities.contains("WEP")) append(" (Secured)")
                if (isConnected) append(" (Connected)")
            }
            holder.view.setOnClickListener { onClick(network) }
        }

        override fun getItemCount(): Int = networks.size
    }

    init {
        registerWifiStateReceiver()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun startWifiScan() {
        requestWifiPermission(startScanAfterGrant = true)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startScan() {
        if (!wifiManager.isWifiEnabled) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Enable Wi-Fi")
                .setMessage("Wi-Fi must be enabled to scan for networks")
                .setPositiveButton("Enable") { _, _ ->
                    wifiManager.isWifiEnabled = true
                    startScan()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (isLocationEnabled()) {
            showLocationPrompt()
            return
        }

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestWifiPermission(startScanAfterGrant = true)
            return
        }

        wifiNetworks.clear()
        unregisterReceiver()
        registerReceiver()

        if (wifiManager.startScan()) {
            showWifiDialog()
        } else {
            showSnackbar("Failed to start Wi-Fi scan")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showWifiDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.device_list)
        val progressIndicator = dialogView.findViewById<CircularProgressIndicator>(R.id.progress_indicator)
        val emptyMessage = dialogView.findViewById<TextView>(R.id.empty_message)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = WifiAdapter(wifiNetworks, getCurrentSsid()) { network ->
            wifiDialog?.dismiss()
            handleNetworkClick(network)
        }

        recyclerView.apply {
            alpha = 0f
            scaleY = 0.8f
            ViewCompat.animate(this)
                .alpha(1f)
                .scaleY(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
        }

        wifiDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Wi-Fi Networks")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ ->
                unregisterReceiver()
                wifiDialog?.dismiss()
            }
            .create()

        wifiDialog?.setOnDismissListener {
            unregisterReceiver()
            wifiDialog = null
        }
        wifiDialog?.show()
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentSsid(): String? {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val wifiInfo = wifiManager.connectionInfo
        return wifiInfo?.ssid?.trim('"')
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun handleNetworkClick(network: WifiNetworkWrapper) {
        val currentSsid = getCurrentSsid()
        if (network.ssid == currentSsid) {
            disconnectFromNetwork()
        } else {
            connectToWifi(network)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromNetwork() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            wifiManager.disconnect()
            showSnackbar("Disconnected from Wi-Fi")
        } else {
            showSnackbar("Permission denied to disconnect Wi-Fi")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun connectToWifi(network: WifiNetworkWrapper) {
        if (network.capabilities.contains("PSK") || network.capabilities.contains("WEP")) {
            showPasswordDialog(network)
        } else {
            connectToOpenNetwork(network)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun connectToOpenNetwork(network: WifiNetworkWrapper) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestWifiPermission()
            return
        }

        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"${network.ssid}\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }

        val networkId = wifiManager.addNetwork(wifiConfig)
        if (networkId != -1) {
            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
            showSnackbar("Connecting to ${network.ssid}")
        } else {
            showSnackbar("Failed to connect to ${network.ssid}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showPasswordDialog(network: WifiNetworkWrapper) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_wifi_password, null)
        val passwordInput = dialogView.findViewById<TextInputEditText>(R.id.password_input)
        val errorMessage = dialogView.findViewById<TextView>(R.id.error_message)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Connect to ${network.ssid}")
            .setView(dialogView)
            .setPositiveButton("Connect") { _, _ ->
                val password = passwordInput.text.toString()
                if (password.length < 8 && network.capabilities.contains("PSK")) {
                    errorMessage.text = "Password must be at least 8 characters"
                    errorMessage.isVisible = true
                } else {
                    connectToSecuredNetwork(network, password)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun connectToSecuredNetwork(network: WifiNetworkWrapper, password: String) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            requestWifiPermission()
            return
        }

        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"${network.ssid}\""
            if (network.capabilities.contains("PSK")) {
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            } else if (network.capabilities.contains("WEP")) {
                wepKeys[0] = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                wepTxKeyIndex = 0
            }
        }

        val networkId = wifiManager.addNetwork(wifiConfig)
        if (networkId != -1) {
            wifiManager.disconnect()
            wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
            showSnackbar("Connecting to ${network.ssid}")
        } else {
            showSnackbar("Failed to connect to ${network.ssid}")
        }
    }

    private fun registerReceiver() {
        if (wifiReceiver != null) return

        val newReceiver = object : BroadcastReceiver() {
            @RequiresApi(Build.VERSION_CODES.S)
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            wifiManager.scanResults.forEach { result ->
                                if (!wifiNetworks.any { it.bssid == result.BSSID }) {
                                    wifiNetworks.add(WifiNetworkWrapper(result.SSID, result.BSSID, result.level, result.capabilities))
                                }
                            }
                            activity.runOnUiThread {
                                wifiDialog?.findViewById<CircularProgressIndicator>(R.id.progress_indicator)?.isVisible = false
                                wifiDialog?.findViewById<RecyclerView>(R.id.device_list)?.adapter = WifiAdapter(wifiNetworks, getCurrentSsid()) { network ->
                                    wifiDialog?.dismiss()
                                    handleNetworkClick(network)
                                }
                                if (wifiNetworks.isEmpty()) {
                                    wifiDialog?.findViewById<TextView>(R.id.empty_message)?.isVisible = true
                                }
                            }
                        }
                    }
                }
            }
        }

        wifiReceiver = newReceiver
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        activity.registerReceiver(newReceiver, filter)
    }

    private fun registerWifiStateReceiver() {
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                    when (state) {
                        WifiManager.WIFI_STATE_DISABLED -> {
                            showSnackbar("Wi-Fi turned off")
                            wifiDialog?.dismiss()
                            unregisterReceiver()
                        }
                    }
                }
            }
        }
        activity.registerReceiver(stateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
    }

    private fun unregisterReceiver() {
        wifiReceiver?.let {
            try { activity.unregisterReceiver(it) } catch (e: Exception) {
                Log.e(TAG, "Error unregistering Wi-Fi receiver: ${e.message}")
            }
        }
        wifiReceiver = null
    }

    private fun isLocationEnabled(): Boolean {
        val lm = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            !lm.isLocationEnabled
        } else {
            try {
                Settings.Secure.getInt(activity.contentResolver, Settings.Secure.LOCATION_MODE) == Settings.Secure.LOCATION_MODE_OFF
            } catch (e: Exception) { true }
        }
    }

    private fun showLocationPrompt() {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Enable Location")
            .setMessage("Location must be enabled for Wi-Fi scanning")
            .setPositiveButton("Open Settings") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestWifiPermission(startScanAfterGrant: Boolean = false) {
        pendingStartWifiScan = startScanAfterGrant

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_WIFI_STATE)
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CHANGE_WIFI_STATE)
        }

        if (needed.isNotEmpty()) {
            if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_WIFI_STATE) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.CHANGE_WIFI_STATE)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Permission Required")
                    .setMessage("Location and Wi-Fi permissions are needed to scan for and connect to networks")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_WIFI_PERMISSION)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_WIFI_PERMISSION)
            }
            return
        }

        if (pendingStartWifiScan) {
            startScan()
            pendingStartWifiScan = false
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun handlePermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == REQUEST_WIFI_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingStartWifiScan) {
                    startScan()
                    pendingStartWifiScan = false
                } else {
                    showSnackbar("Wi-Fi permissions granted")
                }
            } else {
                pendingStartWifiScan = false
                showSnackbar("Wi-Fi permissions denied")
            }
            return true
        }
        return false
    }

    fun cleanup() {
        unregisterReceiver()
        wifiDialog?.dismiss()
    }
}