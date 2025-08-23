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
import android.location.LocationManager
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import java.lang.reflect.Method

class BluetoothManager(private val activity: AppCompatActivity) {

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSION = 1
        private const val REQUEST_ENABLE_BLUETOOTH = 3
        private const val TAG = "BluetoothManager"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val discoveredDevices = mutableListOf<BluetoothDeviceWrapper>()
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var bluetoothDialog: androidx.appcompat.app.AlertDialog? = null
    private var pendingStartDiscovery: Boolean = false

    interface BluetoothDeviceWrapper {
        val name: String
        val address: String
        val bondState: Int
        fun createBond(): Boolean
        fun removeBond(): Boolean
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

        override fun removeBond(): Boolean {
            return if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                false
            } else {
                try {
                    val method: Method = device.javaClass.getMethod("removeBond")
                    method.invoke(device) as Boolean
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove bond: ${e.message}")
                    false
                }
            }
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

    init {
        registerBluetoothStateReceiver()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun startBluetoothDiscovery() {
        requestBluetoothPermission(startDiscoveryAfterGrant = true)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun startDiscovery() {
        if (bluetoothAdapter == null) {
            showSnackbar("Bluetooth not available")
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
            return
        }

        if (isLocationEnabled()) {
            showLocationPrompt()
            return
        }

        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            requestBluetoothPermission(startDiscoveryAfterGrant = true)
            return
        }

        discoveredDevices.clear()
        bluetoothAdapter.bondedDevices?.forEach { device ->
            val wrapper = RealBluetoothDevice(activity, device)
            discoveredDevices.add(wrapper)
        }

        unregisterReceiver()
        registerReceiver()

        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }

        if (bluetoothAdapter.startDiscovery()) {
            showBluetoothDialog()
        } else {
            showSnackbar("Failed to start Bluetooth discovery")
        }
    }

    private fun showBluetoothDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_device_list, null)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.device_list)
        val progressIndicator = dialogView.findViewById<CircularProgressIndicator>(R.id.progress_indicator)
        val emptyMessage = dialogView.findViewById<TextView>(R.id.empty_message)

        recyclerView.layoutManager = LinearLayoutManager(activity)
        recyclerView.adapter = DeviceAdapter(discoveredDevices) { device ->
            bluetoothDialog?.dismiss()
            handleDeviceClick(device)
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

        bluetoothDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Bluetooth Devices")
            .setView(dialogView)
            .setNegativeButton("Cancel") { _, _ ->
                cancelDiscovery()
                bluetoothDialog?.dismiss()
            }
            .create()

        bluetoothDialog?.setOnDismissListener {
            cancelDiscovery()
            bluetoothDialog = null
        }
        bluetoothDialog?.show()
    }

    private fun registerReceiver() {
        if (bluetoothReceiver != null) return

        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                        Log.d(TAG, "Bluetooth discovery started")
                        activity.runOnUiThread { bluetoothDialog?.findViewById<CircularProgressIndicator>(R.id.progress_indicator)?.isVisible = true }
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device != null && !discoveredDevices.any { it.address == device.address }) {
                            val wrapper = RealBluetoothDevice(activity, device)
                            discoveredDevices.add(wrapper)
                            activity.runOnUiThread {
                                bluetoothDialog?.findViewById<RecyclerView>(R.id.device_list)?.adapter?.notifyDataSetChanged()
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        Log.d(TAG, "Bluetooth discovery finished")
                        activity.runOnUiThread {
                            bluetoothDialog?.findViewById<CircularProgressIndicator>(R.id.progress_indicator)?.isVisible = false
                            if (discoveredDevices.isEmpty()) {
                                bluetoothDialog?.findViewById<TextView>(R.id.empty_message)?.isVisible = true
                            }
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        if (device != null) {
                            activity.runOnUiThread {
                                val index = discoveredDevices.indexOfFirst { it.address == device.address }
                                if (index != -1) {
                                    discoveredDevices.removeAt(index)
                                    if (bondState == BluetoothDevice.BOND_BONDED) {
                                        discoveredDevices.add(index, RealBluetoothDevice(activity, device))
                                    }
                                    bluetoothDialog?.findViewById<RecyclerView>(R.id.device_list)?.adapter?.notifyDataSetChanged()
                                }
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
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        activity.registerReceiver(newReceiver, filter)
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
                            unregisterReceiver()
                        }
                    }
                }
            }
        }
        activity.registerReceiver(stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    private fun unregisterReceiver() {
        bluetoothReceiver?.let {
            try { activity.unregisterReceiver(it) } catch (e: Exception) {
                Log.e(TAG, "Error unregistering Bluetooth receiver: ${e.message}")
            }
        }
        bluetoothReceiver = null
    }

    private fun cancelDiscovery() {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.cancelDiscovery()
        }
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
            .setMessage("Location must be enabled for Bluetooth scanning")
            .setPositiveButton("Open Settings") { _, _ ->
                activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothPermission(startDiscoveryAfterGrant: Boolean = false) {
        pendingStartDiscovery = startDiscoveryAfterGrant

        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            if (activity.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Permission Required")
                    .setMessage("Bluetooth and Location permissions are needed to scan for and manage devices")
                    .setPositiveButton("OK") { _, _ ->
                        ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                ActivityCompat.requestPermissions(activity, needed.toTypedArray(), REQUEST_BLUETOOTH_PERMISSION)
            }
            return
        }

        if (pendingStartDiscovery) {
            startDiscovery()
            pendingStartDiscovery = false
        }
    }

    private fun handleDeviceClick(device: BluetoothDeviceWrapper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            device is RealBluetoothDevice &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestBluetoothPermission(startDiscoveryAfterGrant = false)
            return
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            if (device.removeBond()) {
                showSnackbar("Unpairing ${device.name}")
            } else {
                showSnackbar("Failed to unpair ${device.name}")
            }
        } else if (device.bondState == BluetoothDevice.BOND_NONE) {
            if (device.createBond()) {
                showSnackbar("Pairing with ${device.name}")
            } else {
                showSnackbar("Failed to initiate pairing with ${device.name}")
            }
        } else {
            showSnackbar("Device ${device.name} is in pairing process")
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(activity.findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun handlePermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                if (pendingStartDiscovery) {
                    startDiscovery()
                    pendingStartDiscovery = false
                } else {
                    showSnackbar("Bluetooth permissions granted")
                }
            } else {
                pendingStartDiscovery = false
                showSnackbar("Bluetooth permissions denied")
            }
            return true
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun handleActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == AppCompatActivity.RESULT_OK) {
                startDiscovery()
            } else {
                showSnackbar("Bluetooth not enabled")
            }
            return true
        }
        return false
    }

    fun cleanup() {
        unregisterReceiver()
        bluetoothDialog?.dismiss()
    }
}