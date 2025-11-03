
package com.Aryan.SilencerApp

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.Aryan.SilencerApp.databinding.ActivityMainBinding
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted.
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            // Permission denied.
            Toast.makeText(this, "Microphone permission is required for the app to work.", Toast.LENGTH_LONG).show()
        }
    }
    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Re-checks the DND permission when the user returns from the settings page.
        if (!isDndPermissionGranted()) {
            Toast.makeText(this, "Do Not Disturb access is required to change ringer modes.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "DND access granted!", Toast.LENGTH_SHORT).show()
        }
    }

    // Class Variables
    private lateinit var binding: ActivityMainBinding
    private var currentThreshold = 60
    private lateinit var notificationManager: NotificationManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Sets up listeners for UI controls.
        setupControlListeners()


        checkAndRequestAudioPermission()
        checkAndRequestDndPermission()

        // Set the initial state to "OFF" when the app starts.
        stopMonitoringService()
    }

    private fun setupControlListeners() {
        // Set the initial text for the slider to match its default progress.
        binding.tvThresholdValue.text = binding.seekThreshold.progress.toString()
        currentThreshold = binding.seekThreshold.progress

        // Listener for the Threshold Slider.
        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Update the threshold value text.
                binding.tvThresholdValue.text = progress.toString()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // Save the final threshold value.
                currentThreshold = seekBar?.progress ?: 60
            }
        })

        // Listener for the main Monitoring Switch.
        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startMonitoringService()
            } else {
                stopMonitoringService()
            }
        }

        // Listener for the Calibrate button.
        binding.btnCalibrate.setOnClickListener {
            Toast.makeText(this, "Calibration feature not implemented yet.", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Updates the UI to the "ON" state, initializes AudioRecord, and starts the monitoring thread.
     */
    private fun startMonitoringService() {
        // 1. Verify Audio Permission
        // This check is crucial. Initializing AudioRecord without permission will crash the app.
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission is not granted.", Toast.LENGTH_SHORT).show()
            // Reset the switch to OFF, as monitoring cannot start.
            binding.switchMonitoring.isChecked = false
            return
        }

        // 2. Initialize AudioRecord
        try {
            // Get the minimum buffer size required for the device's hardware.
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            // Handle cases where the buffer size is invalid.
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e("SilencerApp", "Invalid AudioRecord parameters.")
                return
            }

            // Create the AudioRecord instance.
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            // Start recording.
            audioRecord?.startRecording()

        } catch (e: Exception) {
            Log.e("SilencerApp", "AudioRecord initialization failed", e)
            Toast.makeText(this, "Microphone is in use by another app.", Toast.LENGTH_SHORT).show()
            binding.switchMonitoring.isChecked = false
            return
        }

        // 3. Start Monitoring Thread
        isMonitoring = true
        monitoringThread = Thread {
            val audioBuffer = ShortArray(bufferSize)

            // Main loop for the monitoring thread.
            while (isMonitoring) {
                // Read audio data from the microphone into the buffer.
                val readResult = audioRecord?.read(audioBuffer, 0, bufferSize)

                if (readResult != null && readResult > 0) {
                    // Calculate the decibel level from the buffer.
                    val db = calculateDecibel(audioBuffer)

                    runOnUiThread {
                        updateDecibelUI(db.toInt())
                    }
                }

                // Pause the thread briefly to avoid excessive CPU usage.
                try {
                    Thread.sleep(250) // Samples 4 times per second.
                } catch (e: InterruptedException) {
                    // Thread was interrupted, likely by stopMonitoringService().
                    isMonitoring = false
                }
            }
        }
        // Start the thread.
        monitoringThread?.start()

        // 4. Update UI to "ON" state
        binding.lottieMicIcon.playAnimation()
        binding.cardDashboard.alpha = 1.0f
        binding.seekThreshold.isEnabled = false
        binding.btnCalibrate.isEnabled = false
        updateModeUI(isSilent = false)
    }

    /**
     * Updates the UI to the "OFF" state, stops the monitoring thread, and releases AudioRecord resources.
     */
    private fun stopMonitoringService() {

        if (monitoringThread != null) {
            isMonitoring = false // Signal the thread's while-loop to exit.
            monitoringThread?.interrupt() // Wake the thread if it's sleeping.
            monitoringThread = null
        }

        if (audioRecord != null) {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop() // Stop recording.
            }
            audioRecord?.release() // Release the hardware.
            audioRecord = null
        }

        binding.lottieMicIcon.pauseAnimation()
        binding.lottieMicIcon.progress = 0f
        binding.cardDashboard.alpha = 0.7f
        binding.seekThreshold.isEnabled = true
        binding.btnCalibrate.isEnabled = true
        binding.tvDecibelValue.text = "-- dB"
        binding.progressDecibel.progress = 0
        binding.tvCurrentMode.text = "Monitoring Paused"
        binding.tvCurrentMode.background =
            ContextCompat.getDrawable(this, R.drawable.bg_mode_paused)
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
            // Set text and background to RED ("Silent").
            binding.tvCurrentMode.text = "Silent Mode"
            binding.tvCurrentMode.background = ContextCompat.getDrawable(this, R.drawable.bg_mode_silent)
        } else {
            // Set text and background to GREEN ("Normal").
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
            // Check if permission is already granted.
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is already granted.
                Toast.makeText(this, "Microphone permission is already granted.", Toast.LENGTH_SHORT).show()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(this, "Microphone access is required to detect noise levels.", Toast.LENGTH_LONG).show()
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            // If no permission, ask for it.
            else -> {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    /**
     * Checks if the app has been granted 'Do Not Disturb' (DND) access.
     * Required for API 23 (Marshmallow) and above.
     * @return True if permission is granted, false otherwise.
     */
    private fun isDndPermissionGranted(): Boolean {
        return notificationManager.isNotificationPolicyAccessGranted
    }

    /**
     * Checks if DND (Do Not Disturb) access is granted.
     * If not, it launches the system settings page for the user to grant it.
     */
    private fun checkAndRequestDndPermission() {
        // Check if permission is already granted.
        if (isDndPermissionGranted()) {
            Toast.makeText(this, "DND access is already granted.", Toast.LENGTH_SHORT).show()
            return
        }

        // If not granted, show a toast and prepare to launch the settings page.
        Toast.makeText(this, "Please grant Do Not Disturb access for the app to work.", Toast.LENGTH_LONG).show()

        // Create an Intent to open the DND access settings page.
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

        // Launch the settings page.
        dndPermissionLauncher.launch(intent)
    }

    // AudioRecord and Monitoring Variables
    private var audioRecord: AudioRecord? = null
    private var monitoringThread: Thread? = null

    // A flag to control the while-loop in the monitoring thread.
    @Volatile private var isMonitoring = false

    // Audio configuration settings.
    private val sampleRate = 44100 // Standard sample rate.
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // The size of the buffer to read audio data into.
    private var bufferSize = 0

    /**
     * Calculates the decibel (dB) level from a buffer of raw audio data.
     * @param audioBuffer The buffer of 16-bit audio samples.
     * @return The calculated decibel level as a Double.
     */
    private fun calculateDecibel(audioBuffer: ShortArray): Double {
        var sum = 0.0

        // Sum the squares of the amplitudes.
        for (sample in audioBuffer) {
            sum += sample.toDouble() * sample.toDouble()
        }

        // Calculate the Root Mean Square (RMS).
        val rms = sqrt(sum / audioBuffer.size)

        // Calculate the dB level: 20 * log10(rms).
        // A check for rms > 0 is essential to avoid log(0), which is -Infinity.
        return if (rms > 0) {
            // This calculation gives a value (e.g., 60-90) that is
            // easy to understand as a "decibel" level for the UI.
            20 * log10(rms)
        } else {
            0.0 // Return 0 if silence (rms = 0).
        }
    }
}
