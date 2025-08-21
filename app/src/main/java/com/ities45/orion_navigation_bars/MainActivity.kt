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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.annotation.RequiresPermission
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

    // Interface to abstract BluetoothDevice and mock devices
    interface BluetoothDeviceWrapper {
        val name: String
        val address: String
        val bondState: Int
        fun createBond(): Boolean
    }

    // Wrapper for real BluetoothDevice
    private class RealBluetoothDevice(private val device: BluetoothDevice) : BluetoothDeviceWrapper {
        override val name: String
            get() = if (ActivityCompat.checkSelfPermission(
                    device.context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                device.name ?: device.address
            } else {
                device.address
            }
        override val address: String
            get() = device.address
        override val bondState: Int
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            get() = device.bondState
        override fun createBond(): Boolean {
            return if (ActivityCompat.checkSelfPermission(
                    device.context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                device.createBond()
            } else {
                false
            }
        }

        // Helper to get context from activity; assumes MainActivity is the context
        private val BluetoothDevice.context: Context
            get() = (this as? android.bluetooth.BluetoothDevice)?.let {
                MainActivity::class.java.getDeclaredField("this$0").let { field ->
                    field.isAccessible = true
                    field.get(this) as Context
                }
            } ?: throw IllegalStateException("Context not available")
    }

    // Mock device for emulator
    private class MockBluetoothDevice(
        override val name: String,
        override val address: String
    ) : BluetoothDeviceWrapper {
        override val bondState: Int = BluetoothDevice.BOND_NONE
        override fun createBond(): Boolean {
            return true // Simulate successful pairing
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val discoveredDevices = mutableListOf<BluetoothDeviceWrapper>()
    private val deviceNames = mutableListOf<String>()
    private var receiver: BroadcastReceiver? = null
    private var scanningDialog: AlertDialog? = null
    private var scanningAdapter: ArrayAdapter<String>? = null

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
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                showSnackbar("WiFi icon clicked")
            }

            setBluetoothIconClickListener {
                requestBluetoothPermission()
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
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
                Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
                Build.MODEL.contains("Android SDK built for x86", ignoreCase = true)
    }

    private fun registerReceiver() {
        if (receiver != null) return // Prevent duplicate registration

        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && !discoveredDevices.any { it.address == device.address }) {
                            val wrapper = RealBluetoothDevice(device)
                            discoveredDevices.add(wrapper)
                            deviceNames.add(wrapper.name)
                            Log.d(TAG, "Device found: ${wrapper.name}")
                            runOnUiThread {
                                scanningAdapter?.notifyDataSetChanged()
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        runOnUiThread {
                            scanningDialog?.setMessage("Scan finished.")
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (scanningDialog?.isShowing == true && deviceNames.isEmpty()) {
                                    scanningDialog?.setMessage("No devices found.")
                                }
                            }, 2000)
                        }
                    }
                }
            }
        }

        receiver = newReceiver
        val filter = IntentFilter().apply {
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
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        receiver = null
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
                    showSnackbar("Selected: ${deviceNames[which]} (Simulated)")
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

        if (isEmulator()) {
            scanningAdapter?.notifyDataSetChanged()
        }
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

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (needed.isNotEmpty()) {
                if (shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                    AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Bluetooth permissions are needed to scan for and connect to devices.")
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
        }
        showPairedDevices()
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        Log.d(TAG, "Starting discovery...")
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Log.e(TAG, "Bluetooth adapter not available")
            showSnackbar("Bluetooth adapter not available.")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "Bluetooth not enabled, requesting enable")
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
            return
        }

        if (isEmulator()) {
            Log.d(TAG, "Running in emulator, simulating devices")
            discoveredDevices.clear()
            deviceNames.clear()
            listOf(
                MockBluetoothDevice("Simulated OBD-II Device", "00:11:22:33:44:55"),
                MockBluetoothDevice("Simulated Phone", "00:11:22:33:44:56"),
                MockBluetoothDevice("Simulated Speaker", "00:11:22:33:44:57")
            ).forEach { mockDevice ->
                discoveredDevices.add(mockDevice)
                deviceNames.add(mockDevice.name)
            }
            showScanningDialog()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Missing BLUETOOTH_SCAN permission")
                showSnackbar("Missing Bluetooth scan permission.")
                requestBluetoothPermission()
                return
            }

            discoveredDevices.clear()
            deviceNames.clear()
            unregisterReceiverSafely()
            registerReceiver()

            if (bluetoothAdapter.isDiscovering) {
                Log.d(TAG, "Cancelling ongoing discovery")
                bluetoothAdapter.cancelDiscovery()
            }
            if (bluetoothAdapter.startDiscovery()) {
                Log.d(TAG, "Discovery started successfully")
                showScanningDialog()
            } else {
                Log.e(TAG, "Failed to start discovery")
                showSnackbar("Failed to start Bluetooth discovery.")
            }
        }
    }

    private fun showPairedDevices() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermission()
            return
        }

        val pairedDevices = bluetoothAdapter.bondedDevices
        if (pairedDevices.isNullOrEmpty()) {
            startBluetoothDiscovery()
        } else {
            val wrappers = pairedDevices.map { RealBluetoothDevice(it) }
            val names = wrappers.map { it.name }.toTypedArray()
            showDeviceDialog(names, wrappers)
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
            requestBluetoothPermission()
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showPairedDevices()
            } else {
                showSnackbar("Bluetooth permissions denied.")
            }
        }
    }

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