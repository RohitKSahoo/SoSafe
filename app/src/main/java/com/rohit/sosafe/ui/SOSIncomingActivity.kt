package com.rohit.sosafe.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.ui.theme.SoSafeTheme

class SOSIncomingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        showOnLockScreen()

        val sessionId = intent.getStringExtra("sessionId") ?: ""
        val senderId = intent.getStringExtra("senderId") ?: ""
        val senderName = intent.getStringExtra("senderName") ?: "Someone"

        // Reuse existing SosSession model for the UI
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
                    MonitoringScreen(
                        session = session,
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun showOnLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }
}
