package com.rohit.sosafe.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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
    onAddContactClick: () -> Unit, // Keeping parameter for now to avoid breaking factory but won't use it
    onTriggerSOS: () -> Unit,
    onStopService: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    Box(modifier = Modifier.fillMaxSize().background(DarkBackground)) {
        GridBackground()
        
        Scaffold(
            topBar = { TopBar(state.isProtectionActive, onStopService) },
            bottomBar = { 
                BottomNav(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                ) 
            },
            containerColor = Color.Transparent,
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
                            contacts = state.contacts
                        )
                    }
                    1 -> { // Settings
                        SystemConfigSection(
                            onTriggerSOS = onTriggerSOS,
                            onStopService = onStopService
                        )
                    }
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
                text = if (isProtectionActive) "PROTECTION ENABLED" else "SYSTEM IDLE",
                style = MaterialTheme.typography.labelSmall,
                color = if (isProtectionActive) SuccessGreen else LightGrey
            )
        }

        if (isProtectionActive) {
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
fun ContactsSection(contacts: List<Contact>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = "LINKED GUARDIANS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(16.dp))
        
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .border(1.dp, DarkStroke, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("NO GUARDIANS LINKED YET", style = MaterialTheme.typography.labelSmall, color = LightGrey)
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contacts) { contact -> ContactItem(contact) }
            }
        }
    }
}

@Composable
fun ContactItem(contact: Contact) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DarkCard)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MediumGrey),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = PureWhite, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = contact.name, style = MaterialTheme.typography.bodyLarge, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (contact.status == ContactStatus.ONLINE) "ACTIVE" else "IDLE", 
                style = MaterialTheme.typography.labelSmall, 
                color = if (contact.status == ContactStatus.ONLINE) SuccessGreen else LightGrey
            )
        }
    }
}

@Composable
fun SystemConfigSection(onTriggerSOS: () -> Unit, onStopService: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

        Text(text = "SYSTEM SETTINGS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)

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
