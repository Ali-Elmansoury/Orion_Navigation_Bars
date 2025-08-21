package com.ities45.orion_navigation_bars

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.ities45.orion_navigation_bars.databinding.CustomToolbarBinding
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

class CustomToolbar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: CustomToolbarBinding =
        CustomToolbarBinding.inflate(LayoutInflater.from(context), this, true)
    private var wifiIconClickListener: (() -> Unit)? = null
    private var bluetoothIconClickListener: (() -> Unit)? = null
    private var drowsinessIconClickListener: (() -> Unit)? = null
    private var settingsIconClickListener: (() -> Unit)? = null

    init {
        // Set up click listeners for icons with scale animation
        binding.wifiIcon.setOnClickListener {
            animateIcon(it)
            wifiIconClickListener?.invoke()
        }
        binding.bluetoothIcon.setOnClickListener {
            animateIcon(it)
            bluetoothIconClickListener?.invoke()
        }
        binding.drowsinessIcon.setOnClickListener {
            animateIcon(it)
            drowsinessIconClickListener?.invoke()
        }
        binding.settingsIcon.setOnClickListener {
            animateIcon(it)
            settingsIconClickListener?.invoke()
        }

        // Start updating time
        updateTime()
    }

    // Function to update time display
//    private fun updateTime() {
//        val currentTime: String
//
//        // Use the modern DateTimeFormatter for API 26+ (Android 8.0 Oreo)
//        val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
//        currentTime = LocalTime.now().format(formatter)
//
//        binding.timeText.text = currentTime
//
//        // Update time every minute
//        postDelayed({ updateTime() }, 60000)
//    }

    private fun updateTime() {
        val currentTime: String

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
            currentTime = LocalTime.now().format(formatter)
        } else {
            val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
            currentTime = sdf.format(Date())
        }

        // Apply modern styling to the time text
        val styledTime = SpannableString(currentTime)

        // Make the AM/PM part smaller
        val amPmIndex = currentTime.indexOf(" ")
        if (amPmIndex > 0) {
            styledTime.setSpan(
                RelativeSizeSpan(0.7f),
                amPmIndex,
                currentTime.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Optionally change color of AM/PM
            styledTime.setSpan(
                ForegroundColorSpan(Color.parseColor("#CCFFFFFF")), // Slightly transparent white
                amPmIndex,
                currentTime.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        binding.timeText.text = styledTime

        // Update time every minute
        postDelayed({ updateTime() }, 60000)
    }

    // Animation for icon click
    private fun animateIcon(view: android.view.View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.2f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.2f, 1f)
        scaleX.duration = 200
        scaleY.duration = 200
        scaleX.start()
        scaleY.start()
    }

    // Set custom icons
    fun setWifiIcon(resourceId: Int) {
        binding.wifiIcon.setImageResource(resourceId)
    }

    fun setBluetoothIcon(resourceId: Int) {
        binding.bluetoothIcon.setImageResource(resourceId)
    }

    fun setDrowsinessIcon(resourceId: Int) {
        binding.drowsinessIcon.setImageResource(resourceId)
    }

    fun setSettingsIcon(resourceId: Int) {
        binding.settingsIcon.setImageResource(resourceId)
    }

    // Set click listeners
    fun setWifiIconClickListener(listener: () -> Unit) {
        wifiIconClickListener = listener
    }

    fun setBluetoothIconClickListener(listener: () -> Unit) {
        bluetoothIconClickListener = listener
    }

    fun setDrowsinessIconClickListener(listener: () -> Unit) {
        drowsinessIconClickListener = listener
    }

    fun setSettingsIconClickListener(listener: () -> Unit) {
        settingsIconClickListener = listener
    }
}