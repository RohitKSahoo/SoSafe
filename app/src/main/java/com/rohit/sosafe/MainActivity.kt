package com.rohit.sosafe

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohit.sosafe.ui.theme.SoSafeTheme
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.service.SOSForegroundService
import com.rohit.sosafe.ui.DashboardScreen
import com.rohit.sosafe.ui.DashboardViewModel
import com.rohit.sosafe.ui.DashboardViewModelFactory
import com.rohit.sosafe.data.AppMode
import com.rohit.sosafe.data.AppModeManager
import com.rohit.sosafe.data.StreamingModeManager
import com.rohit.sosafe.ui.ModeSelectionScreen
import com.rohit.sosafe.ui.AddContactDialog
import com.rohit.sosafe.data.RoleManager
import com.rohit.sosafe.utils.RecordingManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val tag = "MainActivity"
    private lateinit var userManager: UserManager
    private lateinit var sosTriggerManager: SOSTriggerManager
    private lateinit var appModeManager: AppModeManager
    private lateinit var streamingModeManager: StreamingModeManager
    private lateinit var recordingManager: RecordingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        userManager = UserManager(applicationContext)
        appModeManager = AppModeManager(applicationContext)
        streamingModeManager = StreamingModeManager(applicationContext)
        recordingManager = RecordingManager(applicationContext)
        sosTriggerManager = SOSTriggerManager(this)
        
        val currentMode = appModeManager.getAppMode()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val myId = userManager.getUserCode()
            RoleManager.myUserId = myId
            if (currentMode != null) {
                RoleManager.role = currentMode.name
                val contacts = userManager.getContacts()
                if (contacts.isNotEmpty()) {
                    RoleManager.pairedUserId = contacts.first()
                }
                RoleManager.updateAuditLog()
            }
        }
        
        setContent {
            SoSafeTheme {
                var currentAppMode by remember { mutableStateOf(appModeManager.getAppMode()) }

                if (currentAppMode == null) {
                    ModeSelectionScreen { selectedMode ->
                        appModeManager.setAppMode(selectedMode)
                        RoleManager.role = selectedMode.name
                        currentAppMode = selectedMode
                    }
                } else {
                    val viewModel: DashboardViewModel = viewModel(
                        factory = DashboardViewModelFactory(userManager, appModeManager, streamingModeManager, recordingManager)
                    )
                    
                    MainScreen(
                        userManager = userManager,
                        viewModel = viewModel,
                        appMode = currentAppMode!!,
                        onPermissionsGranted = { 
                            // START SERVICE FOR BOTH: SENDER (Protection) & GUARDIAN (Listening)
                            startGuardianService() 
                        },
                        onTriggerSOS = { sosTriggerManager.manualTrigger() },
                        onStopSOS = { 
                            val serviceIntent = Intent(this@MainActivity, SOSForegroundService::class.java).apply {
                                action = SOSForegroundService.ACTION_STOP_EMERGENCY
                            }
                            startService(serviceIntent)
                        },
                        onStopService = { stopGuardianService() },
                        onSwitchMode = {
                            val nextMode = if (currentAppMode == AppMode.SENDER) AppMode.GUARDIAN else AppMode.SENDER
                            appModeManager.setAppMode(nextMode)
                            RoleManager.role = nextMode.name
                            
                            // Stop service before switching to ensure clean restart
                            stopGuardianService()
                            
                            // Restart activity to reset RoleManager and states
                            finish()
                            startActivity(intent)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    private fun startGuardianService() {
        val serviceIntent = Intent(this, SOSForegroundService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopGuardianService() {
        val serviceIntent = Intent(this, SOSForegroundService::class.java)
        stopService(serviceIntent)
        Log.d(tag, "Stop Service requested")
    }
}

@Composable
fun MainScreen(
    userManager: UserManager,
    viewModel: DashboardViewModel,
    appMode: AppMode,
    onPermissionsGranted: () -> Unit,
    onTriggerSOS: () -> Unit,
    onStopSOS: () -> Unit,
    onStopService: () -> Unit,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        if (permissions.entries.all { it.value }) {
            onPermissionsGranted()
        }
    }

    LaunchedEffect(Unit) {
        if (permissionsToRequest.all { userManager.hasPermission(it) }) {
            onPermissionsGranted()
        } else {
            launcher.launch(permissionsToRequest)
        }
    }

    var showAddContactDialog by remember { mutableStateOf(false) }

    DashboardScreen(
        viewModel = viewModel,
        appMode = appMode,
        onAddContactClick = { showAddContactDialog = true },
        onTriggerSOS = onTriggerSOS,
        onStopSOS = onStopSOS,
        onStopService = onStopService,
        onSwitchMode = onSwitchMode,
        modifier = modifier
    )

    if (showAddContactDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onValidateCode = { code, onResult ->
                viewModel.validateUserCode(code, onResult)
            },
            onAdd = { code, name, onResult ->
                viewModel.sendPairingRequest(code, name) { result ->
                    onResult(result)
                    if (result.isSuccess) {
                        android.widget.Toast.makeText(context, "Pairing request sent to $code", android.widget.Toast.LENGTH_LONG).show()
                        showAddContactDialog = false
                    }
                }
            }
        )
    }
}
