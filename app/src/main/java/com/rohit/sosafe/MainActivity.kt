package com.rohit.sosafe

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.rohit.sosafe.ui.theme.SoSafeTheme
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.service.SOSForegroundService
import android.util.Log

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private lateinit var userManager: UserManager
    private lateinit var sosTriggerManager: SOSTriggerManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        userManager = UserManager(applicationContext)
        sosTriggerManager = SOSTriggerManager(this)
        
        setContent {
            SoSafeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        userManager = userManager,
                        sosTriggerManager = sosTriggerManager,
                        onPermissionsGranted = { startGuardianService() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun startGuardianService() {
        val serviceIntent = Intent(this, SOSForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}

@Composable
fun MainScreen(
    userManager: UserManager,
    sosTriggerManager: SOSTriggerManager,
    onPermissionsGranted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var userCode by remember { mutableStateOf("Generating code...") }
    val TAG_SCREEN = "MainScreen"

    val permissionsToRequest = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Log.d(TAG_SCREEN, "All permissions granted")
            onPermissionsGranted()
        } else {
            Log.e(TAG_SCREEN, "Some permissions denied")
        }
    }

    LaunchedEffect(Unit) {
        // Check if permissions are already granted
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(userManager.getContextForTestingOnly(), it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (allGranted) {
            onPermissionsGranted()
        } else {
            launcher.launch(permissionsToRequest)
        }
        
        userCode = userManager.getUserCode()
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "SoSafe is Active",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFF4CAF50),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "Your User Code: $userCode",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Button(
            onClick = { sosTriggerManager.manualTrigger() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Text("TRIGGER SOS (TEST)", color = Color.White)
        }
        
        Text(
            text = "Detection works when screen is OFF",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 32.dp)
        )
    }
}

// Extension to help UserManager get context safely for checkSelfPermission
private fun UserManager.getContextForTestingOnly(): android.content.Context {
    // This is a bit of a hack to get context since UserManager is private, 
    // but in a real app we'd have a better DI or context holder.
    return (this::class.java.getDeclaredField("context").apply { isAccessible = true }.get(this) as android.content.Context)
}
