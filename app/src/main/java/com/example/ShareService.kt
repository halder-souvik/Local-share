package com.example

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.data.AppDatabase
import com.example.data.SharedFolder
import com.example.server.LocalHttpServer
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.*

class ShareService : Service() {

    private val tag = "ShareService"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var httpServer: LocalHttpServer? = null
    
    companion object {
        const val ACTION_START = "com.example.START_SHARE"
        const val ACTION_STOP = "com.example.STOP_SHARE"
        
        const val EXTRA_ID = "extra_id"
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_PORT = "extra_port"
        
        private const val CHANNEL_ID = "localshare_channel"
        private const val NOTIFICATION_ID = 101

        var activeShareId: Int? = null
            private set
        
        var bytesTransferredInMemory = 0L
            private set

        fun getLocalIpAddress(): String? {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (networkInterface in interfaces) {
                    val addresses = Collections.list(networkInterface.inetAddresses)
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val ip = address.hostAddress
                            if (ip != null && !ip.startsWith("127.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ShareService", "Error getting IP", e)
            }
            return null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_START) {
            val id = intent.getIntExtra(EXTRA_ID, -1)
            val name = intent.getStringExtra(EXTRA_NAME) ?: "Shared Folder"
            val uriString = intent.getStringExtra(EXTRA_URI) ?: ""
            val port = intent.getIntExtra(EXTRA_PORT, 8080)
            
            if (id != -1 && uriString.isNotEmpty()) {
                startShare(id, name, uriString, port)
            }
        } else if (action == ACTION_STOP) {
            stopShare()
        }
        return START_STICKY
    }

    private fun startShare(id: Int, name: String, uriString: String, port: Int) {
        // If there's an existing server running, stop it first
        httpServer?.stop()
        
        activeShareId = id
        bytesTransferredInMemory = 0L

        // Start HTTP Server
        httpServer = LocalHttpServer(this, uriString, port) { bytes ->
            bytesTransferredInMemory += bytes
            updateBytesInDatabase()
        }
        httpServer?.start()

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        val serverUrl = "http://$ip:$port"

        // Build Foreground Notification
        val stopIntent = Intent(this, ShareService::class.java).apply {
            this.action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification open app intent
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sharing \"$name\"")
            .setContentText("Accessible locally at $serverUrl")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Update active status in Database
        serviceScope.launch {
            val db = AppDatabase.getDatabase(this@ShareService)
            db.sharedFolderDao().deactivateAll() // Deactivate any other
            val folder = db.sharedFolderDao().getSharedFolderById(id)
            if (folder != null) {
                db.sharedFolderDao().updateSharedFolder(folder.copy(isActive = true))
            }
        }
    }

    private fun stopShare() {
        httpServer?.stop()
        httpServer = null
        val id = activeShareId
        activeShareId = null

        serviceScope.launch {
            val db = AppDatabase.getDatabase(this@ShareService)
            if (id != null) {
                val folder = db.sharedFolderDao().getSharedFolderById(id)
                if (folder != null) {
                    // Fetch latest memory bytes before deactivating
                    db.sharedFolderDao().updateSharedFolder(
                        folder.copy(
                            isActive = false,
                            bytesTransferred = folder.bytesTransferred + bytesTransferredInMemory
                        )
                    )
                }
            }
            db.sharedFolderDao().deactivateAll()
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private var lastDbUpdate = 0L
    private fun updateBytesInDatabase() {
        val now = System.currentTimeMillis()
        if (now - lastDbUpdate > 3000) { // Throttle DB updates to once every 3 seconds
            lastDbUpdate = now
            val id = activeShareId ?: return
            val bytes = bytesTransferredInMemory
            serviceScope.launch {
                val db = AppDatabase.getDatabase(this@ShareService)
                val folder = db.sharedFolderDao().getSharedFolderById(id)
                if (folder != null) {
                    db.sharedFolderDao().updateSharedFolder(
                        folder.copy(bytesTransferred = folder.bytesTransferred + bytes)
                    )
                    bytesTransferredInMemory = 0L // Reset memory accumulator after flushing to DB
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Folder Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of currently shared folder"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
