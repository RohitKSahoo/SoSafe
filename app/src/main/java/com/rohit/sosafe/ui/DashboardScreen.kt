package com.rohit.sosafe.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohit.sosafe.ui.theme.*

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddContactClick: () -> Unit,
    onTriggerSOS: () -> Unit,
    onStopService: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopBar(state.isProtectionActive, onStopService) },
        bottomBar = { 
            BottomNav(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            ) 
        },
        containerColor = DarkBackground,
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                0 -> { // Dashboard
                    UserCodeCard(state.userCode)

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatusCard(
                            title = "CONNECTION",
                            status = state.connectionStatus,
                            icon = Icons.Default.Wifi,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        StatusCard(
                            title = "BROADCAST",
                            status = state.broadcastStatus,
                            icon = Icons.Default.LocationOn,
                            isLive = state.isEmergency,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    ContactsSection(
                        contacts = state.contacts,
                        onAddContactClick = onAddContactClick
                    )
                }
                1 -> { // Uplinks (Full list)
                    Text("AUTHORIZED_UPLINKS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.contacts) { contact -> ContactItem(contact) }
                    }
                }
                2 -> { // Settings / System Config
                    SystemConfigSection(
                        onTriggerSOS = onTriggerSOS,
                        onStopService = onStopService
                    )
                }
            }
        }
    }
}

@Composable
fun TopBar(isProtectionActive: Boolean, onStopService: () -> Unit) {
    Row(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SOSAFE",
                style = MaterialTheme.typography.titleLarge,
                color = NeonCyan,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        if (isProtectionActive) {
            Surface(
                color = NeonCyanGlow,
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                modifier = Modifier.clickable { onStopService() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(NeonCyan, RoundedCornerShape(1.dp))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PROTECTION ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold
                    )
                }
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
            .clip(RoundedCornerShape(12.dp))
            .background(DarkCard)
            .border(1.dp, DarkStroke, RoundedCornerShape(12.dp))
            .padding(24.dp)
    ) {
        Text(text = "USER ACCESS CODE", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = userCode,
                style = MaterialTheme.typography.headlineMedium,
                color = NeonCyan
            )
            IconButton(onClick = {
                clipboardManager.setText(AnnotatedString(userCode.replace("-", "")))
                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = TextSecondary)
            }
        }
        Text(text = "SHARE CODE FOR SYNC", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
fun StatusCard(title: String, status: String, icon: ImageVector, isLive: Boolean = false, modifier: Modifier = Modifier) {
    val borderColor = if (isLive) NeonCyan else DarkStroke
    val glowColor = if (isLive) NeonCyanGlow else Color.Transparent

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkCard)
            .then(if (isLive) Modifier.background(glowColor) else Modifier)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = if (isLive) NeonCyan else TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(32.dp))
        Text(text = title, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        Text(text = status, style = MaterialTheme.typography.titleMedium, color = if (isLive) NeonCyan else TextPrimary, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ContactsSection(contacts: List<Contact>, onAddContactClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(text = "AUTHORIZED_CONTACTS [${String.format("%02d", contacts.size)}]", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Text(text = "LOGS_ALL", style = MaterialTheme.typography.labelSmall, color = NeonCyan, modifier = Modifier.clickable { })
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(contacts) { contact -> ContactItem(contact) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onAddContactClick,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "ADD_NEW_UPLINK", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun ContactItem(contact: Contact) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(DarkCard).border(1.dp, DarkStroke, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(NeonCyanGlow).border(1.dp, NeonCyan.copy(alpha = 0.5f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Terminal, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = contact.name, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (contact.status) {
                    ContactStatus.ONLINE -> StatusOnline
                    ContactStatus.OFFLINE -> StatusOffline
                    ContactStatus.PENDING -> StatusPending
                }
                Surface(color = statusColor.copy(alpha = 0.1f), shape = RoundedCornerShape(2.dp), border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)) {
                    Text(text = contact.status.name, style = MaterialTheme.typography.labelSmall, color = statusColor, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = contact.lastActive, style = MaterialTheme.typography.labelSmall, color = TextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun SystemConfigSection(
    onTriggerSOS: () -> Unit,
    onStopService: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "SYSTEM_CONFIGURATION", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        
        Card(
            modifier = Modifier.fillMaxWidth().clickable { onTriggerSOS() },
            colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed)
                Spacer(modifier = Modifier.width(16.dp))
                Text("MANUAL_SOS_TRIGGER", color = DangerRed, fontWeight = FontWeight.Bold)
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().clickable { onStopService() },
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkStroke)
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = TextSecondary)
                Spacer(modifier = Modifier.width(16.dp))
                Text("SHUTDOWN_GUARDIAN_DAEMON", color = TextPrimary)
            }
        }
    }
}

@Composable
fun BottomNav(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    NavigationBar(containerColor = DarkBackground, tonalElevation = 0.dp) {
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            icon = { Icon(Icons.Default.GridView, null) },
            label = { Text("MAIN_DASH", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, indicatorColor = Color.Transparent, unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            icon = { Icon(Icons.Default.People, null) },
            label = { Text("UPLINKS", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, indicatorColor = Color.Transparent, unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            icon = { Icon(Icons.Default.Settings, null) },
            label = { Text("SYS_CFG", fontSize = 10.sp) },
            colors = NavigationBarItemDefaults.colors(selectedIconColor = NeonCyan, selectedTextColor = NeonCyan, indicatorColor = Color.Transparent, unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary)
        )
    }
}