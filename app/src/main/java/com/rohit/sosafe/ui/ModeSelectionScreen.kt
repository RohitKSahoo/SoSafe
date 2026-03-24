package com.rohit.sosafe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rohit.sosafe.data.AppMode
import com.rohit.sosafe.ui.theme.Black
import com.rohit.sosafe.ui.theme.DarkBackground
import com.rohit.sosafe.ui.theme.MediumGrey
import com.rohit.sosafe.ui.theme.PureWhite

@Composable
fun ModeSelectionScreen(
    onModeSelected: (AppMode) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = DarkBackground // Use our monochrome background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "CHOOSE YOUR ROLE",
                style = MaterialTheme.typography.headlineMedium,
                color = PureWhite,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = { onModeSelected(AppMode.SENDER) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PureWhite, contentColor = Black)
            ) {
                Text("I NEED PROTECTION (SENDER)", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onModeSelected(AppMode.GUARDIAN) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MediumGrey, contentColor = PureWhite)
            ) {
                Text("I AM A GUARDIAN (RECEIVER)", fontWeight = FontWeight.Bold)
            }
        }
    }
}