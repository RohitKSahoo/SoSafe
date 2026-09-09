package com.rohit.sosafe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.rohit.sosafe.ui.theme.*

@Composable
fun AddContactDialog(
    onDismiss: () -> Unit,
    onValidateCode: (String, (Result<Unit>) -> Unit) -> Unit,
    onAdd: (String, String, (Result<Unit>) -> Unit) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var step by remember { mutableIntStateOf(1) } // 1: Code, 2: Name
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!isLoading) onDismiss() }) {
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
                        onValueChange = { input ->
                            val formatted = input.replace("-", "").replace(" ", "").uppercase()
                            if (formatted.length <= 8) {
                                code = formatted
                                errorMessage = null
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Black,
                            unfocusedContainerColor = Black,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedIndicatorColor = if (errorMessage != null) DangerRed else PureWhite,
                            unfocusedIndicatorColor = if (errorMessage != null) DangerRed else MediumGrey
                        ),
                        placeholder = { Text("E.G. AB12CD34", color = MediumGrey) },
                        singleLine = true,
                        enabled = !isLoading
                    )
                } else {
                    TextField(
                        value = name,
                        onValueChange = { 
                            name = it
                            errorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Black,
                            unfocusedContainerColor = Black,
                            focusedTextColor = PureWhite,
                            unfocusedTextColor = PureWhite,
                            focusedIndicatorColor = if (errorMessage != null) DangerRed else PureWhite,
                            unfocusedIndicatorColor = if (errorMessage != null) DangerRed else MediumGrey
                        ),
                        placeholder = { Text("Enter Name", color = MediumGrey) },
                        singleLine = true,
                        enabled = !isLoading
                    )
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isLoading
                    ) {
                        Text("CANCEL", color = LightGrey)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(
                        onClick = { 
                            if (step == 1) {
                                isLoading = true
                                errorMessage = null
                                onValidateCode(code) { result ->
                                    isLoading = false
                                    if (result.isSuccess) {
                                        step = 2
                                    } else {
                                        errorMessage = result.exceptionOrNull()?.message ?: "Invalid code"
                                    }
                                }
                            } else {
                                isLoading = true
                                errorMessage = null
                                val finalName = name.ifBlank { "User ${code.take(4)}" }
                                onAdd(code, finalName) { result ->
                                    isLoading = false
                                    if (result.isSuccess) {
                                        onDismiss()
                                    } else {
                                        errorMessage = result.exceptionOrNull()?.message ?: "Failed to save contact"
                                    }
                                }
                            }
                        },
                        enabled = (if (step == 1) code.length == 8 else true) && !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PureWhite,
                            contentColor = Black,
                            disabledContainerColor = MediumGrey,
                            disabledContentColor = LightGrey
                        ),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(if (step == 1) "NEXT" else "CONFIRM", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}