package com.Aryan.SilencerApp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt

class SoundMonitoringService : Service() {

    // --- AudioRecord and Monitoring Variables ---
    private var audioRecord: AudioRecord? = null
    private var monitoringThread: Thread? = null
    @Volatile private var isMonitoring = false
    private var bufferSize = 0

    // Audio configuration
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // --- System Service Variables ---
    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var localBroadcastManager: androidx.localbroadcastmanager.content.LocalBroadcastManager
    private lateinit var vibrator: Vibrator

    private var isSilentMode = false

    // --- Logic Variables ---
    private var currentThreshold = 60
    private var quietSampleCounter = 0
    private var loudSampleCounter = 0
    private val persistenceThreshold = 40

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "SoundMonitoringChannel"
        const val ACTION_START_MONITORING = "com.Aryan.SilencerApp.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.Aryan.SilencerApp.STOP_MONITORING"
        const val EXTRA_THRESHOLD = "com.Aryan.SilencerApp.EXTRA_THRESHOLD"
        const val BROADCAST_ACTION_STATUS_UPDATE = "com.Aryan.SilencerApp.STATUS_UPDATE"
        const val EXTRA_DECIBEL_LEVEL = "com.Aryan.SilencerApp.EXTRA_DECIBEL_LEVEL"
        const val EXTRA_RINGER_MODE = "com.Aryan.SilencerApp.EXTRA_RINGER_MODE"
    }

    override fun onCreate() {
        super.onCreate()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        localBroadcastManager = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)

        // Robust Vibrator Initialization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibrator = vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_MONITORING -> {
                currentThreshold = intent.getIntExtra(EXTRA_THRESHOLD, 60)
                startMonitoring()
            }
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startMonitoring() {
        if (isMonitoring) return

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        // --- BUG FIX #1: CORRECT STATE CHECK ---
        // We check for VIBRATE mode, not SILENT mode.
        isSilentMode = (audioManager.ringerMode == AudioManager.RINGER_MODE_VIBRATE)
        broadcastStatus(0.0, isSilentMode)

        quietSampleCounter = 0
        loudSampleCounter = 0

        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) return

            @Suppress("DEPRECATION")
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            audioRecord?.startRecording()

        } catch (e: Exception) {
            return
        }

        isMonitoring = true
        monitoringThread = Thread {
            val audioBuffer = ShortArray(bufferSize / 2)

            while (isMonitoring) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size)

                if (readResult != null && readResult > 0) {
                    val db = calculateDecibel(audioBuffer)

                    try {
                        if (db < currentThreshold && !isSilentMode) {
                            quietSampleCounter++
                            loudSampleCounter = 0
                            if (quietSampleCounter >= persistenceThreshold) {
                                setSilentMode()
                                quietSampleCounter = 0
                            }
                        } else if (db > currentThreshold && isSilentMode) {
                            loudSampleCounter++
                            quietSampleCounter = 0
                            if (loudSampleCounter >= persistenceThreshold) {
                                setNormalMode()
                                loudSampleCounter = 0
                            }
                        } else {
                            quietSampleCounter = 0
                            loudSampleCounter = 0
                        }
                    } catch (e: Exception) {
                        Log.e("SoundService", "Error in monitoring loop", e)
                    }

                    broadcastStatus(db, isSilentMode)
                }

                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    isMonitoring = false
                }
            }
        }
        monitoringThread?.start()
    }

    private fun stopMonitoring() {
        if (monitoringThread != null) {
            isMonitoring = false
            monitoringThread?.interrupt()
            monitoringThread = null
        }
        if (audioRecord != null) {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
            audioRecord = null
        }
    }

    // --- Updated Helper Functions ---

    private fun setSilentMode() {
        if (!notificationManager.isNotificationPolicyAccessGranted) return
        try {
            // 1. Change mode FIRST (Unlocks motor)
            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            isSilentMode = true

            // 2. Wait slightly
            Thread.sleep(100)

            // 3. Vibrate
            triggerVibration()

        } catch (e: Exception) {}
    }

    private fun setNormalMode() {
        if (!notificationManager.isNotificationPolicyAccessGranted) return
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            isSilentMode = false

            Thread.sleep(100)
            triggerVibration()

        } catch (e: Exception) {}
    }

    // --- BUG FIX #2: STRONGER VIBRATION ---
    private fun triggerVibration() {
        try {
            // Using a double-buzz pattern (0ms delay, 200ms vibe, 100ms pause, 200ms vibe)
            // This -1 means "do not repeat"
            val pattern = longArrayOf(0, 50, 100, 50)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use DEFAULT_AMPLITUDE to be safe on all devices
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e("SoundService", "Vibration failed", e)
        }
    }

    private fun calculateDecibel(audioBuffer: ShortArray): Double {
        var sum: Double = 0.0
        for (sample in audioBuffer) {
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sum / audioBuffer.size)
        if (rms > 0) {
            return 20 * log10(rms)
        }
        return 0.0
    }

    private fun broadcastStatus(db: Double, isSilent: Boolean) {
        val intent = Intent(BROADCAST_ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_DECIBEL_LEVEL, db)
            putExtra(EXTRA_RINGER_MODE, isSilent)
        }
        localBroadcastManager.sendBroadcast(intent)
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Silencer Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Silencer App is Active")
            .setContentText("Monitoring ambient sound level.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }
}