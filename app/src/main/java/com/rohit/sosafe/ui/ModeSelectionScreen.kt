package com.rohit.sosafe.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rohit.sosafe.R
import com.rohit.sosafe.data.AppMode
import com.rohit.sosafe.ui.theme.*
import com.rohit.sosafe.utils.QrCodeUtils
import kotlin.math.sqrt
import java.util.Random

/**
 * Static medium-density constellation mesh background with deterministic dots and connecting lines.
 */
@Composable
fun StaticConstellationBackground(
    modifier: Modifier = Modifier,
    particleCount: Int = 38,
    pointColor: Color = PureWhite,
    lineColor: Color = PureWhite,
    maxLineDistanceDp: Float = 130f
) {
    val density = LocalDensity.current
    val maxLineDistancePx = with(density) { maxLineDistanceDp.dp.toPx() }

    val points = remember {
        val rand = Random(2048)
        List(particleCount) {
            Pair(
                Offset(rand.nextFloat(), rand.nextFloat()),
                1.0f + rand.nextFloat() * 1.5f
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Draw connecting lines between nearby points
        for (i in 0 until points.size) {
            val (pos1, _) = points[i]
            val x1 = pos1.x * w
            val y1 = pos1.y * h

            for (j in i + 1 until points.size) {
                val (pos2, _) = points[j]
                val x2 = pos2.x * w
                val y2 = pos2.y * h

                val dx = x2 - x1
                val dy = y2 - y1
                val dist = sqrt(dx * dx + dy * dy)
                if (dist < maxLineDistancePx) {
                    val alpha = (1f - (dist / maxLineDistancePx)) * 0.16f
                    drawLine(
                        color = lineColor.copy(alpha = alpha),
                        start = Offset(x1, y1),
                        end = Offset(x2, y2),
                        strokeWidth = 1f
                    )
                }
            }

            // Draw dot
            drawCircle(
                color = pointColor.copy(alpha = 0.35f),
                radius = points[i].second,
                center = Offset(x1, y1)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSelectionScreen(
    userCode: String,
    initialName: String = "",
    onCompleted: (selectedMode: AppMode, userName: String) -> Unit
) {
    var selectedRole by remember { mutableStateOf<AppMode?>(null) }
    var nameInput by remember { mutableStateOf(initialName) }
    var nameError by remember { mutableStateOf(false) }
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
        QrCodeUtils.generateQrCodeBitmap(qrPayload, 512)
    }

    // Intercept phone back button / gesture to navigate from Step 2 back to Step 1
    BackHandler(enabled = selectedRole != null) {
        selectedRole = null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Static medium-density constellation background
            StaticConstellationBackground()

            // Outer Fixed Frame: Top Corner Header, Middle Transitioning Content, and Bottom Corner Footer
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                // ── 1. TOP CORNER HEADER (Pinned / Static Across Transitions) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Top Left: Back Navigation with SOSAFE (Visible on Step 2)
                    AnimatedVisibility(
                        visible = selectedRole != null,
                        enter = fadeIn() + slideInHorizontally { -it / 2 },
                        exit = fadeOut() + slideOutHorizontally { -it / 2 }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { selectedRole = null }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = PureWhite,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Row {
                                Text(
                                    text = "SOS",
                                    color = DangerRed,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "AFE",
                                    color = PureWhite,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 2.sp,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }

                    if (selectedRole == null) {
                        Spacer(modifier = Modifier.weight(1f, fill = false))
                    }

                    // Top Right: GitHub Icon (Replaces Peer Safety Network)
                    IconButton(
                        onClick = {
                            try {
                                val githubIntent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/RohitKSahoo/SoSafe")
                                )
                                context.startActivity(githubIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Cannot open browser", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = "GitHub Repository",
                            tint = PureWhite.copy(alpha = 0.85f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // ── 2. MIDDLE CONTENT (Transitioning between Step 1 and Step 2) ──
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
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
                            // STEP 1: CHOOSE ROLE (Exact Mockup Design)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Spacer(modifier = Modifier.weight(0.9f))

                                // Center Brand Title (SOS in Red, AFE in White)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "S O S",
                                        fontSize = 34.sp,
                                        color = DangerRed,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 5.sp
                                    )
                                    Text(
                                        text = " A F E",
                                        fontSize = 34.sp,
                                        color = PureWhite,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 5.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Choose your role to get started.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LightGrey,
                                    fontSize = 13.sp,
                                    letterSpacing = 0.5.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(48.dp))

                                // Choice Cards
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    // Sender Option Card
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(86.dp)
                                            .clickable { selectedRole = AppMode.SENDER },
                                        shape = RoundedCornerShape(0.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 20.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Left SOS Box
                                            Box(
                                                modifier = Modifier.width(54.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Text(
                                                    text = "SOS",
                                                    color = DangerRed,
                                                    fontWeight = FontWeight.Black,
                                                    fontSize = 18.sp,
                                                    letterSpacing = 2.sp
                                                )
                                            }

                                            // Vertical Divider Line
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(38.dp)
                                                    .background(Color(0xFF262626))
                                            )

                                            Spacer(modifier = Modifier.width(18.dp))

                                            // Text Column
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "I WANT TO BE SENDER",
                                                    color = PureWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    letterSpacing = 1.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Trigger SOS and get help",
                                                    color = LightGrey,
                                                    fontSize = 11.sp,
                                                    letterSpacing = 0.5.sp
                                                )
                                            }

                                            // Arrow Forward
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = PureWhite.copy(alpha = 0.85f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    // Guardian Option Card
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(86.dp)
                                            .clickable { selectedRole = AppMode.GUARDIAN },
                                        shape = RoundedCornerShape(0.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D0D0D)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262626))
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 20.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Left Shield Box
                                            Box(
                                                modifier = Modifier.width(54.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Shield,
                                                    contentDescription = "Guardian Mode",
                                                    tint = PureWhite,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }

                                            // Vertical Divider Line
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(38.dp)
                                                    .background(Color(0xFF262626))
                                            )

                                            Spacer(modifier = Modifier.width(18.dp))

                                            // Text Column
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "I WANT TO BE GUARDIAN",
                                                    color = PureWhite,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    letterSpacing = 1.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "Be there for your people",
                                                    color = LightGrey,
                                                    fontSize = 11.sp,
                                                    letterSpacing = 0.5.sp
                                                )
                                            }

                                            // Arrow Forward
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                contentDescription = null,
                                                tint = PureWhite.copy(alpha = 0.85f),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1.3f))
                            }
                        } else {
                            // STEP 2: SET UP YOUR IDENTITY (Scrollable Form)
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = 16.dp, bottom = 8.dp)
                            ) {
                                // 1. Headline
                                Text(
                                    text = "SET UP YOUR IDENTITY",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = PureWhite,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.5.sp
                                )

                                Spacer(modifier = Modifier.height(20.dp))

                                // 2. Name Input Field with Person Icon & Sharp Edges
                                OutlinedTextField(
                                    value = nameInput,
                                    onValueChange = { 
                                        nameInput = it
                                        if (it.isNotBlank()) nameError = false
                                    },
                                    placeholder = { Text("Enter your name (required) ", color = LightGrey) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = if (nameError && nameInput.trim().isBlank()) DangerRed else LightGrey,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    singleLine = true,
                                    isError = nameError && nameInput.trim().isBlank(),
                                    supportingText = if (nameError && nameInput.trim().isBlank()) {
                                        { Text("Name is required to continue", color = DangerRed, fontSize = 11.sp) }
                                    } else null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(0.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = PureWhite,
                                        unfocusedTextColor = PureWhite,
                                        focusedBorderColor = PureWhite,
                                        unfocusedBorderColor = MediumGrey,
                                        focusedContainerColor = DarkGrey,
                                        unfocusedContainerColor = DarkGrey,
                                        errorBorderColor = DangerRed,
                                        errorContainerColor = DarkGrey,
                                        errorTextColor = PureWhite,
                                        cursorColor = PureWhite
                                    )
                                )

                                Spacer(modifier = Modifier.height(28.dp))

                                // 3. Centered Divider: ─── YOUR SAFETY PAIRING QR ───
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MediumGrey,
                                        thickness = 1.dp
                                    )
                                    Text(
                                        text = "YOUR SAFETY PAIRING QR",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LightGrey,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.5.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.weight(1f),
                                        color = MediumGrey,
                                        thickness = 1.dp
                                    )
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                // 4. Large QR Code Box (Sharp edges with minimized padding)
                                if (qrBitmap != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(240.dp)
                                            .align(Alignment.CenterHorizontally)
                                            .clip(RoundedCornerShape(0.dp))
                                            .background(Color.White)
                                            .padding(4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = qrBitmap.asImageBitmap(),
                                            contentDescription = "My Pairing QR Code",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(28.dp))

                                // 5. Device ID Card with Copy Icon on the right
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(0.dp))
                                        .background(DarkGrey)
                                        .border(1.dp, MediumGrey, RoundedCornerShape(0.dp))
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Device ID",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = LightGrey,
                                                fontSize = 11.sp
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = formattedCode,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = PureWhite,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 2.sp
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(formattedCode))
                                                Toast.makeText(context, "Device ID copied!", Toast.LENGTH_SHORT).show()
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "Copy ID",
                                                tint = LightGrey,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // 6. Action Buttons (COPY LINK & SHARE LINK) - Sharp edges
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Left COPY LINK button (Dark)
                                    Button(
                                        onClick = {
                                            val trimmedName = nameInput.trim()
                                            if (trimmedName.isBlank()) {
                                                nameError = true
                                                Toast.makeText(context, "Please enter your name first", Toast.LENGTH_SHORT).show()
                                            } else {
                                                nameError = false
                                                clipboardManager.setText(AnnotatedString(inviteLink))
                                                Toast.makeText(context, "Invite link copied to clipboard!", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(0.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = DarkGrey,
                                            contentColor = PureWhite
                                        ),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
                                    ) {
                                        Icon(
                                            Icons.Default.ContentCopy,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = PureWhite
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "COPY LINK",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }

                                    // Right SHARE LINK button (Red)
                                    Button(
                                        onClick = {
                                            val trimmedName = nameInput.trim()
                                            if (trimmedName.isBlank()) {
                                                nameError = true
                                                Toast.makeText(context, "Please enter your name first", Toast.LENGTH_SHORT).show()
                                            } else {
                                                nameError = false
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(
                                                        Intent.EXTRA_TEXT,
                                                        "Add me as your emergency safety contact on SoSafe:\n$inviteLink"
                                                    )
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share SoSafe Safety Link"))
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                        shape = RoundedCornerShape(0.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = DangerRed,
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = Color.Black
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "SHARE LINK",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(28.dp))

                                // 7. Continue to Dashboard CTA (White with black text & arrow)
                                Button(
                                    onClick = {
                                        val trimmedName = nameInput.trim()
                                        if (trimmedName.isBlank()) {
                                            nameError = true
                                            Toast.makeText(context, "Please enter your name to continue", Toast.LENGTH_SHORT).show()
                                        } else {
                                            nameError = false
                                            onCompleted(targetMode, trimmedName)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PureWhite,
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "CONTINUE TO DASHBOARD",
                                            fontWeight = FontWeight.Black,
                                            fontSize = 14.sp,
                                            letterSpacing = 1.sp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "You can also scan contact QRs anytime from the dashboard.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LightGrey,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }

                // ── 3. BOTTOM CORNER FOOTER (Pinned / Static Across Transitions) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "SOSAFE",
                            fontSize = 10.sp,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "FREE  ·  OPEN SOURCE  ·  SAFER TOGETHER",
                            fontSize = 9.sp,
                            color = LightGrey,
                            letterSpacing = 1.sp
                        )
                    }

                    AnimatedContent(
                        targetState = if (selectedRole == null) "[ 01 ]" else "[ 02 ]",
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "StepCounterTransition"
                    ) { stepText ->
                        Text(
                            text = stepText,
                            fontSize = 11.sp,
                            color = LightGrey,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}