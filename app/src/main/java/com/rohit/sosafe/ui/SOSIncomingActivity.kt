package com.rohit.sosafe.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.ui.theme.SoSafeTheme
import com.rohit.sosafe.utils.SirenPlayer

class SOSIncomingActivity : ComponentActivity() {

    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sessionId = intent.getStringExtra("sessionId") ?: ""
        val senderId = intent.getStringExtra("senderId") ?: ""
        val senderName = intent.getStringExtra("senderName") ?: "Someone"

        // Ensure lockscreen display and turn screen on
        showOnLockScreen()

        // Cancel system notification banner so custom Activity takes control
        cancelNotification()

        // Ensure siren is playing (SirenPlayer is idempotent)
        SirenPlayer.start(this)

        val session = SosSession(
            sessionId = sessionId,
            senderId = senderId,
            status = "ACTIVE"
        )

        setContent {
            SoSafeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isSirenPlayingState by SirenPlayer.isSirenPlaying.collectAsState()
                    MonitoringScreen(
                        session = session,
                        displayName = senderName,
                        initialDelayMillis = 3000L,
                        isSirenActive = isSirenPlayingState,
                        onSilenceSiren = { 
                            SirenPlayer.stop(this@SOSIncomingActivity)
                            cancelNotification()
                        },
                        onClose = { 
                            Log.d("SOS_AUDIT", "MONITORING_CLOSED: Closing activity.")
                            SirenPlayer.stop(this@SOSIncomingActivity)
                            cancelNotification()
                            finish() 
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOnLockScreen()
        SirenPlayer.start(this)
    }

    private fun cancelNotification() {
        if (sessionId.isNotEmpty()) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(sessionId.hashCode())
            Log.d("SOS_AUDIT", "NOTIFICATION_CANCELLED: Activity took control.")
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            SirenPlayer.stop(this)
            cancelNotification()
        }
        super.onDestroy()
    }

    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }
}
