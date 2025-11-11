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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.log10
import kotlin.math.sqrt

class SoundMonitoringService : Service() {

    private var audioRecord: AudioRecord? = null
    private var monitoringThread: Thread? = null
    @Volatile private var isMonitoring = false
    private var bufferSize = 0

    // Audio configuration parameters.
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var audioManager: AudioManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var localBroadcastManager: LocalBroadcastManager
    private var isSilentMode = false

    private var currentThreshold = 60 // Default threshold, updated by intent.
    private var quietSampleCounter = 0
    private var loudSampleCounter = 0
    // Persistence threshold: 40 samples * 250ms sleep = 10 seconds.
    private val persistenceThreshold = 40

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "SoundMonitoringChannel"

        const val ACTION_START_MONITORING = "com.Aryan.SilencerApp.START_MONITORING"
        const val ACTION_STOP_MONITORING = "com.Aryan.SilencerApp.STOP_MONITORING"
        const val EXTRA_THRESHOLD = "com.Aryan.SilencerApp.EXTRA_THRESHOLD"

        // Broadcast actions for service-to-activity communication.
        const val BROADCAST_ACTION_STATUS_UPDATE = "com.Aryan.SilencerApp.STATUS_UPDATE"
        const val EXTRA_DECIBEL_LEVEL = "com.Aryan.SilencerApp.EXTRA_DECIBEL_LEVEL"
        const val EXTRA_RINGER_MODE = "com.Aryan.SilencerApp.EXTRA_RINGER_MODE"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("SoundService", "Service onCreate")

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        localBroadcastManager = LocalBroadcastManager.getInstance(this)

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10+, specifying the service type is required for microphone access from the background.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SoundService", "Service onStartCommand")

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
        Log.d("SoundService", "Service onDestroy")
        stopMonitoring()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This is not a bound service.
    }

    /**
     * Initializes AudioRecord and starts the background monitoring thread.
     */
    private fun startMonitoring() {
        if (isMonitoring) {
            Log.w("SoundService", "Monitoring is already running.")
            return
        }

        // Safeguard to ensure audio permission is granted before starting.
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("SoundService", "Cannot start monitoring, RECORD_AUDIO permission not granted.")
            stopSelf()
            return
        }

        isSilentMode = (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT)
        broadcastStatus(0.0, isSilentMode) // Send initial state to the UI.
        quietSampleCounter = 0
        loudSampleCounter = 0

        try {
            bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e("SoundService", "Invalid AudioRecord parameters.")
                return
            }

            @Suppress("DEPRECATION")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()

        } catch (e: Exception) {
            Log.e("SoundService", "AudioRecord initialization failed", e)
            return
        }

        isMonitoring = true
        monitoringThread = Thread {
            // Buffer size is in bytes, a Short is 2 bytes.
            val audioBuffer = ShortArray(bufferSize / 2)

            while (isMonitoring) {
                val readResult = audioRecord?.read(audioBuffer, 0, audioBuffer.size)

                if (readResult != null && readResult > 0) {
                    val db = calculateDecibel(audioBuffer)
                    
                    // Logic to adjust ringer mode based on sound level and persistence.
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

    /**
     * Stops the monitoring thread and releases the AudioRecord resource.
     */
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
        Log.d("SoundService", "Monitoring stopped.")
    }

    /**
     * Sets the device's ringer mode to silent.
     */
    private fun setSilentMode() {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Log.e("SoundService", "Cannot set silent mode, DND permission is not granted.")
            return
        }
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            isSilentMode = true
        } catch (e: SecurityException) {
            Log.e("SoundService", "Security exception while setting silent mode.", e)
        }
    }

    /**
     * Sets the device's ringer mode to normal.
     */
    private fun setNormalMode() {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            Log.e("SoundService", "Cannot set normal mode, DND permission is not granted.")
            return
        }
        try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            isSilentMode = false
        } catch (e: SecurityException) {
            Log.e("SoundService", "Security exception while setting normal mode.", e)
        }
    }

    /**
     * Calculates the decibel level from a buffer of raw audio data.
     */
    private fun calculateDecibel(audioBuffer: ShortArray): Double {
        var sum: Double = 0.0
        for (sample in audioBuffer) {
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sum / audioBuffer.size)
        // A check for rms > 0 is essential to avoid log(0) which is -Infinity.
        if (rms > 0) {
            return 20 * log10(rms)
        }
        return 0.0
    }

    /**
     * Broadcasts the current decibel level and ringer mode to the activity.
     */
    private fun broadcastStatus(db: Double, isSilent: Boolean) {
        val intent = Intent(BROADCAST_ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_DECIBEL_LEVEL, db)
            putExtra(EXTRA_RINGER_MODE, isSilent)
        }
        localBroadcastManager.sendBroadcast(intent)
    }

    /**
     * Creates the persistent notification for the foreground service.
     */
    private fun createNotification(): Notification {
        // Create the notification channel for Android 8.0+.
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