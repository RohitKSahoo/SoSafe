package com.rohit.sosafe.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.rohit.sosafe.data.AppMode
import com.rohit.sosafe.data.StreamingMode
import com.rohit.sosafe.data.contracts.SosSession
import com.rohit.sosafe.ui.theme.*
import com.rohit.sosafe.utils.RecordingInfo
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun GridBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val gridSpacing = 20.dp.toPx()
        val dotRadius = 1.dp.toPx()
        val color = LightGrey.copy(alpha = 0.15f)

        for (x in 0..(size.width / gridSpacing).toInt()) {
            for (y in 0..(size.height / gridSpacing).toInt()) {
                drawCircle(
                    color = color,
                    radius = dotRadius,
                    center = Offset(x * gridSpacing, y * gridSpacing)
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    appMode: AppMode,
    onAddContactClick: () -> Unit,
    onTriggerSOS: () -> Unit,
    onStopService: () -> Unit,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    
    var showMonitoringScreen by remember { mutableStateOf(false) }
    var selectedMonitoringSession by remember { mutableStateOf<SosSession?>(null) }
    var contactToRename by remember { mutableStateOf<Contact?>(null) }
    var contactForHistory by remember { mutableStateOf<Contact?>(null) }

    // Alert Popup Handling
    if (appMode == AppMode.GUARDIAN && state.activeEmergencySession != null) {
        val session = state.activeEmergencySession!!
        val senderContact = state.contacts.find { it.id == session.senderId }
        val displayName = senderContact?.name ?: "User ${session.senderId.take(4)}"

        AlertDialog(
            onDismissRequest = { viewModel.dismissSession(session.sessionId) },
            title = { Text("!!! EMERGENCY ALERT !!!", color = DangerRed, fontWeight = FontWeight.Bold) },
            text = { Text("Sender: $displayName has triggered an SOS.", color = PureWhite) },
            confirmButton = {
                Button(
                    onClick = { 
                        selectedMonitoringSession = session
                        showMonitoringScreen = true
                        viewModel.dismissSession(session.sessionId)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("VIEW LIVE", color = PureWhite)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSession(session.sessionId) }) {
                    Text("DISMISS", color = LightGrey)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    if (showMonitoringScreen && selectedMonitoringSession != null) {
        MonitoringScreen(
            session = selectedMonitoringSession!!,
            displayName = state.contacts.find { it.id == selectedMonitoringSession!!.senderId }?.name ?: "",
            onClose = { 
                showMonitoringScreen = false
                selectedMonitoringSession = null
            }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
            GridBackground()
            
            Scaffold(
                topBar = { TopBar(state.isProtectionActive, appMode, onStopService) },
                bottomBar = { 
                    BottomNav(
                        selectedTab = pagerState.currentPage,
                        onTabSelected = { page ->
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(page)
                            }
                        }
                    ) 
                },
                containerColor = Color.Transparent,
                modifier = modifier.fillMaxSize()
            ) { padding ->
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    verticalAlignment = Alignment.Top
                ) { page ->
                    Column(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxSize()
                    ) {
                        when (page) {
                            0 -> { // Dashboard
                                if (appMode == AppMode.SENDER) {
                                    SenderDashboard(
                                        state = state,
                                        onContactClick = { contact ->
                                            viewModel.loadRecordingsForUser(contact.id)
                                            contactForHistory = contact
                                        },
                                        onRenameClick = { contactToRename = it }
                                    )
                                } else {
                                    GuardianDashboard(
                                        state = state, 
                                        onAddContactClick = onAddContactClick,
                                        onContactClick = { contact ->
                                            if (contact.status == ContactStatus.EMERGENCY && contact.activeSession != null) {
                                                selectedMonitoringSession = contact.activeSession
                                                showMonitoringScreen = true
                                            } else {
                                                viewModel.loadRecordingsForUser(contact.id)
                                                contactForHistory = contact
                                            }
                                        },
                                        onRenameClick = { contactToRename = it }
                                    )
                                }
                            }
                            1 -> { // Settings
                                SystemConfigSection(
                                    appMode = appMode,
                                    streamingMode = state.streamingMode,
                                    onStreamingModeChange = { viewModel.setStreamingMode(it) },
                                    onTriggerSOS = onTriggerSOS,
                                    onStopService = onStopService,
                                    onSwitchMode = onSwitchMode
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (contactToRename != null) {
        RenameDialog(
            contact = contactToRename!!,
            onDismiss = { contactToRename = null },
            onConfirm = { newName ->
                viewModel.renameContact(contactToRename!!.id, newName)
                contactToRename = null
            }
        )
    }

    if (contactForHistory != null) {
        SessionHistoryDialog(
            contactName = contactForHistory!!.name,
            recordings = state.selectedUserRecordings,
            onDismiss = { contactForHistory = null }
        )
    }
}

@Composable
fun SessionHistoryDialog(
    contactName: String,
    recordings: List<RecordingInfo>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp).padding(16.dp),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = DarkGrey),
            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "RECORDINGS: $contactName", 
                    style = MaterialTheme.typography.titleMedium, 
                    color = PureWhite, 
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (recordings.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        Text("No recordings found", color = LightGrey, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recordings) { recording ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Black)
                                    .clickable {
                                        playRecording(recording.file, context)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SuccessGreen)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(recording.durationText, color = PureWhite, style = MaterialTheme.typography.bodyMedium)
                                    Text("ID: ${recording.sessionId.takeLast(6)}", color = LightGrey, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MediumGrey)
                ) {
                    Text("CLOSE")
                }
            }
        }
    }
}

private fun playRecording(file: File, context: android.content.Context) {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No app to play audio found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun RenameDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(contact.name) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = DarkGrey),
            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
        ) {
            Column(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
                Text("RENAME USER", style = MaterialTheme.typography.headlineSmall, color = PureWhite, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Update name for ${contact.id}", style = MaterialTheme.typography.bodySmall, color = LightGrey)
                Spacer(modifier = Modifier.height(24.dp))

                TextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Black,
                        unfocusedContainerColor = Black,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedIndicatorColor = PureWhite,
                        unfocusedIndicatorColor = MediumGrey
                    ),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("CANCEL", color = LightGrey) }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { onConfirm(name) },
                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("SAVE", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun SenderDashboard(state: DashboardState, onContactClick: (Contact) -> Unit, onRenameClick: (Contact) -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        UserCodeCard(state.userCode)
        Spacer(modifier = Modifier.height(24.dp))
        Text("SERVICE STATUS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatusCard(
                title = "NETWORK",
                status = if (state.connectionStatus == "STABLE") "CONNECTED" else "OFFLINE",
                icon = Icons.Default.Wifi,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(16.dp))
            StatusCard(
                title = "BROADCAST",
                status = if (state.isEmergency) "LIVE" else "READY",
                icon = Icons.Default.Radio,
                isLive = state.isEmergency,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        ContactsSection(
            title = "LINKED GUARDIANS (Tap name for history)", 
            contacts = state.contacts, 
            showAddButton = false,
            onContactClick = onContactClick,
            onRenameClick = onRenameClick
        )
    }
}

@Composable
fun GuardianDashboard(
    state: DashboardState, 
    onAddContactClick: () -> Unit,
    onContactClick: (Contact) -> Unit,
    onRenameClick: (Contact) -> Unit
) {
    Column {
        ContactsSection(
            title = "PROTECTED USERS (Tap name for history)", 
            contacts = state.contacts, 
            showAddButton = true,
            onAddContactClick = onAddContactClick,
            onContactClick = onContactClick,
            onRenameClick = onRenameClick
        )
    }
}

@Composable
fun TopBar(isProtectionActive: Boolean, appMode: AppMode, onStopService: () -> Unit) {
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "SOSAFE",
                style = MaterialTheme.typography.headlineSmall,
                color = PureWhite,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = if (appMode == AppMode.SENDER) {
                    if (isProtectionActive) "PROTECTION ENABLED" else "SYSTEM IDLE"
                } else "GUARDIAN MODE ACTIVE",
                style = MaterialTheme.typography.labelSmall,
                color = if (appMode == AppMode.SENDER && isProtectionActive) SuccessGreen else LightGrey
            )
        }

        if (appMode == AppMode.SENDER && isProtectionActive) {
            IconButton(
                onClick = onStopService,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MediumGrey)
                    .size(40.dp)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = "Stop", tint = PureWhite, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun UserCodeCard(userCode: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DarkCard)
            .clickable {
                clipboardManager.setText(AnnotatedString(userCode.replace("-", "")))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }
            .padding(24.dp)
    ) {
        Text(text = "MY ID", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = userCode,
                style = MaterialTheme.typography.headlineLarge,
                color = PureWhite,
                fontWeight = FontWeight.Light
            )
            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = LightGrey, modifier = Modifier.size(20.dp))
        }
        Text(text = "Share this ID with your guardians", style = MaterialTheme.typography.labelSmall, color = LightGrey)
    }
}

@Composable
fun StatusCard(title: String, status: String, icon: ImageVector, isLive: Boolean = false, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isLive) PureWhite else DarkCard)
            .padding(20.dp)
    ) {
        Icon(
            imageVector = icon, 
            contentDescription = null, 
            tint = if (isLive) Black else TextSecondary, 
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = if (isLive) Black.copy(alpha = 0.6f) else TextSecondary)
        Text(
            text = status, 
            style = MaterialTheme.typography.titleMedium, 
            color = if (isLive) Black else TextPrimary, 
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun ContactsSection(
    title: String, 
    contacts: List<Contact>, 
    showAddButton: Boolean,
    onAddContactClick: () -> Unit = {},
    onContactClick: (Contact) -> Unit = {},
    onRenameClick: (Contact) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .border(1.dp, DarkStroke, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("NO USERS LINKED", style = MaterialTheme.typography.labelSmall, color = LightGrey)
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts) { contact -> 
                    ContactItem(
                        contact = contact, 
                        onClick = { onContactClick(contact) },
                        onRenameClick = { onRenameClick(contact) }
                    ) 
                }
            }
        }
        
        if (showAddButton) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = onAddContactClick,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "ADD USER", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ContactItem(contact: Contact, onClick: () -> Unit, onRenameClick: () -> Unit) {
    val isEmergency = contact.status == ContactStatus.EMERGENCY
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (isEmergency) DangerRed.copy(alpha = 0.2f) else DarkCard)
            .border(
                width = if (isEmergency) 2.dp else 0.dp,
                color = if (isEmergency) DangerRed else Color.Transparent,
                shape = RoundedCornerShape(4.dp)
            )
            .clickable { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isEmergency) DangerRed else MediumGrey),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isEmergency) Icons.Default.Warning else Icons.Default.Person, 
                contentDescription = null, 
                tint = PureWhite, 
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contact.name, 
                style = MaterialTheme.typography.bodyLarge, 
                color = TextPrimary, 
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when(contact.status) {
                    ContactStatus.EMERGENCY -> "!!! SOS ACTIVE !!!"
                    ContactStatus.ONLINE -> "READY"
                    else -> "IDLE"
                }, 
                style = MaterialTheme.typography.labelSmall, 
                color = if (isEmergency) DangerRed else if (contact.status == ContactStatus.ONLINE) SuccessGreen else LightGrey,
                fontWeight = if (isEmergency) FontWeight.Bold else FontWeight.Normal
            )
        }
        
        if (isEmergency) {
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(2.dp)
            ) {
                Text("VIEW", color = PureWhite, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            IconButton(onClick = onRenameClick) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = LightGrey, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun SystemConfigSection(
    appMode: AppMode, 
    streamingMode: StreamingMode,
    onStreamingModeChange: (StreamingMode) -> Unit,
    onTriggerSOS: () -> Unit, 
    onStopService: () -> Unit, 
    onSwitchMode: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (appMode == AppMode.SENDER) {
            Text(text = "EMERGENCY ACTIONS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onTriggerSOS() },
                colors = CardDefaults.cardColors(containerColor = DangerRed),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = PureWhite)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("TRIGGER MANUAL SOS", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(text = "DEBUG: AUDIO STREAMING MODE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkStroke)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StreamingMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onStreamingModeChange(mode) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = streamingMode == mode,
                            onClick = { onStreamingModeChange(mode) },
                            colors = RadioButtonDefaults.colors(selectedColor = PureWhite, unselectedColor = LightGrey)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = mode.label, color = if (streamingMode == mode) PureWhite else LightGrey)
                    }
                }
            }
        }

        Text(text = "SYSTEM SETTINGS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)

        if (appMode == AppMode.SENDER) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onStopService() },
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkStroke)
            ) {
                Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = LightGrey)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("SHUTDOWN SERVICE", color = TextPrimary)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { onSwitchMode() },
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkStroke)
        ) {
            Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SyncAlt, contentDescription = null, tint = LightGrey)
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = if (appMode == AppMode.SENDER) "SWITCH TO GUARDIAN MODE" else "SWITCH TO SENDER MODE",
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
fun BottomNav(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(containerColor = Black, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Default.GridView, null) },
            label = { Text("DASHBOARD") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = PureWhite, 
                selectedTextColor = PureWhite, 
                indicatorColor = MediumGrey, 
                unselectedIconColor = LightGrey, 
                unselectedTextColor = LightGrey
            )
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Default.Settings, null) },
            label = { Text("SYSTEM") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = PureWhite, 
                selectedTextColor = PureWhite, 
                indicatorColor = MediumGrey, 
                unselectedIconColor = LightGrey, 
                unselectedTextColor = LightGrey
            )
        )
    }
}
