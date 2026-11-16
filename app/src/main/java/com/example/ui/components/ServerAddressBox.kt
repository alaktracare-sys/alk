package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.AuthPreferences
import com.example.data.repository.MusicRepository
import com.example.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Reusable Server Address entry & configuration card for Login, Signup, and other auth/setup pages.
 * Directly modifies and syncs with MusicRepository.updateServerUrl and AuthPreferences.
 */
@Composable
fun ServerAddressBox(
    repository: MusicRepository,
    modifier: Modifier = Modifier,
    onServerUpdated: ((String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var serverUrlInput by remember { mutableStateOf(repository.authPreferences.getServerBaseUrl()) }
    var isChecking by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<Boolean?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isSavedConfirmation by remember { mutableStateOf(false) }

    // Keep input synced if changed externally (e.g. from Profile Screen)
    LaunchedEffect(Unit) {
        val current = repository.authPreferences.getServerBaseUrl()
        if (current.isNotBlank()) {
            serverUrlInput = current
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_address_box"),
        colors = CardDefaults.cardColors(containerColor = AlaktraSurface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AlaktraBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(AlaktraMint.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = "Server Address",
                            tint = AlaktraMint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Server Address",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = AlaktraTextPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Status indicator dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when (connectionStatus) {
                                    true -> Color(0xFF10B981)
                                    false -> Color(0xFFEF4444)
                                    null -> Color(0xFF9CA3AF)
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (connectionStatus) {
                            true -> "Online"
                            false -> "Offline"
                            null -> "Configured"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = when (connectionStatus) {
                            true -> Color(0xFF10B981)
                            false -> Color(0xFFEF4444)
                            null -> AlaktraTextMuted
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = serverUrlInput,
                onValueChange = {
                    serverUrlInput = it
                    connectionStatus = null
                    statusMessage = null
                    isSavedConfirmation = false
                    val trimmed = it.trim()
                    if (trimmed.isNotBlank()) {
                        repository.updateServerUrl(trimmed)
                        onServerUpdated?.invoke(trimmed)
                    }
                },
                placeholder = {
                    Text(
                        text = "http://100.x.y.z:8000 or http://your-domain:8000",
                        fontSize = 13.sp,
                        color = AlaktraTextMuted
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AlaktraTextPrimary,
                    unfocusedTextColor = AlaktraTextPrimary,
                    focusedBorderColor = AlaktraMint,
                    unfocusedBorderColor = AlaktraBorder,
                    focusedContainerColor = AlaktraCard,
                    unfocusedContainerColor = AlaktraCard
                ),
                trailingIcon = {
                    if (isSavedConfirmation) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Saved",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_address_input")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        val def = AuthPreferences.DEFAULT_SERVER_URL
                        serverUrlInput = def
                        repository.updateServerUrl(def)
                        connectionStatus = null
                        statusMessage = null
                        isSavedConfirmation = true
                        onServerUpdated?.invoke(def)
                    },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Reset Default",
                        color = AlaktraTextSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = {
                            val trimmed = serverUrlInput.trim()
                            if (trimmed.isNotBlank()) {
                                repository.updateServerUrl(trimmed)
                                isSavedConfirmation = true
                                onServerUpdated?.invoke(trimmed)
                                scope.launch {
                                    isChecking = true
                                    val ok = repository.testUrl(trimmed)
                                    isChecking = false
                                    connectionStatus = ok
                                    statusMessage = if (ok) "Server reachable" else "Cannot connect"
                                }
                            }
                        },
                        enabled = !isChecking && serverUrlInput.isNotBlank(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AlaktraCard,
                            contentColor = AlaktraMint
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.heightIn(min = 34.dp)
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(
                                color = AlaktraMint,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...", fontSize = 11.sp)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Test",
                                tint = AlaktraMint,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = msg,
                        fontSize = 11.sp,
                        color = if (connectionStatus == true) Color(0xFF10B981) else Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}
