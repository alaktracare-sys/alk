package com.example.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.MusicRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    repository: MusicRepository,
    onBack: () -> Unit,
    onLoginSuccess: () -> Unit,
    onNavigateToSignup: () -> Unit
) {
    var identifier by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showServerConfigDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(repository.authPreferences.getServerBaseUrl()) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlaktraBackground)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign In", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = AlaktraTextPrimary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showServerConfigDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Tailscale Server Settings",
                                tint = AlaktraMint
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AlaktraBackground)
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Tailscale Server connection chip
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AlaktraSurface,
                modifier = Modifier
                    .clickable { showServerConfigDialog = true }
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF10B981), shape = RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tailscale: ${serverUrl.replace("http://", "").trimEnd('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = identifier,
                onValueChange = { identifier = it },
                label = { Text("Username or Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AlaktraTextPrimary,
                    unfocusedTextColor = AlaktraTextPrimary,
                    focusedBorderColor = AlaktraMint,
                    unfocusedBorderColor = AlaktraBorder,
                    focusedLabelColor = AlaktraMint,
                    unfocusedLabelColor = AlaktraTextSecondary,
                    focusedContainerColor = AlaktraSurface,
                    unfocusedContainerColor = AlaktraSurface
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle password visibility",
                            tint = AlaktraTextSecondary
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AlaktraTextPrimary,
                    unfocusedTextColor = AlaktraTextPrimary,
                    focusedBorderColor = AlaktraMint,
                    unfocusedBorderColor = AlaktraBorder,
                    focusedLabelColor = AlaktraMint,
                    unfocusedLabelColor = AlaktraTextSecondary,
                    focusedContainerColor = AlaktraSurface,
                    unfocusedContainerColor = AlaktraSurface
                )
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage!!,
                    color = Color(0xFFEF4444),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = {
                    val id = identifier.trim()
                    val pwd = password.trim()
                    if (id.isEmpty() || pwd.isEmpty()) {
                        errorMessage = "Please enter username and password"
                        return@Button
                    }
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val result = repository.login(id, pwd)
                        isLoading = false
                        if (result.isSuccess) {
                            onLoginSuccess()
                        } else {
                            errorMessage = result.exceptionOrNull()?.message ?: "Login failed"
                        }
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AlaktraMint,
                    contentColor = Color(0xFF041C12)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color(0xFF041C12), modifier = Modifier.size(24.dp))
                } else {
                    Text(
                        text = "Sign In",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedButton(
                onClick = {
                    // Guest / Offline fallback mode
                    repository.authPreferences.saveUser(
                        userId = 1,
                        username = "Guest User",
                        email = "guest@alaktra.local"
                    )
                    onLoginSuccess()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AlaktraTextSecondary),
                border = ButtonDefaults.outlinedButtonBorder.copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Continue as Guest / Offline")
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onNavigateToSignup) {
                Text(
                    text = "Don't have an account? Sign Up",
                    color = AlaktraMint
                )
            }
        }
    }

    if (showServerConfigDialog) {
        var tempUrl by remember { mutableStateOf(serverUrl) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var testing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showServerConfigDialog = false },
            containerColor = AlaktraCard,
            title = { Text("Tailscale Server URL", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Configure your Tailscale endpoint:",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempUrl,
                        onValueChange = {
                            tempUrl = it
                            testResult = null
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlaktraTextPrimary,
                            unfocusedTextColor = AlaktraTextPrimary,
                            focusedBorderColor = AlaktraMint,
                            unfocusedBorderColor = AlaktraBorder
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                testing = true
                                testResult = null
                                repository.authPreferences.setServerBaseUrl(tempUrl)
                                scope.launch {
                                    val ok = repository.testConnection()
                                    testing = false
                                    testResult = if (ok) "Connected successfully!" else "Connection failed"
                                }
                            },
                            enabled = !testing
                        ) {
                            Text(if (testing) "Testing..." else "Test Connection", color = AlaktraMint)
                        }
                    }
                    if (testResult != null) {
                        Text(
                            text = testResult!!,
                            color = if (testResult!!.startsWith("Conn")) Color(0xFF10B981) else Color(0xFFEF4444),
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.authPreferences.setServerBaseUrl(tempUrl)
                        serverUrl = repository.authPreferences.getServerBaseUrl()
                        showServerConfigDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF041C12))
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerConfigDialog = false }) {
                    Text("Cancel", color = AlaktraTextSecondary)
                }
            }
        )
    }

    // Bottom navigation gradient scrim (opaque at bottom to transparent at top)
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(96.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, AlaktraBackground)
                )
            )
    )
  }
}
