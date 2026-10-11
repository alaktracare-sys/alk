package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.MusicRepository
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignupScreen(
    repository: MusicRepository,
    onBack: () -> Unit,
    onSignupSuccess: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var showOtpField by remember { mutableStateOf(false) }

    // 5-minute countdown timer (300 seconds)
    var resendCooldownSeconds by remember { mutableIntStateOf(300) }
    var isResendingOtp by remember { mutableStateOf(false) }

    var isLoading by remember { mutableStateOf(false) }
    var infoMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Countdown timer for resend OTP (decrements every second when OTP is showing)
    LaunchedEffect(showOtpField, resendCooldownSeconds) {
        if (showOtpField && resendCooldownSeconds > 0) {
            delay(1000L)
            resendCooldownSeconds -= 1
        }
    }

    // Intercept device back button while verifying OTP to return to edit form
    BackHandler(enabled = showOtpField) {
        showOtpField = false
        errorMessage = null
        infoMessage = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AlaktraBackground)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (!showOtpField) "Create Account" else "Verify Email", color = AlaktraTextPrimary, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (showOtpField) {
                                    showOtpField = false
                                    errorMessage = null
                                    infoMessage = null
                                } else {
                                    onBack()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = AlaktraTextPrimary
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

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                enabled = !showOtpField,
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

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Address") },
                singleLine = true,
                enabled = !showOtpField,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
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

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                enabled = !showOtpField,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                singleLine = true,
                enabled = !showOtpField,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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

            if (showOtpField) {
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it },
                    label = { Text("Enter 6-Digit OTP Code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AlaktraTextPrimary,
                        unfocusedTextColor = AlaktraTextPrimary,
                        focusedBorderColor = AlaktraCyan,
                        unfocusedBorderColor = AlaktraBorder,
                        focusedLabelColor = AlaktraCyan,
                        unfocusedLabelColor = AlaktraTextSecondary,
                        focusedContainerColor = AlaktraSurface,
                        unfocusedContainerColor = AlaktraSurface
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Resend OTP Row with 5-minute cooldown timer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            showOtpField = false
                            errorMessage = null
                            infoMessage = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = AlaktraTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Edit details",
                            style = MaterialTheme.typography.bodySmall,
                            color = AlaktraTextSecondary
                        )
                    }

                    val minutes = resendCooldownSeconds / 60
                    val seconds = resendCooldownSeconds % 60
                    val formattedTime = String.format(Locale.US, "%02d:%02d", minutes, seconds)

                    TextButton(
                        onClick = {
                            if (resendCooldownSeconds == 0 && !isResendingOtp && !isLoading) {
                                isResendingOtp = true
                                errorMessage = null
                                scope.launch {
                                    val res = repository.resendOtp(email)
                                    isResendingOtp = false
                                    if (res.isSuccess) {
                                        infoMessage = res.getOrNull() ?: "New OTP sent to your email"
                                        resendCooldownSeconds = 300
                                        otp = ""
                                    } else {
                                        errorMessage = res.exceptionOrNull()?.message ?: "Failed to resend OTP"
                                    }
                                }
                            }
                        },
                        enabled = resendCooldownSeconds == 0 && !isResendingOtp && !isLoading
                    ) {
                        if (isResendingOtp) {
                            CircularProgressIndicator(
                                color = AlaktraMint,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Sending...",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraMint
                            )
                        } else if (resendCooldownSeconds > 0) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = AlaktraTextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Resend in $formattedTime",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextMuted
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = AlaktraMint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Resend OTP",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraMint
                            )
                        }
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = errorMessage!!, color = Color(0xFFEF4444), style = MaterialTheme.typography.bodySmall)
            }

            if (infoMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(text = infoMessage!!, color = AlaktraMint, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    if (!showOtpField) {
                        if (username.isBlank() || email.isBlank() || password.isBlank()) {
                            errorMessage = "Please fill in all fields"
                            return@Button
                        }
                        if (password != confirmPassword) {
                            errorMessage = "Passwords do not match"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val result = repository.signup(username, email, password, confirmPassword)
                            isLoading = false
                            if (result.isSuccess) {
                                showOtpField = true
                                resendCooldownSeconds = 300
                                infoMessage = result.getOrNull() ?: "OTP sent to your email"
                            } else {
                                errorMessage = result.exceptionOrNull()?.message ?: "Signup failed"
                            }
                        }
                    } else {
                        if (otp.isBlank()) {
                            errorMessage = "Please enter the OTP"
                            return@Button
                        }
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val verifyResult = repository.verifyOtp(email, otp)
                            if (verifyResult.isSuccess) {
                                // Auto login upon verification
                                val loginResult = repository.login(email, password)
                                isLoading = false
                                if (loginResult.isSuccess) {
                                    onSignupSuccess()
                                } else {
                                    // Verification succeeded, prompt user to log in
                                    onNavigateToLogin()
                                }
                            } else {
                                isLoading = false
                                errorMessage = verifyResult.exceptionOrNull()?.message ?: "Invalid OTP"
                            }
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
                        text = if (!showOtpField) "Create Account" else "Verify OTP & Continue",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onNavigateToLogin) {
                Text(text = "Already have an account? Sign In", color = AlaktraMint)
            }
        }
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

