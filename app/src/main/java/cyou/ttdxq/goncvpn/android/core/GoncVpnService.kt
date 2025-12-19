package cyou.ttdxq.goncvpn.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import cyou.ttdxq.goncvpn.android.R
import cyou.ttdxq.goncvpn.android.ui.MainActivity
import cyou.ttdxq.goncvpn.android.data.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import gobridge.Gobridge
import gobridge.Logger

class GoncVpnService : VpnService() {
    companion object {
        const val ACTION_START = "cyou.ttdxq.goncvpn.android.START"
        const val ACTION_STOP = "cyou.ttdxq.goncvpn.android.STOP"
        const val EXTRA_P2P_SECRET = "p2p_secret"
        const val EXTRA_ROUTE_CIDRS = "route_cidrs" // Newline separated
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "gonc_vpn_channel"
        private const val TAG = "GoncVpnService"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var vpnInterface: ParcelFileDescriptor? = null
    
    // JNI Logger implementation
    private val goLogger = object : Logger {
        override fun log(message: String?) {
            message?.let { LogRepository.log("GoJNI", it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Gobridge.setLogger(goLogger)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "onStartCommand received action: ${intent?.action}")
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                val secret = intent.getStringExtra(EXTRA_P2P_SECRET) ?: ""
                val cidrs = intent.getStringExtra(EXTRA_ROUTE_CIDRS) ?: ""
                startVpn(secret, cidrs)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    private fun startVpn(secret: String, cidrs: String) {
        if (secret.isBlank()) {
            stopSelf()
            return
        }

        serviceScope.launch(Dispatchers.IO) {
            try {
                // 1. Setup VPN Interface
                val builder = Builder()
                    .addAddress("10.0.0.2", 32)
                    .addAddress("fd00::2", 128)
                    .addDisallowedApplication(packageName)
                    .setMtu(1400)
                    .setSession("GoncVPN")

                cidrs.lines().filter { it.isNotBlank() }.forEach { cidr ->
                    try {
                        val parts = cidr.trim().split("/")
                        val address = parts[0]
                        val prefixLength = if (parts.size > 1) {
                            parts[1].toInt()
                        } else {
                            if (address.contains(":")) 128 else 32
                        }
                        builder.addRoute(address, prefixLength)
                    } catch (e: Exception) {
                        Log.e(TAG, "Invalid route: $cidr", e)
                    }
                }

                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    Log.e(TAG, "Failed to establish VPN interface")
                    stopSelf()
                    return@launch
                }

                val fd = vpnInterface!!.fd
                LogRepository.log(TAG, "VPN interface established. FD: $fd")

                // 2. Start Tun2Socks (Non-blocking usually, but allows packet processing)
                // We use socks5://127.0.0.1:1080 as the proxy target for tun2socks
                // Logic: tun2socks reads form TUN -> forwards to SOCKS5 (gonc)
                // Go 'int' maps to Java 'long', so we must cast fd and mtu.
                Gobridge.startTun2Socks(fd.toLong(), "socks5://127.0.0.1:1080", "tun0", 1400L, "error")
                LogRepository.log(TAG, "Tun2Socks started")

                // 3. Start Gonc (Blocking, so run in separate thread)
                // Args: -p2p <SECRET> -link 1080;none
                val goncArgs = "-p2p $secret -link 1080;none"
                Thread {
                    LogRepository.log(TAG, "Starting Gonc with args: $goncArgs")
                    try {
                        Gobridge.startGonc(goncArgs)
                    } catch (e: Exception) {
                        LogRepository.log(TAG, "Gonc exited with error: ${e.message}")
                    }
                    LogRepository.log(TAG, "Gonc thread exited")
                }.start()

                updateNotification("Connected")

            } catch (e: Exception) {
                LogRepository.log(TAG, "FATAL: Error starting VPN: ${e.message}")
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        serviceScope.launch(Dispatchers.IO) {
            // Close the interface first to signal tun2socks (reading from FD will fail)
            // This prevents fdsan issues if tun2socks tries to close it, 
            // though ideally tun2socks shouldn't close an FD it didn't open.
            try {
                vpnInterface?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }
            vpnInterface = null
            
            try {
                Gobridge.stopTun2Socks()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping tun2socks", e)
            }
            
            stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // Ensure cleanup
        try {
            Gobridge.stopTun2Socks()
            vpnInterface?.close()
        } catch(_: Exception){}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Status",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gonc VPN")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(status))
    }
}
