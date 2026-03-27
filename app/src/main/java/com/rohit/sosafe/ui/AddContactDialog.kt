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
    onAdd: (String, String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) } // 1: Code, 2: Name

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
                    text = if (step == 1) "LINK NEW USER" else "NAME THIS USER",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PureWhite,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (step == 1) 
                        "Enter the 8-character ID of the person you want to link with."
                        else "Give this person a recognizable name (e.g. Mom, Guardian 1).",
                    style = MaterialTheme.typography.bodySmall,
                    color = LightGrey
                )
                Spacer(modifier = Modifier.height(24.dp))

                if (step == 1) {
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
                } else {
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
                        placeholder = { Text("Enter Name", color = MediumGrey) },
                        singleLine = true
                    )
                }

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
                        onClick = { 
                            if (step == 1) step = 2
                            else onAdd(code, name.ifBlank { "User ${code.take(4)}" })
                        },
                        enabled = if (step == 1) code.length == 8 else true,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = Black,
                            disabledContainerColor = MediumGrey,
                            disabledContentColor = LightGrey
                        ),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(if (step == 1) "NEXT" else "CONFIRM", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}