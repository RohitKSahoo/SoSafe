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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rohit.sosafe.ui.theme.SoSafeTheme
import com.rohit.sosafe.data.UserManager
import com.rohit.sosafe.utils.SOSTriggerManager
import com.rohit.sosafe.service.SOSForegroundService
import com.rohit.sosafe.ui.DashboardScreen
import com.rohit.sosafe.ui.DashboardViewModel
import com.rohit.sosafe.ui.DashboardViewModelFactory

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
                val viewModel: DashboardViewModel = viewModel(
                    factory = DashboardViewModelFactory(userManager)
                )
                
                MainScreen(
                    userManager = userManager,
                    viewModel = viewModel,
                    onPermissionsGranted = { startGuardianService() },
                    onTriggerSOS = { sosTriggerManager.manualTrigger() },
                    onStopService = { stopGuardianService() },
                    modifier = Modifier.fillMaxSize()
                )
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
        Log.d(TAG, "Stop Service requested")
    }
}

@Composable
fun MainScreen(
    userManager: UserManager,
    viewModel: DashboardViewModel,
    onPermissionsGranted: () -> Unit,
    onTriggerSOS: () -> Unit,
    onStopService: () -> Unit,
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
        onAddContactClick = { showAddContactDialog = true },
        onTriggerSOS = onTriggerSOS,
        onStopService = onStopService,
        modifier = modifier
    )

    if (showAddContactDialog) {
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onAdd = { code ->
                viewModel.addContact(code) { result ->
                    if (result.isSuccess) {
                        showAddContactDialog = false
                    }
                }
            }
        )
    }
}

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add New Contact") },
        text = {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("User Access Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onAdd(code) }) {
                Text("ADD")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}