package com.rohit.sosafe.ui

import android.content.Intent
import android.net.Uri
import android.widget.ImageView
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rohit.sosafe.data.AppMode
import com.rohit.sosafe.ui.theme.*
import com.rohit.sosafe.utils.QrCodeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectionScreen(
    userCode: String,
    initialName: String = "",
    onCompleted: (selectedMode: AppMode, userName: String) -> Unit
) {
    var selectedRole by remember { mutableStateOf<AppMode?>(null) }
    var nameInput by remember { mutableStateOf(initialName) }
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val formattedCode = remember(userCode) {
        userCode.replace("-", "").trim().uppercase()
    }

    val inviteLink = remember(formattedCode, nameInput) {
        val encodedName = Uri.encode(nameInput.trim().ifBlank { "User $formattedCode" })
        "https://rohitksahoo.github.io/SoSafe/pair?id=$formattedCode&name=$encodedName"
    }

    val qrBitmap = remember(formattedCode, nameInput) {
        val encodedName = Uri.encode(nameInput.trim().ifBlank { "User $formattedCode" })
        val qrPayload = "sosafe://pair?id=$formattedCode&name=$encodedName"
        QrCodeUtils.generateQrCodeBitmap(qrPayload, 380)
    }

    // Safe loading of the application launcher icon across all Android versions
    val appIconDrawable = remember(context) {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
        } catch (e: Exception) {
            null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Dotted grid background identical to Dashboard and Settings screens
            GridBackground()

            AnimatedContent(
                targetState = selectedRole,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "OnboardingTransition",
                modifier = Modifier.fillMaxSize()
            ) { targetMode ->
                if (targetMode == null) {
                    // STEP 1: CHOOSE ROLE (Sharp Edges & Centered)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // App Icon with sharp edges
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(RoundedCornerShape(0.dp))
                                .background(DarkGrey)
                                .border(1.dp, MediumGrey, RoundedCornerShape(0.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (appIconDrawable != null) {
                                AndroidView(
                                    factory = { ctx ->
                                        ImageView(ctx).apply {
                                            setImageDrawable(appIconDrawable)
                                            scaleType = ImageView.ScaleType.FIT_CENTER
                                        }
                                    },
                                    modifier = Modifier.size(52.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = "SoSafe App Icon",
                                    tint = PureWhite,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "SO-SAFE",
                            style = MaterialTheme.typography.headlineLarge,
                            color = PureWhite,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Choose your role to get started",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LightGrey,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(36.dp))

                        // Sender Option Card with sharp edges
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRole = AppMode.SENDER },
                            shape = RoundedCornerShape(0.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkGrey),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "I NEED PROTECTION",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = PureWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = DangerRed.copy(alpha = 0.2f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "SENDER",
                                            color = DangerRed,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Stream real-time audio and live GPS coordinates to your emergency guardians when SOS is triggered.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LightGrey,
                                    lineHeight = 18.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Guardian Option Card with sharp edges
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRole = AppMode.GUARDIAN },
                            shape = RoundedCornerShape(0.dp),
                            colors = CardDefaults.cardColors(containerColor = DarkGrey),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "I AM A GUARDIAN",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = PureWhite,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = SuccessGreen.copy(alpha = 0.2f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = "MONITOR",
                                            color = SuccessGreen,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Receive instant emergency alerts, listen to live audio streams, and monitor the safety of your loved ones.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LightGrey,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                } else {
                    // STEP 2: PROFILE IDENTITY & DOCKED CTA (Sharp Edges + Centered Content + Bottom Dock CTA)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                    ) {
                        // TOP: Back Navigation Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart)
                                .padding(top = 20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { selectedRole = null }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = PureWhite
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "SET UP YOUR IDENTITY",
                                style = MaterialTheme.typography.titleMedium,
                                color = PureWhite,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // CENTER: Centered Form & QR Preview
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.Center)
                                .padding(top = 64.dp, bottom = 120.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Enter your name so your contacts recognize you when linking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LightGrey,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            // Name Input Field with sharp edges
                            OutlinedTextField(
                                value = nameInput,
                                onValueChange = { nameInput = it },
                                label = { Text("Your Name (e.g. Alice, Dad)", color = LightGrey) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(0.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = PureWhite,
                                    unfocusedTextColor = PureWhite,
                                    focusedBorderColor = PureWhite,
                                    unfocusedBorderColor = MediumGrey,
                                    cursorColor = PureWhite
                                )
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            // QR Code Preview Card with sharp edges
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(0.dp),
                                colors = CardDefaults.cardColors(containerColor = DarkGrey),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "YOUR SAFETY PAIRING QR",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = LightGrey,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    if (qrBitmap != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(150.dp)
                                                .clip(RoundedCornerShape(0.dp))
                                                .background(Color.White)
                                                .padding(6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                bitmap = qrBitmap.asImageBitmap(),
                                                contentDescription = "My Pairing QR Code",
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = "Device ID: $formattedCode",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PureWhite,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(modifier = Modifier.height(16.dp))

                                    // Action buttons with sharp edges
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(inviteLink))
                                                Toast.makeText(context, "Invite link copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(0.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PureWhite)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("COPY", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "Add me as your emergency safety contact on SoSafe:\n$inviteLink"
                                                    )
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share SoSafe Safety Link"))
                                            },
                                            modifier = Modifier.weight(1.3f),
                                            shape = RoundedCornerShape(0.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Color.Black)
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("SHARE LINK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        // BOTTOM: Docked "CONTINUE TO DASHBOARD" CTA Button
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    val finalName = nameInput.trim().ifBlank { "User $formattedCode" }
                                    onCompleted(targetMode, finalName)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (targetMode == AppMode.SENDER) DangerRed else SuccessGreen,
                                    contentColor = PureWhite
                                )
                            ) {
                                Text(
                                    text = "CONTINUE TO DASHBOARD",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 15.sp,
                                    letterSpacing = 1.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "You can also scan contact QRs anytime from the dashboard.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LightGrey,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}