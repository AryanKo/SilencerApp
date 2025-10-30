
package com.Aryan.SilencerApp

// --- Imports ---

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.Aryan.SilencerApp.databinding.ActivityMainBinding



class MainActivity : AppCompatActivity() {
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission WAS granted.
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            // Permission was DENIED.
            Toast.makeText(this, "Microphone permission is required for the app to work.", Toast.LENGTH_LONG).show()

            // binding.switchMonitoring.isEnabled = false
        }
    }
    // --- Class Variables ---
    private lateinit var binding: ActivityMainBinding
    private var currentThreshold = 60

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)

        setContentView(binding.root)

        // --- 1. Setup Control Listeners (Making buttons respond) ---

        // Set the initial text for the slider to match its default progress (60)
        binding.tvThresholdValue.text = binding.seekThreshold.progress.toString()
        currentThreshold = binding.seekThreshold.progress

        // Set a listener for the Threshold Slider (seekThreshold)
        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Update the 'tvThresholdValue' text
                binding.tvThresholdValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) { }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Save the final value
                currentThreshold = seekBar?.progress ?: 60
            }
        })

        // Set a listener for the main Monitoring Switch (switchMonitoring)
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startMonitoringService()
            } else {
                stopMonitoringService()
            }
        }

        // Set a listener for Calibrate button (btnCalibrate)
        binding.btnCalibrate.setOnClickListener {
            Toast.makeText(this, "Calibration feature not implemented yet.", Toast.LENGTH_SHORT).show()
        }
        checkAndRequestAudioPermission()
        // --- 2. Set Initial UI State ---
        // When the app first starts, set it to the "OFF" state.
        stopMonitoringService()
    }

    /**
     * This function updates the UI to the "ON" state.
     */
    private fun startMonitoringService() {
        // --- Update UI for "ON" state ---

        // 1. Start the Lottie animation
        binding.lottieMicIcon.playAnimation()

        // 2. Make the dashboard card fully visible
        binding.cardDashboard.alpha = 1.0f

        // 3. Disable the slider and calibrate button
        binding.seekThreshold.isEnabled = false
        binding.btnCalibrate.isEnabled = false

        // 4. Set the mode text to "Normal"
        updateModeUI(isSilent = false)
    }

    /**
     * This function updates the UI to the "OFF" state.
     */
    private fun stopMonitoringService() {
        // --- Update UI for "OFF" state ---

        // 1. Stop the Lottie animation and rewind it
        binding.lottieMicIcon.pauseAnimation()
        binding.lottieMicIcon.progress = 0f

        // 2. Dim the dashboard card
        binding.cardDashboard.alpha = 0.7f

        // 3. Re-enable the controls
        binding.seekThreshold.isEnabled = true
        binding.btnCalibrate.isEnabled = true

        // 4. Reset the dashboard text and progress ring
        binding.tvDecibelValue.text = "-- dB"
        binding.progressDecibel.progress = 0

        // 5. Set status text to "Paused" and use the gray background
        binding.tvCurrentMode.text = "Monitoring Paused"
        binding.tvCurrentMode.background = ContextCompat.getDrawable(this, R.drawable.bg_mode_paused)
    }

    /**
     * Updates the decibel progress ring and text.
     */
    private fun updateDecibelUI(db: Int) {
        if (binding.switchMonitoring.isChecked) {
            binding.progressDecibel.setProgressCompat(db, true)
            binding.tvDecibelValue.text = "$db dB"
        }
    }

    /**
     * Updates the status text and color (Normal/Silent).
     */
    private fun updateModeUI(isSilent: Boolean) {
        if (!binding.switchMonitoring.isChecked) return

        if (isSilent) {
            // Set text and background to RED ("Silent")
            binding.tvCurrentMode.text = "Silent Mode"
            binding.tvCurrentMode.background = ContextCompat.getDrawable(this, R.drawable.bg_mode_silent)
        } else {
            // Set text and background to GREEN ("Normal")
            binding.tvCurrentMode.text = "Normal Mode"
            binding.tvCurrentMode.background = ContextCompat.getDrawable(this, R.drawable.bg_mode_normal)
        }
    }
    /**
     * Checks if the RECORD_AUDIO permission is granted.
     * If not, it launches the permission request pop-up.
     */
    private fun checkAndRequestAudioPermission() {
        when {
            // 1. Check if permission is already granted
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is already granted.
                Toast.makeText(this, "Microphone permission is already granted.", Toast.LENGTH_SHORT).show()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(this, "We need microphone access to detect noise levels.", Toast.LENGTH_LONG).show()
                // launch request
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            // 3. If no permission, ask for it
            else -> {
                // launch request
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}