package one.woodbury.drone.bridge.service

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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import one.woodbury.drone.bridge.MainActivity
import one.woodbury.drone.bridge.R

private const val TAG = "BridgeForegroundService"
private const val NOTIFICATION_CHANNEL_ID = "drone_bridge_service_channel"
private const val NOTIFICATION_ID = 5601

/**
 * Foreground service that maintains CPU execution and low-latency Wi-Fi streaming
 * while the drone controller is connected and the device screen is turned off.
 */
class BridgeForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        lastState = NotificationState(isStreaming = false, isPaused = false)
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(this, isStreaming = false, isPaused = false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        isServiceRunning = false
        lastState = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.bridge_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.bridge_notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "one.woodbury.drone.bridge:BridgeCpuWakeLock",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to acquire CPU WakeLock.", exception)
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(
                lockType,
                "one.woodbury.drone.bridge:BridgeWifiLock",
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to acquire WifiLock.", exception)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to release CPU WakeLock.", exception)
        }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to release WifiLock.", exception)
        }
    }

    companion object {
        @Volatile
        private var isServiceRunning = false

        private data class NotificationState(val isStreaming: Boolean, val isPaused: Boolean)

        @Volatile
        private var lastState: NotificationState? = null

        private fun buildNotification(context: Context, isStreaming: Boolean, isPaused: Boolean = false): Notification {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val contentText = when {
                isPaused -> context.getString(R.string.bridge_notification_paused)
                isStreaming -> context.getString(R.string.bridge_notification_streaming)
                else -> context.getString(R.string.bridge_notification_waiting)
            }

            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.bridge_notification_title))
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
        }

        /**
         * Starts the foreground bridge service.
         */
        fun start(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Stops the foreground bridge service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java)
            context.stopService(intent)
        }

        /**
         * Dynamically updates the ongoing foreground notification with the current streaming status.
         */
        fun updateStatus(context: Context, isStreaming: Boolean, isPaused: Boolean = false) {
            if (!isServiceRunning) return
            val newState = NotificationState(isStreaming, isPaused)
            if (newState == lastState) return
            lastState = newState
            val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
            val notification = buildNotification(context, isStreaming, isPaused)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }
}
