package com.ities45.orion_navigation_bars

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.ities45.orion_navigation_bars.databinding.ActivityMainBinding
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSION = 1
        private const val REQUEST_WIFI_PERMISSION = 2
        private const val REQUEST_ENABLE_BLUETOOTH = 3
        private const val TAG = "MainActivity"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val wifiManager: WifiManager by lazy { getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private var pendingStartDiscovery: Boolean = false
    private var pendingStartWifiScan: Boolean = false

    interface BluetoothDeviceWrapper {
        val name: String
        val address: String
        val bondState: Int
        fun createBond(): Boolean
    }

    private class RealBluetoothDevice(
        private val context: Context,
        private val device: BluetoothDevice
    ) : BluetoothDeviceWrapper {
        override val name: String
            get() = if (
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                device.name ?: device.address
            } else {
                device.address
            }

        override val address: String
            get() = device.address

        override val bondState: Int
            get() = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                BluetoothDevice.BOND_NONE
            } else {
                device.bondState
            }

        override fun createBond(): Boolean {
            return if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                false
            } else {
                device.createBond()
            }
        }
    }

    data class WifiNetworkWrapper(
        val ssid: String,
        val bssid: String,
        val level: Int
    )

    private lateinit var binding: ActivityMainBinding
    private val discoveredDevices = mutableListOf<BluetoothDeviceWrapper>()
    private val wifiNetworks = mutableListOf<WifiNetworkWrapper>()
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var bluetoothDialog: androidx.appcompat.app.AlertDialog? = null
    private var wifiDialog: androidx.appcompat.app.AlertDialog? = null

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        with(binding.customToolbar) {
            setWifiIcon(R.drawable.wifi)
            setBluetoothIcon(R.drawable.bluetooth)
            setDrowsinessIcon(R.drawable.eye)
            setSettingsIcon(R.drawable.setting)

            setWifiIconClickListener {
                requestWifiPermission(startScanAfterGrant = true)
            }

            setBluetoothIconClickListener {
                requestBluetoothPermission(startDiscoveryAfterGrant = true)
            }

            setDrowsinessIconClickListener {
                showSnackbar("Drowsiness icon clicked")
            }

            setSettingsIconClickListener {
                showSnackbar("Settings icon clicked")
            }
        }

        registerBluetoothStateReceiver()
        registerWifiStateReceiver()
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.MODEL.contains("Android SDK built for x86", ignoreCase = true)
    }

    // Bluetooth Receiver
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return

        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        Log.d(TAG, "Bluetooth discovery started")
                        runOnUiThread { bluetoothDialog?.findViewById<CircularProgressIndicator>(R.id.progress_indicator)?.isVisible = true }
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && !discoveredDevices.any { it.address == device.address }) {
                            val wrapper = RealBluetoothDevice(this@MainActivity, device)
                            discoveredDevices.add(wrapper)
                            runOnUiThread {
                                bluetoothDialog?.findViewById<RecyclerView>(R.id.device_list)?.adapter?.notifyDataSetChanged()
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Bluetooth discovery finished")
                        runOnUiThread {
                            bluetoothDialog?.findViewById<CircularProgressIndicator>(R.id.progress_indicator)?.isVisible = false
                            if (discoveredDevices.isEmpty()) {
                                bluetoothDialog?.findViewById<TextView>(R.id.empty_message)?.isVisible = true
                            }
                        }
                    }
                }
            }
        }

        bluetoothReceiver = newReceiver
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(newReceiver, filter)
    }

    // Wi-Fi Receiver
    private fun registerWifiReceiver() {
        if (wifiReceiver != null) return

        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                            wifiManager.scanResults.forEach { result ->
                                if (!wifiNetworks.any { it.bssid == result.BSSID }) {
                                    wifiNetworks.add(WifiNetworkWrapper(result.SSID, result.BSSID, result.level))
                                }
                            }
                            runOnUiThread {
                                wifiDialog?.findViewById<CircularProgressIndicator>(R.id.progress_indicator)?.isVisible = false
                                wifiDialog?.findViewById<RecyclerView>(R.id.device_list)?.adapter?.notifyDataSetChanged()
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
        registerReceiver(newReceiver, filter)
    }

    private fun registerBluetoothStateReceiver() {
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            showSnackbar("Bluetooth turned off")
                            bluetoothDialog?.dismiss()
                            unregisterBluetoothReceiver()
                        }
                    }
                }
            }
        }
        registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
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
                            unregisterWifiReceiver()
                        }
                    }
                }
            }
        }
        registerReceiver(stateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
    }

    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {
                Log.e(TAG, "Error unregistering Bluetooth receiver: ${e.message}")
            }
        }
        bluetoothReceiver = null
    }

    private fun unregisterWifiReceiver() {
        wifiReceiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {
                Log.e(TAG, "Error unregistering Wi-Fi receiver: ${e.message}")
            }
        }
        wifiReceiver = null
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            try {
                Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
            } catch (e: Exception) { false }
        }
    }

    private fun promptEnableLocationIfNeeded(): Boolean {
        if (!isLocationEnabled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Enable Location")
                .setMessage("Location must be enabled for scanning")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return true
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun showBluetoothDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.device_list)
        val progressIndicator = dialogView.findViewById<CircularProgressIndicator>(R.id.progress_indicator)
        val emptyMessage = dialogView.findViewById<TextView>(R.id.empty_message)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = DeviceAdapter(discoveredDevices) { device ->
            bluetoothDialog?.dismiss()
            initiatePairing(device)
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

        bluetoothDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Bluetooth Devices")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ ->
                cancelBluetoothDiscovery()
                bluetoothDialog?.dismiss()
            }
            .create()

        bluetoothDialog?.setOnDismissListener {
            cancelBluetoothDiscovery()
            bluetoothDialog = null
        }
        bluetoothDialog?.show()
    }

    @SuppressLint("MissingPermission")
    private fun showWifiDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.device_list)
        val progressIndicator = dialogView.findViewById<CircularProgressIndicator>(R.id.progress_indicator)
        val emptyMessage = dialogView.findViewById<TextView>(R.id.empty_message)

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = WifiAdapter(wifiNetworks) { network ->
            wifiDialog?.dismiss()
            showSnackbar("Selected Wi-Fi: ${network.ssid}")
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

        wifiDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Wi-Fi Networks")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ ->
                unregisterWifiReceiver()
                wifiDialog?.dismiss()
            }
            .create()

        wifiDialog?.setOnDismissListener {
            unregisterWifiReceiver()
            wifiDialog = null
        }
        wifiDialog?.show()
    }

    private fun cancelBluetoothDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.cancelDiscovery()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermission(startDiscoveryAfterGrant: Boolean = false) {
        pendingStartDiscovery = startDiscoveryAfterGrant

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) ||
                shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Permission Required")
                    .setMessage("Bluetooth and Location permissions are needed to scan for devices")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
            }
            return
        }

        if (pendingStartDiscovery) {
            startBluetoothDiscovery()
            pendingStartDiscovery = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestWifiPermission(startScanAfterGrant: Boolean = false) {
        pendingStartWifiScan = startScanAfterGrant

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_WIFI_STATE)
        }

        if (needed.isNotEmpty()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_WIFI_STATE)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Permission Required")
                    .setMessage("Location and Wi-Fi permissions are needed to scan for networks")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_WIFI_PERMISSION)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_WIFI_PERMISSION)
            }
            return
        }

        if (pendingStartWifiScan) {
            startWifiScan()
            pendingStartWifiScan = false
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startBluetoothDiscovery() {
        if (bluetoothAdapter == null) {
            showSnackbar("Bluetooth not available")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
            return
        }

        if (promptEnableLocationIfNeeded()) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermission(startDiscoveryAfterGrant = true)
            return
        }

        discoveredDevices.clear()
        bluetoothAdapter.bondedDevices?.forEach { device ->
            val wrapper = RealBluetoothDevice(this, device)
            discoveredDevices.add(wrapper)
        }

        unregisterBluetoothReceiver()
        registerBluetoothReceiver()

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        if (bluetoothAdapter.startDiscovery()) {
            showBluetoothDialog()
        } else {
            showSnackbar("Failed to start Bluetooth discovery")
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startWifiScan() {
        if (!wifiManager.isWifiEnabled) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Enable Wi-Fi")
                .setMessage("Wi-Fi must be enabled to scan for networks")
                .setPositiveButton("Enable") { _, _ ->
                    wifiManager.isWifiEnabled = true
                    startWifiScan()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        if (promptEnableLocationIfNeeded()) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestWifiPermission(startScanAfterGrant = true)
            return
        }

        wifiNetworks.clear()
        unregisterWifiReceiver()
        registerWifiReceiver()

        if (wifiManager.startScan()) {
            showWifiDialog()
        } else {
            showSnackbar("Failed to start Wi-Fi scan")
        }
    }

    private fun initiatePairing(device: BluetoothDeviceWrapper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device is RealBluetoothDevice &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermission(startDiscoveryAfterGrant = false)
            return
        }

        if (device.bondState == BluetoothDevice.BOND_NONE) {
            if (device.createBond()) {
                showSnackbar("Pairing with ${device.name}")
            } else {
                showSnackbar("Failed to initiate pairing with ${device.name}")
            }
        } else {
            showSnackbar("Already paired: ${device.name}")
        }
    }

    private class DeviceAdapter(
        private val devices: List<BluetoothDeviceWrapper>,
        private val onClick: (BluetoothDeviceWrapper) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {
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
            val device = devices[position]
            holder.nameText.text = device.name
            holder.statusText.text = when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> "Paired"
                BluetoothDevice.BOND_BONDING -> "Pairing..."
                else -> device.address
            }
            holder.view.setOnClickListener { onClick(device) }
        }

        override fun getItemCount(): Int = devices.size
    }

    private class WifiAdapter(
        private val networks: List<WifiNetworkWrapper>,
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
            holder.statusText.text = "Signal: ${network.level}dBm"
            holder.view.setOnClickListener { onClick(network) }
        }

        override fun getItemCount(): Int = networks.size
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (pendingStartDiscovery) {
                        startBluetoothDiscovery()
                        pendingStartDiscovery = false
                    } else {
                        showSnackbar("Bluetooth permissions granted")
                    }
                } else {
                    pendingStartDiscovery = false
                    showSnackbar("Bluetooth permissions denied")
                }
            }
            REQUEST_WIFI_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    if (pendingStartWifiScan) {
                        startWifiScan()
                        pendingStartWifiScan = false
                    } else {
                        showSnackbar("Wi-Fi permissions granted")
                    }
                } else {
                    pendingStartWifiScan = false
                    showSnackbar("Wi-Fi permissions denied")
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                startBluetoothDiscovery()
            } else {
                showSnackbar("Bluetooth not enabled")
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        unregisterBluetoothReceiver()
        unregisterWifiReceiver()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        unregisterWifiReceiver()
    }
}