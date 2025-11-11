package com.Aryan.SilencerApp

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.Aryan.SilencerApp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentThreshold = 60
    private lateinit var notificationManager: NotificationManager
    private lateinit var localBroadcastManager: LocalBroadcastManager

    /**
     * Receives status updates from the SoundMonitoringService.
     */
    private val statusUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == SoundMonitoringService.BROADCAST_ACTION_STATUS_UPDATE) {
                val db = intent.getDoubleExtra(SoundMonitoringService.EXTRA_DECIBEL_LEVEL, 0.0)
                val isSilent = intent.getBooleanExtra(SoundMonitoringService.EXTRA_RINGER_MODE, false)

                updateDecibelUI(db.toInt())
                updateModeUI(isSilent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        setupListeners()

        checkAndRequestAudioPermission()
        checkAndRequestDndPermission()
        checkAndRequestNotificationPermission()

        updateUIMonitoringStopped()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SoundMonitoringService.BROADCAST_ACTION_STATUS_UPDATE)
        localBroadcastManager.registerReceiver(statusUpdateReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        localBroadcastManager.unregisterReceiver(statusUpdateReceiver)
    }

    /**
     * Initializes and sets listeners for UI components.
     */
    private fun setupListeners() {
        binding.tvThresholdValue.text = binding.seekThreshold.progress.toString()
        currentThreshold = binding.seekThreshold.progress

        binding.seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvThresholdValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                currentThreshold = seekBar?.progress ?: 60
            }
        })

        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startMonitoringService()
            } else {
                stopMonitoringService()
            }
        }
    }

    /**
     * Starts the sound monitoring service after performing permission checks.
     */
    private fun startMonitoringService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is not granted.", Toast.LENGTH_SHORT).show()
            binding.switchMonitoring.isChecked = false
            return
        }
        if (!isDndPermissionGranted()) {
            Toast.makeText(this, "Do Not Disturb permission is not granted.", Toast.LENGTH_SHORT).show()
            binding.switchMonitoring.isChecked = false
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Notification permission is not granted.", Toast.LENGTH_SHORT).show()
            binding.switchMonitoring.isChecked = false
            return
        }

        val serviceIntent = Intent(this, SoundMonitoringService::class.java).apply {
            action = SoundMonitoringService.ACTION_START_MONITORING
            putExtra(SoundMonitoringService.EXTRA_THRESHOLD, currentThreshold)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        updateUIMonitoringStarted()
    }

    /**
     * Stops the sound monitoring service.
     */
    private fun stopMonitoringService() {
        val serviceIntent = Intent(this, SoundMonitoringService::class.java).apply {
            action = SoundMonitoringService.ACTION_STOP_MONITORING
        }
        startService(serviceIntent)
        updateUIMonitoringStopped()
    }

    /**
     * Updates the UI to reflect that monitoring has started.
     */
    private fun updateUIMonitoringStarted() {
        binding.lottieMicIcon.playAnimation()
        binding.cardDashboard.alpha = 1.0f
        binding.seekThreshold.isEnabled = false
    }

    /**
     * Updates the UI to reflect that monitoring has stopped.
     */
    private fun updateUIMonitoringStopped() {
        binding.lottieMicIcon.pauseAnimation()
        binding.lottieMicIcon.progress = 0f
        binding.cardDashboard.alpha = 0.7f
        binding.seekThreshold.isEnabled = true

        binding.tvDecibelValue.text = "-- dB"
        binding.progressDecibel.progress = 0
        binding.tvCurrentMode.text = "Monitoring Paused"
        binding.tvCurrentMode.background =
            ContextCompat.getDrawable(this, R.drawable.bg_mode_paused)
        binding.tvDecibelAnalogy.text = "-- Awaiting audio --"
    }

    /**
     * Updates the decibel progress ring and text.
     */
    private fun updateDecibelUI(db: Int) {
        if (binding.switchMonitoring.isChecked) {
            binding.progressDecibel.setProgressCompat(db, true)
            binding.tvDecibelValue.text = "$db dB"
            binding.tvDecibelAnalogy.text = getDecibelAnalogy(db)
        }
    }

    /**
     * Updates the status text and color (Normal/Silent).
     */
    private fun updateModeUI(isSilent: Boolean) {
        if (!binding.switchMonitoring.isChecked) return

        if (isSilent) {
            binding.tvCurrentMode.text = "Silent Mode"
            binding.tvCurrentMode.background =
                ContextCompat.getDrawable(this, R.drawable.bg_mode_silent)
        } else {
            binding.tvCurrentMode.text = "Normal Mode"
            binding.tvCurrentMode.background =
                ContextCompat.getDrawable(this, R.drawable.bg_mode_normal)
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (isDndPermissionGranted()) {
            Toast.makeText(this, "DND access granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "DND access is required.", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Notification permission is required.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Checks for audio permission and requests it if it has not been granted.
     */
    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {}
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(this, "Microphone access is needed to detect noise.", Toast.LENGTH_LONG).show()
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Checks if the user has granted Do Not Disturb access.
     */
    private fun isDndPermissionGranted(): Boolean {
        return notificationManager.isNotificationPolicyAccessGranted
    }

    /**
     * Checks for Do Not Disturb permission and launches the settings screen if not granted.
     */
    private fun checkAndRequestDndPermission() {
        if (isDndPermissionGranted()) return

        Toast.makeText(this, "Please grant Do Not Disturb access.", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        dndPermissionLauncher.launch(intent)
    }

    /**
     * Checks for notification permission on Android 13+ and requests it if not granted.
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {}
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(this, "Notification permission is needed for the service.", Toast.LENGTH_LONG).show()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Returns a real-world analogy string based on the decibel level.
     */
    private fun getDecibelAnalogy(db: Int): String {
        return when {
            db < 30 -> "Faint (Quiet Forest)"
            db < 40 -> "Quiet (Library)"
            db < 60 -> "Moderate (Normal conversation)"
            db < 70 -> "Loud (Street traffic)"
            db < 80 -> "Very Loud (Alarm clock)"
            db < 90 -> "Disruptive (Mixer or Grinder)"
            else -> "Harmful (Loud concert)"
        }
    }
}