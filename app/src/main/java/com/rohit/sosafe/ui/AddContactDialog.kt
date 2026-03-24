package com.rohit.sosafe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rohit.sosafe.ui.theme.*

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit
) {
    var code by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = DarkGrey),
            border = androidx.compose.foundation.BorderStroke(1.dp, MediumGrey)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "LINK NEW USER",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter the 8-character ID of the person you want to link with.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LightGrey
                )
                Spacer(modifier = Modifier.height(24.dp))

                TextField(
                    value = code,
                    onValueChange = { if (it.length <= 8) code = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Black,
                        unfocusedContainerColor = Black,
                        focusedTextColor = PureWhite,
                        unfocusedTextColor = PureWhite,
                        focusedIndicatorColor = PureWhite,
                        unfocusedIndicatorColor = MediumGrey
                    ),
                    placeholder = { Text("E.G. AB12CD34", color = MediumGrey) },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("CANCEL", color = LightGrey)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { if (code.length == 8) onAdd(code) },
                        enabled = code.length == 8,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = Black,
                            disabledContainerColor = MediumGrey,
                            disabledContentColor = LightGrey
                        ),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text("LINK USER", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}