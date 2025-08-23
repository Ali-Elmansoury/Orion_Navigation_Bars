package com.ities45.orion_navigation_bars

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.graphics.Color
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.ities45.orion_navigation_bars.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var wifiManager: WifiManager
    private var isDrowsinessIconEye = true // Track current icon state


    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetoothManager = BluetoothManager(this)
        wifiManager = WifiManager(this)

        with(binding.customToolbar) {
            setWifiIcon(R.drawable.wifi)
            setBluetoothIcon(R.drawable.bluetooth)
            setDrowsinessIcon(R.drawable.eye)
            setSettingsIcon(R.drawable.setting)

            setWifiIconClickListener {
                wifiManager.startWifiScan()
            }

            setBluetoothIconClickListener {
                bluetoothManager.startBluetoothDiscovery()
            }

            setDrowsinessIconClickListener {
                isDrowsinessIconEye = !isDrowsinessIconEye // Toggle state
                val newIcon = if (isDrowsinessIconEye) R.drawable.eye else R.drawable.drowsiness
                setDrowsinessIcon(newIcon)
            }

            setSettingsIconClickListener {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
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

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (bluetoothManager.handlePermissionsResult(requestCode, permissions, grantResults)) return
        if (wifiManager.handlePermissionsResult(requestCode, permissions, grantResults)) return
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (bluetoothManager.handleActivityResult(requestCode, resultCode)) return
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
        bluetoothManager.cleanup()
        wifiManager.cleanup()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothManager.cleanup()
        wifiManager.cleanup()
    }
}