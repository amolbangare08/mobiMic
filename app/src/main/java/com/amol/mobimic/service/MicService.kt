package com.amol.mobimic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.amol.mobimic.MainActivity
import com.amol.mobimic.R
import com.amol.mobimic.audio.EngineController
import com.amol.mobimic.audio.NativeAudioEngine
import com.amol.mobimic.audio.Presets
import com.amol.mobimic.audio.StreamSettings
import com.amol.mobimic.net.NetworkLinks
import com.amol.mobimic.net.ReceiverProbe

/**
 * Keeps capture alive with the screen off.
 *
 * Android kills microphone access for backgrounded apps, and the CPU sleeps
 * without a wake lock, so this service is not optional even in Phase 1.
 * Phase 2 adds the low-latency Wi-Fi lock here as well.
 */
class MicService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every action path has to reach startForeground, including the adb-driven
        // ones, or the platform kills the service for not promoting itself in time.
        ensureForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            // The record and stream actions exist so the capture path can be driven
            // from adb during testing, without tapping through the UI.
            ACTION_RECORD_START -> {
                val dir = getExternalFilesDir(null) ?: filesDir
                val source = if (intent.getStringExtra(EXTRA_SOURCE) == "processed") {
                    NativeAudioEngine.RecordSource.PROCESSED
                } else {
                    NativeAudioEngine.RecordSource.RAW
                }
                EngineController.startRecording(dir, source)
            }
            ACTION_RECORD_STOP -> EngineController.stopRecording()
            ACTION_STREAM_START -> startStreamingAsync()
            ACTION_STREAM_STOP -> EngineController.stopStreaming()
            // Prints the live counters to logcat, so a test run does not need the UI.
            ACTION_LOG_STATS -> Log.i("mobiMic", "stats: ${NativeAudioEngine.stats()}")
            // Applies a factory preset without the UI, for scripted A/B runs.
            ACTION_APPLY_PRESET -> applyPreset(intent.getStringExtra(EXTRA_PRESET))
            ACTION_PROBE_PATHS -> Thread { NativeAudioEngine.probePaths() }.start()
            ACTION_SET_WIRE_FORMAT -> setWireFormat(intent.getStringExtra(EXTRA_FORMAT))
            ACTION_SET_LINK -> setLinkMode(intent.getStringExtra(EXTRA_LINK))
            ACTION_DISCOVER -> Thread {
                val links = NetworkLinks.list()
                Log.i("mobiMic", "links: " + links.joinToString {
                    "${it.interfaceName}=${it.hostAddress}(${it.kind})"
                })
                val found = ReceiverProbe.discover()
                Log.i("mobiMic", if (found.isEmpty()) "discovery: nothing answered"
                    else "discovery: " + found.joinToString {
                        "${it.host}:${it.port} via ${it.viaInterface}${if (it.overUsb) " USB" else ""}"
                    })
            }.start()
            ACTION_SET_EFFECT_OVERRIDE -> {
                val on = intent.getStringExtra(EXTRA_ENABLED) == "true"
                StreamSettings(this).forceEffectsOff = on
                Log.i("mobiMic", "forceEffectsOff=$on (takes effect on next capture start)")
            }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun startCapture() {
        acquireLocks()

        // Starting the engine now includes receiver discovery, which opens sockets and
        // waits for replies. That is illegal on the main thread and would stall the
        // service anyway, so it runs on a worker.
        Thread {
            if (!EngineController.startEngine(this)) {
                mainHandler.post { stopCapture() }
            }
        }.start()
    }

    private fun setLinkMode(name: String?) {
        val mode = StreamSettings.LinkMode.entries
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (mode == null) {
            Log.w("mobiMic", "unknown link mode: $name")
            return
        }
        StreamSettings(this).linkMode = mode
        Log.i("mobiMic", "linkMode=$mode (takes effect on next stream start)")
    }

    private fun setWireFormat(name: String?) {
        val format = NativeAudioEngine.WireFormat.entries
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (format == null) {
            Log.w("mobiMic", "unknown wire format: $name")
            return
        }
        StreamSettings(this).wireFormat = format
        Log.i("mobiMic", "wireFormat=$format (takes effect on next stream start)")
    }

    private fun applyPreset(name: String?) {
        val settings = StreamSettings(this)
        val preset = Presets.all.firstOrNull { it.first.equals(name, ignoreCase = true) }
        if (preset == null) {
            Log.w("mobiMic", "unknown preset: $name")
            return
        }
        settings.dsp = preset.second
        settings.presetName = preset.first
        NativeAudioEngine.setParams(preset.second)
        Log.i("mobiMic", "applied preset ${preset.first}")
    }

    /** Target resolution can hit DNS, so never run it on the main thread. */
    private fun startStreamingAsync() {
        Thread { EngineController.startStreaming(this) }.start()
    }

    private fun stopCapture() {
        EngineController.stopEngine()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        EngineController.stopEngine()
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Without these two, Wi-Fi power save parks the radio the moment the screen
     * turns off and inserts stalls of 100 ms and more into the stream.
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobiMic:capture").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "mobiMic:stream").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Microphone capture",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while mobiMic is capturing audio"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MicService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("mobiMic")
            .setContentText("Capturing microphone")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.amol.mobimic.START"
        const val ACTION_STOP = "com.amol.mobimic.STOP"
        const val ACTION_RECORD_START = "com.amol.mobimic.RECORD_START"
        const val ACTION_RECORD_STOP = "com.amol.mobimic.RECORD_STOP"
        const val ACTION_STREAM_START = "com.amol.mobimic.STREAM_START"
        const val ACTION_STREAM_STOP = "com.amol.mobimic.STREAM_STOP"
        const val ACTION_LOG_STATS = "com.amol.mobimic.LOG_STATS"
        const val ACTION_APPLY_PRESET = "com.amol.mobimic.APPLY_PRESET"
        const val EXTRA_SOURCE = "source"
        const val ACTION_SET_EFFECT_OVERRIDE = "com.amol.mobimic.SET_EFFECT_OVERRIDE"
        const val ACTION_PROBE_PATHS = "com.amol.mobimic.PROBE_PATHS"
        const val ACTION_SET_WIRE_FORMAT = "com.amol.mobimic.SET_WIRE_FORMAT"
        const val ACTION_SET_LINK = "com.amol.mobimic.SET_LINK"
        const val ACTION_DISCOVER = "com.amol.mobimic.DISCOVER"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_LINK = "link"
        const val EXTRA_PRESET = "preset"
        const val EXTRA_ENABLED = "enabled"

        private const val CHANNEL_ID = "mobimic_capture"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000

        fun start(context: Context) {
            val intent = Intent(context, MicService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MicService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }

        fun startStreaming(context: Context) {
            val intent = Intent(context, MicService::class.java).setAction(ACTION_STREAM_START)
            context.startForegroundService(intent)
        }

        fun stopStreaming(context: Context) {
            val intent = Intent(context, MicService::class.java).setAction(ACTION_STREAM_STOP)
            context.startForegroundService(intent)
        }
    }
}
