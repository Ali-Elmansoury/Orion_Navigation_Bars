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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.ities45.orion_navigation_bars.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSION = 1
        private const val REQUEST_ENABLE_BLUETOOTH = 2
        private const val TAG = "Bluetooth"
    }

    val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()

    // --- track whether we should start discovery right after permissions are granted
    private var pendingStartDiscovery: Boolean = false

    // Interface to abstract BluetoothDevice and mock devices
    interface BluetoothDeviceWrapper {
        val name: String
        val address: String
        val bondState: Int
        fun createBond(): Boolean
    }

    // Wrapper for real BluetoothDevice (NO reflection; takes Context directly)
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
                // Safe fallback when permission isn't granted yet
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

    // Mock device for emulator (kept but not used)
    private class MockBluetoothDevice(
        override val name: String,
        override val address: String
    ) : BluetoothDeviceWrapper {
        override val bondState: Int = BluetoothDevice.BOND_NONE
        override fun createBond(): Boolean = true // Simulate successful pairing
    }

    private lateinit var binding: ActivityMainBinding
    private val discoveredDevices = mutableListOf<BluetoothDeviceWrapper>()
    private val deviceNames = mutableListOf<String>()
    private var receiver: BroadcastReceiver? = null
    private var scanningDialog: AlertDialog? = null
    private var scanningAdapter: ArrayAdapter<String>? = null

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

            Log.d(TAG, "Bluetooth adapter: $adapter, enabled: ${adapter?.isEnabled}")

            setWifiIconClickListener {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                showSnackbar("WiFi icon clicked")
            }

            setBluetoothIconClickListener {
                // unified flow: request perms → auto-start discovery when granted
                requestBluetoothPermission(startDiscoveryAfterGrant = true)
            }

            setDrowsinessIconClickListener {
                showSnackbar("Drowsiness icon clicked")
            }

            setSettingsIconClickListener {
                showSnackbar("Settings icon clicked")
            }
        }

        // Register Bluetooth state change receiver
        registerStateReceiver()
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

    // ---- Discovery receiver (ACTION_FOUND / STARTED / FINISHED)
    private fun registerReceiver() {
        if (receiver != null) return // Prevent duplicate registration

        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        Log.d(TAG, "ACTION_DISCOVERY_STARTED")
                        runOnUiThread { scanningDialog?.setTitle("Scanning…") }
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && !discoveredDevices.any { it.address == device.address }) {
                            val wrapper = RealBluetoothDevice(this@MainActivity, device)
                            discoveredDevices.add(wrapper)
                            deviceNames.add("${wrapper.name} - ${wrapper.address}")
                            Log.d(TAG, "Device found: ${wrapper.name} (${wrapper.address})")
                            runOnUiThread { scanningAdapter?.notifyDataSetChanged() }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "ACTION_DISCOVERY_FINISHED")
                        runOnUiThread {
                            if (deviceNames.isEmpty()) scanningDialog?.setMessage("No devices found.")
                            scanningDialog?.setTitle("Scan finished")
                        }
                    }
                }
            }
        }

        receiver = newReceiver
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(newReceiver, filter)
    }

    private fun registerStateReceiver() {
        val stateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_OFF -> {
                            showSnackbar("Bluetooth turned off.")
                            scanningDialog?.dismiss()
                            unregisterReceiverSafely()
                        }
                    }
                }
            }
        }
        registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun unregisterReceiverSafely() {
        receiver?.let {
            try { unregisterReceiver(it) } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        receiver = null
    }

    // ---- Location helpers (many AAOS builds require Location to be ON for discovery)
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
            AlertDialog.Builder(this)
                .setTitle("Enable Location")
                .setMessage("Location must be ON for Bluetooth discovery on this device.")
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
    private fun showScanningDialog() {
        scanningAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)

        val builder = AlertDialog.Builder(this)
            .setTitle("Available Bluetooth Devices")
            .setAdapter(scanningAdapter) { _, which ->
                val selectedDevice = discoveredDevices.getOrNull(which)
                scanningDialog?.dismiss()
                if (selectedDevice != null) {
                    initiatePairing(selectedDevice)
                } else {
                    showSnackbar("Invalid device selected.")
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                cancelDiscoverySafe()
                scanningDialog?.dismiss()
            }

        scanningDialog = builder.create()
        scanningDialog?.setOnDismissListener {
            cancelDiscoverySafe()
            scanningDialog = null
        }
        scanningDialog?.show()

        scanningAdapter?.notifyDataSetChanged()
    }

    private fun cancelDiscoverySafe() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
        ) {
            bluetoothAdapter.cancelDiscovery()
        }
    }

    /**
     * Request runtime permissions. If [startDiscoveryAfterGrant] is true,
     * discovery will automatically start once permissions are granted.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermission(startDiscoveryAfterGrant: Boolean = false) {
        pendingStartDiscovery = startDiscoveryAfterGrant

        val needed = mutableListOf<String>()
        // Android 12+ dangerous BT perms
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        // Location often required by OEMs for discovery visibility
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) ||
                shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("Bluetooth and Location permissions are needed to scan for and connect to devices.")
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

        // Already granted
        if (pendingStartDiscovery) {
            startBluetoothDiscovery()
            pendingStartDiscovery = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        Log.d(TAG, "Starting discovery…")
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "Bluetooth adapter not available")
            showSnackbar("Bluetooth adapter not available.")
            return
        }

        // Pre-flight diagnostics
        val hasScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val hasConnect = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val btOn = bluetoothAdapter.isEnabled
        Log.d(TAG, "preflight -> btOn=$btOn, hasScan=$hasScan, hasConnect=$hasConnect, hasFine=$hasFine, discovering=${bluetoothAdapter.isDiscovering}")

        if (!btOn) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
            return
        }

        // Many AAOS builds require Location to be ON for discovery
        if (promptEnableLocationIfNeeded()) {
            return
        }

        if (!hasScan) {
            Log.w(TAG, "Missing BLUETOOTH_SCAN permission -> requesting and will resume discovery")
            requestBluetoothPermission(startDiscoveryAfterGrant = true)
            return
        }

        // Prepare UI + data
        discoveredDevices.clear()
        deviceNames.clear()

        // Add paired devices first (shows names even before discovery finds anything)
        val pairedDevices = bluetoothAdapter.bondedDevices
        pairedDevices?.forEach { device ->
            val wrapper = RealBluetoothDevice(this, device)
            discoveredDevices.add(wrapper)
            deviceNames.add("${wrapper.name} (Paired) - ${wrapper.address}")
        }

        // Register broadcast receiver for discovery callbacks
        unregisterReceiverSafely()
        registerReceiver()

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        val success = bluetoothAdapter.startDiscovery()
        Log.d(TAG, "startDiscovery() -> $success")
        if (success) {
            showScanningDialog()
        } else {
            val reason = buildString {
                append("Failed to start discovery. ")
                if (!hasScan) append("Missing BLUETOOTH_SCAN. ")
                if (!btOn) append("Bluetooth OFF. ")
                if (!isLocationEnabled()) append("Location OFF. ")
            }
            Log.e(TAG, reason)
            showSnackbar(reason)
        }
    }

    private fun showDeviceDialog(names: Array<String>, devices: List<BluetoothDeviceWrapper>) {
        AlertDialog.Builder(this)
            .setTitle("Select Paired Device")
            .setItems(names) { _, which ->
                val selectedDevice = devices.getOrNull(which)
                if (selectedDevice != null) {
                    initiatePairing(selectedDevice)
                } else {
                    showSnackbar("Invalid device selected.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun initiatePairing(device: BluetoothDeviceWrapper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device is RealBluetoothDevice &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            // Pairing path: request just perms, no auto-scan on grant
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

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingStartDiscovery) {
                    startBluetoothDiscovery()
                    pendingStartDiscovery = false
                } else {
                    showSnackbar("Permissions granted.")
                }
            } else {
                pendingStartDiscovery = false
                showSnackbar("Bluetooth permissions denied.")
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
                showSnackbar("Bluetooth not enabled.")
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
        unregisterReceiverSafely()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiverSafely()
    }
}
