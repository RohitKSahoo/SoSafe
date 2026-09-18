package com.rohit.sosafe.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.rohit.sosafe.utils.RecordingInfo
import com.rohit.sosafe.utils.QrCodeUtils
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun GridBackground(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val gridSpacing = 20.dp.toPx()
        val dotRadius = 1.dp.toPx()
        val color = LightGrey.copy(alpha = 0.2f)

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
    onStopSOS: () -> Unit = {},
    onStopService: () -> Unit,
    onSwitchMode: () -> Unit,
    deepLinkPairData: ScannedQrData? = null,
    onClearDeepLinkPairData: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    
    var showMonitoringScreen by remember { mutableStateOf(false) }
    var selectedMonitoringSession by remember { mutableStateOf<SosSession?>(null) }
    var contactToRename by remember { mutableStateOf<Contact?>(null) }
    var contactToRemove by remember { mutableStateOf<Contact?>(null) }
    var contactForHistory by remember { mutableStateOf<Contact?>(null) }
    var selectedHistoryContact by remember { mutableStateOf<Contact?>(null) }
    var showMyQrDialog by remember { mutableStateOf(false) }
    var showQrScannerDialog by remember { mutableStateOf(false) }
    var scannedQrData by remember { mutableStateOf<ScannedQrData?>(null) }
    var showNamePromptDialog by remember { mutableStateOf(false) }
    var sharerNameInput by remember { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(deepLinkPairData) {
        if (deepLinkPairData != null) {
            scannedQrData = deepLinkPairData
            onClearDeepLinkPairData()
        }
    }

    // My QR Code Display Dialog
    if (showMyQrDialog) {
        val rawCode = state.userCode.replace("-", "")
        val currentSharerName = sharerNameInput.trim().ifBlank { "User $rawCode" }
        val encodedSharerName = Uri.encode(currentSharerName)
        val shareableUrl = "https://rohitksahoo.github.io/SoSafe/pair?id=$rawCode&name=$encodedSharerName"

        val qrBitmap = remember(rawCode, currentSharerName) {
            QrCodeUtils.generateQrCodeBitmap("sosafe://pair?id=$rawCode&name=$encodedSharerName", size = 600)
        }

        AlertDialog(
            onDismissRequest = { showMyQrDialog = false },
            title = { Text("MY PAIRING QR CODE", color = PureWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Have your guardian scan this QR code to link instantly.", color = LightGrey, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "My QR Code",
                            modifier = Modifier
                                .size(210.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(PureWhite)
                                .padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("ID: ${state.userCode}", color = SuccessGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Shareable Link Container directly below the QR
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MediumGrey)
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "PAIRING INVITE LINK",
                            color = PureWhite,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = shareableUrl,
                            color = LightGrey,
                            fontSize = 11.sp,
                            maxLines = 2,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Black)
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    showNamePromptDialog = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("SHARE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("SoSafe Pairing Link", shareableUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Link copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = DarkGrey, contentColor = PureWhite),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("COPY", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showMyQrDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MediumGrey, contentColor = PureWhite),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("CLOSE", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    // Pre-Share Name Dialog (Allows sharer to set/confirm their name before sharing the link)
    if (showNamePromptDialog) {
        val rawCode = state.userCode.replace("-", "")
        AlertDialog(
            onDismissRequest = { showNamePromptDialog = false },
            title = { Text("ENTER YOUR NAME", color = PureWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter your name so the other person knows who is sharing this link:",
                        color = LightGrey,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("YOUR NAME", color = PureWhite, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = sharerNameInput,
                        onValueChange = { sharerNameInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Black,
                            unfocusedContainerColor = Black,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedIndicatorColor = PureWhite,
                            unfocusedIndicatorColor = MediumGrey
                        ),
                        placeholder = { Text("E.g. Mom, Dad", color = MediumGrey) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNamePromptDialog = false
                        val finalName = sharerNameInput.trim().ifBlank { "User $rawCode" }
                        val encoded = Uri.encode(finalName)
                        val fullUrl = "https://rohitksahoo.github.io/SoSafe/pair?id=$rawCode&name=$encoded"
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "SoSafe Pairing Invite")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Add me on SoSafe for emergency safety & live monitoring!\n\nTap to connect with $finalName:\n$fullUrl"
                            )
                        }
                        val chooser = Intent.createChooser(sendIntent, "Share Pairing Link via")
                        context.startActivity(chooser)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Black),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("SHARE LINK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNamePromptDialog = false }) {
                    Text("CANCEL", color = LightGrey)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    // QR Camera Scanner Dialog
    if (showQrScannerDialog) {
        QrScannerDialog(
            onDismissRequest = { showQrScannerDialog = false },
            onQrScanned = { data ->
                showQrScannerDialog = false
                scannedQrData = data
            }
        )
    }

    // Scanned QR / Deep Link Naming Confirmation Dialog
    if (scannedQrData != null) {
        val data = scannedQrData!!
        var customContactName by remember(data.userId) { mutableStateOf(data.userName.ifBlank { "" }) }
        AlertDialog(
            onDismissRequest = { scannedQrData = null },
            title = { Text("LINK CONTACT", color = PureWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Link with ${data.userName} (${data.userId})", color = LightGrey, style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("NAME THIS CONTACT", color = PureWhite, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = customContactName,
                        onValueChange = { customContactName = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Black,
                            unfocusedContainerColor = Black,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedIndicatorColor = PureWhite,
                            unfocusedIndicatorColor = MediumGrey
                        ),
                        placeholder = { Text("Enter name (e.g. Mom, Rohit)", color = MediumGrey) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetId = data.userId
                        val nameToSave = customContactName.ifBlank { data.userName }
                        scannedQrData = null
                        viewModel.pairViaQrCode(targetId, nameToSave) { res ->
                            if (res.isSuccess) {
                                Toast.makeText(context, "Linked with $nameToSave successfully!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Pairing failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Black),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("SAVE & LINK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedQrData = null }) {
                    Text("CANCEL", color = LightGrey)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    // Auto popup on Sender device when a new Guardian links via QR code
    var newlyLinkedContactToName by remember { mutableStateOf<Contact?>(null) }
    val promptDismissedContacts = remember { mutableStateOf(setOf<String>()) }
    
    LaunchedEffect(state.contacts, appMode) {
        if (appMode == AppMode.SENDER) {
            val unrenamedContact = state.contacts.find { contact ->
                (contact.name.startsWith("User ", ignoreCase = true) || contact.name.startsWith("USER_", ignoreCase = true)) &&
                        !promptDismissedContacts.value.contains(contact.id)
            }
            if (unrenamedContact != null && newlyLinkedContactToName == null) {
                newlyLinkedContactToName = unrenamedContact
            }
        }
    }

    if (newlyLinkedContactToName != null) {
        val targetContact = newlyLinkedContactToName!!
        var senderSideName by remember(targetContact.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                promptDismissedContacts.value = promptDismissedContacts.value + targetContact.id
                newlyLinkedContactToName = null
            },
            title = { Text("GUARDIAN LINKED", color = PureWhite, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "A new guardian (${targetContact.id}) has linked with your device.",
                        color = LightGrey,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "NAME THIS GUARDIAN",
                        color = PureWhite,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = senderSideName,
                        onValueChange = { senderSideName = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Black,
                            unfocusedContainerColor = Black,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedIndicatorColor = PureWhite,
                            unfocusedIndicatorColor = MediumGrey
                        ),
                        placeholder = { Text("Enter name...", color = MediumGrey) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameToSet = senderSideName.ifBlank { targetContact.name }
                        viewModel.renameContact(targetContact.id, nameToSet)
                        promptDismissedContacts.value = promptDismissedContacts.value + targetContact.id
                        newlyLinkedContactToName = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("SAVE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    promptDismissedContacts.value = promptDismissedContacts.value + targetContact.id
                    newlyLinkedContactToName = null
                }) {
                    Text("SKIP", color = LightGrey)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

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

    // Pairing Request Dialog Handling (2-Step Guardian Confirmation with custom renaming & 10-min countdown)
    if (state.pendingPairingRequests.isNotEmpty()) {
        val request = state.pendingPairingRequests.first()
        val requesterName = request.fromUserName.ifBlank { "User ${request.fromUserId}" }
        var contactCustomName by remember(request.requestId) { 
            mutableStateOf("") 
        }

        var remainingSeconds by remember(request.requestId, request.createdAt) {
            val elapsed = (System.currentTimeMillis() - request.createdAt) / 1000
            val initial = (600 - elapsed).coerceAtLeast(0)
            mutableStateOf(initial)
        }

        LaunchedEffect(request.requestId, request.createdAt) {
            while (remainingSeconds > 0) {
                kotlinx.coroutines.delay(1000)
                val elapsed = (System.currentTimeMillis() - request.createdAt) / 1000
                remainingSeconds = (600 - elapsed).coerceAtLeast(0)
            }
        }

        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        val timeDisplay = String.format("%02d:%02d", minutes, seconds)

        AlertDialog(
            onDismissRequest = { viewModel.declinePairingRequest(request) },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("LINK REQUEST", color = PureWhite, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (remainingSeconds > 0) "⏱ $timeDisplay" else "EXPIRED",
                        color = if (remainingSeconds > 60) SuccessGreen else DangerRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = { 
                Column {
                    Text(
                        "$requesterName (${request.fromUserId}) wants to add you as a contact/guardian.",
                        color = LightGrey,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "NAME THIS CONTACT",
                        color = PureWhite,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = contactCustomName,
                        onValueChange = { contactCustomName = it },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Black,
                            unfocusedContainerColor = Black,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedIndicatorColor = PureWhite,
                            unfocusedIndicatorColor = MediumGrey
                        ),
                        placeholder = { Text("E.G. Mom, Guardian 1", color = MediumGrey) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { 
                        if (remainingSeconds > 0) {
                            viewModel.acceptPairingRequest(request, contactCustomName)
                        } else {
                            viewModel.declinePairingRequest(request)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (remainingSeconds > 0) SuccessGreen else MediumGrey, 
                        contentColor = Black
                    ),
                    shape = RoundedCornerShape(4.dp),
                    enabled = remainingSeconds > 0
                ) {
                    Text(if (remainingSeconds > 0) "ACCEPT" else "EXPIRED", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declinePairingRequest(request) }) {
                    Text("DECLINE", color = LightGrey)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    // Expired Pairing Request Alert Dialog
    if (state.expiredPairingNotice != null) {
        val expiredReq = state.expiredPairingNotice!!
        val senderLabel = expiredReq.fromUserName.ifBlank { "User ${expiredReq.fromUserId}" }
        AlertDialog(
            onDismissRequest = { viewModel.clearExpiredNotice() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = DangerRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("PAIRING EXPIRED", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    text = "The pairing request from $senderLabel (${expiredReq.fromUserId}) has expired (10-minute limit). Please ask them to send a new pairing request.",
                    color = LightGrey,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearExpiredNotice() },
                    colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    // Contact Removal Confirmation Dialog
    if (contactToRemove != null) {
        val target = contactToRemove!!
        AlertDialog(
            onDismissRequest = { contactToRemove = null },
            title = { Text("REMOVE CONTACT", color = DangerRed, fontWeight = FontWeight.Bold) },
            text = { 
                Text(
                    "Are you sure you want to remove ${target.name} (${target.id}) from your contacts? They will be unlinked and notified.",
                    color = PureWhite
                ) 
            },
            confirmButton = {
                Button(
                    onClick = { 
                        viewModel.removeContact(target.id)
                        contactToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = PureWhite),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("REMOVE", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToRemove = null }) {
                    Text("CANCEL", color = LightGrey)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    // Removal Notification Alert Dialog (Received on the other device)
    if (state.pendingRemovalNotifications.isNotEmpty()) {
        val notification = state.pendingRemovalNotifications.first()
        val localSavedContact = state.contacts.find { it.id == notification.removerId }
        val removerDisplayName = when {
            localSavedContact != null && localSavedContact.name.isNotBlank() -> localSavedContact.name
            notification.removerName.isNotBlank() && !notification.removerName.startsWith("User ") -> notification.removerName
            else -> "User ${notification.removerId}"
        }

        AlertDialog(
            onDismissRequest = { viewModel.dismissRemovalNotification(notification) },
            title = { Text("CONTACT REMOVED", color = DangerRed, fontWeight = FontWeight.Bold) },
            text = { 
                Text(
                    "$removerDisplayName has removed you from their emergency contacts list.",
                    color = PureWhite
                ) 
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissRemovalNotification(notification) },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = PureWhite),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkGrey,
            shape = RoundedCornerShape(4.dp)
        )
    }

    val activeSessionFromState = remember(state.contacts, selectedMonitoringSession?.sessionId, selectedMonitoringSession?.senderId) {
        if (selectedMonitoringSession != null) {
            state.contacts.find { it.id == selectedMonitoringSession?.senderId }?.activeSession ?: selectedMonitoringSession
        } else null
    }

    if (showMonitoringScreen && (activeSessionFromState != null || state.selectedPlaybackRecording != null)) {
        MonitoringScreen(
            session = activeSessionFromState,
            playbackInfo = state.selectedPlaybackRecording,
            displayName = if (state.selectedPlaybackRecording != null) {
                selectedHistoryContact?.name ?: contactForHistory?.name ?: ""
            } else {
                val sId = selectedMonitoringSession?.senderId ?: activeSessionFromState?.senderId ?: ""
                state.contacts.find { it.id == sId }?.name ?: ""
            },
            onClose = { 
                showMonitoringScreen = false
                selectedMonitoringSession = null
                selectedHistoryContact = null
                viewModel.selectPlaybackRecording(null)
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
                                            if (contact.status == ContactStatus.EMERGENCY && contact.activeSession != null) {
                                                selectedMonitoringSession = contact.activeSession
                                                showMonitoringScreen = true
                                            } else {
                                                viewModel.loadRecordingsForUser(contact.id)
                                                contactForHistory = contact
                                            }
                                        },
                                        onRenameClick = { contactToRename = it },
                                        onRemoveClick = { contactToRemove = it },
                                        onStopSOS = onStopSOS,
                                        onShowQrClick = { showMyQrDialog = true }
                                    )
                                } else {
                                    GuardianDashboard(
                                        state = state, 
                                        onAddContactClick = onAddContactClick,
                                        onScanQrClick = { showQrScannerDialog = true },
                                        onContactClick = { contact ->
                                            if (contact.status == ContactStatus.EMERGENCY && contact.activeSession != null) {
                                                selectedMonitoringSession = contact.activeSession
                                                showMonitoringScreen = true
                                            } else {
                                                viewModel.loadRecordingsForUser(contact.id)
                                                contactForHistory = contact
                                            }
                                        },
                                        onRenameClick = { contactToRename = it },
                                        onRemoveClick = { contactToRemove = it }
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
            userId = contactForHistory!!.id,
            contactName = contactForHistory!!.name,
            recordings = state.selectedUserRecordings,
            onDismiss = { contactForHistory = null },
            onPlayRecording = { recording ->
                selectedHistoryContact = contactForHistory
                viewModel.selectPlaybackRecording(recording)
                showMonitoringScreen = true
                contactForHistory = null
            },
            onDeleteRecording = { userId, sessionId ->
                viewModel.deleteRecording(userId, sessionId)
            }
        )
    }
}

@Composable
fun SessionHistoryDialog(
    userId: String,
    contactName: String,
    recordings: List<RecordingInfo>,
    onDismiss: () -> Unit,
    onPlayRecording: (RecordingInfo) -> Unit,
    onDeleteRecording: (String, String) -> Unit
) {
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
                                        onPlayRecording(recording)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = SuccessGreen)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(recording.durationText, color = PureWhite, style = MaterialTheme.typography.bodyMedium)
                                }

                                IconButton(
                                    onClick = {
                                        onDeleteRecording(userId, recording.sessionId)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete Recording",
                                        tint = DangerRed,
                                        modifier = Modifier.size(20.dp)
                                    )
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
fun SenderDashboard(
    state: DashboardState, 
    onContactClick: (Contact) -> Unit, 
    onRenameClick: (Contact) -> Unit,
    onRemoveClick: (Contact) -> Unit,
    onStopSOS: () -> Unit,
    onShowQrClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationManager = remember(context) {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    var hasDndAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.isNotificationPolicyAccessGranted
            } else {
                true
            }
        )
    }
    var hasOverlayAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
                    hasOverlayAccess = Settings.canDrawOverlays(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        UserCodeCard(
            userCode = state.userCode,
            onShowQrClick = onShowQrClick,
            onScanQrClick = null
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (!hasDndAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            DndAccessCard(
                onGrantClick = {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Open Settings > Do Not Disturb Access", Toast.LENGTH_LONG).show()
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!hasOverlayAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            OverlayAccessCard(
                onGrantClick = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            context.startActivity(fallbackIntent)
                        } catch (e2: Exception) {
                            Toast.makeText(context, "Open Settings > Display over other apps", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
        
        if (state.isEmergency) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onStopSOS() },
                colors = CardDefaults.cardColors(containerColor = DangerRed),
                shape = RoundedCornerShape(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, tint = PureWhite, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("STOP EMERGENCY SOS", color = PureWhite, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text("SERVICE STATUS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            val netIcon = when {
                !state.isNetworkConnected -> Icons.Default.WifiOff
                state.networkType == "MOBILE DATA" -> Icons.Default.SignalCellular4Bar
                else -> Icons.Default.Wifi
            }
            val (signalLabel, signalColor) = when {
                !state.isNetworkConnected -> Pair("NO NETWORK", DangerRed)
                state.networkQuality.contains("EXCELLENT") -> Pair("EXCELLENT", SuccessGreen)
                else -> Pair("POOR", Color(0xFF2196F3))
            }

            StatusCard(
                status = if (state.isNetworkConnected) "CONNECTED" else "DISCONNECTED",
                subtitle = "SIGNAL: $signalLabel",
                icon = netIcon,
                statusColor = signalColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(16.dp))

            val (broadcastIcon, broadcastLabel) = when (state.streamingMode) {
                StreamingMode.WEBRTC_ONLY -> Pair(Icons.Default.GraphicEq, "WEBRTC MODE")
                StreamingMode.CHUNK_ONLY -> Pair(Icons.Default.CloudUpload, "CHUNK MODE")
                StreamingMode.HYBRID -> Pair(Icons.Default.Radio, "HYBRID MODE")
            }

            StatusCard(
                status = if (state.isEmergency) "LIVE" else "READY",
                subtitle = broadcastLabel,
                icon = broadcastIcon,
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
            onRenameClick = onRenameClick,
            onRemoveClick = onRemoveClick
        )
    }
}

@Composable
fun GuardianDashboard(
    state: DashboardState, 
    onAddContactClick: () -> Unit,
    onScanQrClick: () -> Unit,
    onContactClick: (Contact) -> Unit,
    onRenameClick: (Contact) -> Unit,
    onRemoveClick: (Contact) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationManager = remember(context) {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }
    var hasDndAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.isNotificationPolicyAccessGranted
            } else {
                true
            }
        )
    }
    var hasOverlayAccess by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    hasDndAccess = notificationManager.isNotificationPolicyAccessGranted
                    hasOverlayAccess = Settings.canDrawOverlays(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        UserCodeCard(
            userCode = state.userCode,
            onShowQrClick = null,
            onScanQrClick = onScanQrClick
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (!hasDndAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            DndAccessCard(
                onGrantClick = {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Open Settings > Do Not Disturb Access", Toast.LENGTH_LONG).show()
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (!hasOverlayAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            OverlayAccessCard(
                onGrantClick = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        try {
                            val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            context.startActivity(fallbackIntent)
                        } catch (e2: Exception) {
                            Toast.makeText(context, "Open Settings > Display over other apps", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        Text("SERVICE STATUS", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            val netIcon = when {
                !state.isNetworkConnected -> Icons.Default.WifiOff
                state.networkType == "MOBILE DATA" -> Icons.Default.SignalCellular4Bar
                else -> Icons.Default.Wifi
            }
            val (signalLabel, signalColor) = when {
                !state.isNetworkConnected -> Pair("NO NETWORK", DangerRed)
                state.networkQuality.contains("EXCELLENT") -> Pair("EXCELLENT", SuccessGreen)
                else -> Pair("POOR", Color(0xFF2196F3))
            }

            StatusCard(
                status = if (state.isNetworkConnected) "CONNECTED" else "DISCONNECTED",
                subtitle = "SIGNAL: $signalLabel",
                icon = netIcon,
                statusColor = signalColor,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(16.dp))

            val (broadcastIcon, broadcastLabel) = when (state.streamingMode) {
                StreamingMode.WEBRTC_ONLY -> Pair(Icons.Default.GraphicEq, "WEBRTC MODE")
                StreamingMode.CHUNK_ONLY -> Pair(Icons.Default.CloudUpload, "CHUNK MODE")
                StreamingMode.HYBRID -> Pair(Icons.Default.Radio, "HYBRID MODE")
            }

            StatusCard(
                status = "ACTIVE",
                subtitle = broadcastLabel,
                icon = broadcastIcon,
                isLive = false,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        ContactsSection(
            title = "PROTECTED USERS (Tap name for history)", 
            contacts = state.contacts, 
            showAddButton = true,
            onAddContactClick = onAddContactClick,
            onContactClick = onContactClick,
            onRenameClick = onRenameClick,
            onRemoveClick = onRemoveClick
        )
    }
}

@Composable
fun DndAccessCard(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DarkCard)
            .border(1.dp, DangerRed.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsActive,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "ALLOW SIREN IN DND MODE",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Emergency SOS alerts cannot break through Do Not Disturb or Silent mode unless granted system access. Tap below to enable siren override.",
            style = MaterialTheme.typography.bodySmall,
            color = LightGrey
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onGrantClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = PureWhite),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text("ENABLE DND OVERRIDE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
fun OverlayAccessCard(
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DarkCard)
            .border(1.dp, DangerRed.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "APPEAR ON TOP (OVERLAY)",
                style = MaterialTheme.typography.titleMedium,
                color = PureWhite,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "To automatically launch the emergency monitor screen immediately when an SOS arrives (even when phone is unlocked, in other apps, or cleared from recents), enable Appear on Top permission.",
            style = MaterialTheme.typography.bodySmall,
            color = LightGrey
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onGrantClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = DangerRed, contentColor = PureWhite),
            shape = RoundedCornerShape(4.dp)
        ) {
            Text("ENABLE APPEAR ON TOP", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "SOSAFE",
                    style = MaterialTheme.typography.headlineLarge,
                    fontSize = 42.sp,
                    color = PureWhite,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Spacer(modifier = Modifier.width(12.dp))
                if (appMode == AppMode.GUARDIAN) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Guardian Mode",
                        tint = PureWhite,
                        modifier = Modifier.size(34.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Sos,
                        contentDescription = "Sender Mode",
                        tint = DangerRed,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Text(
                text = if (appMode == AppMode.SENDER) {
                    if (isProtectionActive) "PROTECTION ENABLED" else "SYSTEM IDLE"
                } else "GUARDIAN MODE ACTIVE",
                style = MaterialTheme.typography.labelSmall,
                color = if (appMode == AppMode.SENDER && isProtectionActive) SuccessGreen else if (appMode == AppMode.GUARDIAN) SuccessGreen else LightGrey
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
fun UserCodeCard(
    userCode: String,
    onShowQrClick: (() -> Unit)? = null,
    onScanQrClick: (() -> Unit)? = null
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(DarkCard)
            .padding(20.dp)
    ) {
        Text(text = "MY ID", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    clipboardManager.setText(AnnotatedString(userCode.replace("-", "")))
                    Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                },
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "Share this ID or scan QR code to link instantly", style = MaterialTheme.typography.labelSmall, color = LightGrey)

        if (onShowQrClick != null || onScanQrClick != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                if (onShowQrClick != null) {
                    Button(
                        onClick = onShowQrClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MY QR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (onShowQrClick != null && onScanQrClick != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (onScanQrClick != null) {
                    Button(
                        onClick = onScanQrClick,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("SCAN QR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun StatusCard(
    status: String,
    icon: ImageVector,
    subtitle: String? = null,
    statusColor: Color? = null,
    isLive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isLive) PureWhite else DarkCard)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon, 
                contentDescription = null, 
                tint = if (isLive) Black else (statusColor ?: TextSecondary), 
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = status, 
                style = MaterialTheme.typography.titleMedium, 
                color = if (isLive) Black else PureWhite, 
                fontWeight = FontWeight.Bold
            )
        }
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (isLive) Black.copy(alpha = 0.7f) else LightGrey,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun ContactsSection(
    title: String, 
    contacts: List<Contact>, 
    showAddButton: Boolean,
    onAddContactClick: () -> Unit = {},
    onContactClick: (Contact) -> Unit = {},
    onRenameClick: (Contact) -> Unit = {},
    onRemoveClick: (Contact) -> Unit = {}
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
                        onRenameClick = { onRenameClick(contact) },
                        onRemoveClick = { onRemoveClick(contact) }
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
fun ContactItem(contact: Contact, onClick: () -> Unit, onRenameClick: () -> Unit, onRemoveClick: () -> Unit) {
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
            val context = LocalContext.current
            val latestRecordingDate = remember(contact.id) {
                com.rohit.sosafe.utils.RecordingManager(context).getRecordingsForUser(contact.id).firstOrNull()?.durationText
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = contact.name, 
                    style = MaterialTheme.typography.bodyLarge, 
                    color = TextPrimary, 
                    fontWeight = FontWeight.SemiBold
                )
                if (latestRecordingDate != null) {
                    Text(
                        text = latestRecordingDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = LightGrey,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
            if (isEmergency) {
                Text(
                    text = "!!! SOS ACTIVE !!!",
                    style = MaterialTheme.typography.labelSmall,
                    color = DangerRed,
                    fontWeight = FontWeight.Bold
                )
            }
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
            Row {
                IconButton(onClick = onRenameClick) {
                    Icon(Icons.Default.Edit, contentDescription = "Rename", tint = LightGrey, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onRemoveClick) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = DangerRed, modifier = Modifier.size(18.dp))
                }
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

        val context = LocalContext.current
        Card(
            modifier = Modifier.fillMaxWidth().clickable {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(4.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkStroke)
        ) {
            Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Security, contentDescription = null, tint = LightGrey)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("MANAGE APP PERMISSIONS", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Location, Microphone, Notifications", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

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
