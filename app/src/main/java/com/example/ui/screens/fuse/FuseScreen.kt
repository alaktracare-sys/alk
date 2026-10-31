package com.example.ui.screens.fuse

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.fuse.FuseCodeCodec
import com.example.data.fuse.FusePollStatus
import com.example.data.fuse.FuseSession
import com.example.data.repository.MusicRepository
import com.example.model.Playlist
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private sealed class HostFuseUiState {
    data object Idle : HostFuseUiState()
    data object Creating : HostFuseUiState()
    data class Active(val session: FuseSession, val remainingSeconds: Int) : HostFuseUiState()
    data object ExpiredAndDeleted : HostFuseUiState()
    data class Completed(val playlist: Playlist, val trackCount: Int) : HostFuseUiState()
}

@Composable
fun FuseScreen(
    repository: MusicRepository,
    initialJoinCode: String? = null,
    onConsumedInitialJoinCode: () -> Unit = {},
    onBack: () -> Unit,
    onOpenPlaylist: (Int, String) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var hostState by remember {
        val existing = repository.getActiveFuseSession()
        val initial = if (existing != null && !existing.isExpired()) {
            HostFuseUiState.Active(existing, (existing.remainingMillis() / 1000L).toInt())
        } else {
            HostFuseUiState.Idle
        }
        mutableStateOf<HostFuseUiState>(initial)
    }
    var joinInput by remember { mutableStateOf("") }
    var isJoining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }

    fun startNewHostFuse(forceNew: Boolean) {
        scope.launch {
            hostState = HostFuseUiState.Creating
            val result = repository.createFuseSession(forceNew = forceNew)
            val session = result.getOrNull()
            if (session != null) {
                val secs = (session.remainingMillis() / 1000L).toInt().coerceAtLeast(0)
                if (secs > 0) {
                    hostState = HostFuseUiState.Active(session, secs)
                } else {
                    repository.expireOrDeleteFuseSession(session)
                    hostState = HostFuseUiState.ExpiredAndDeleted
                }
            } else {
                hostState = HostFuseUiState.ExpiredAndDeleted
            }
        }
    }

    fun submitJoinFuse(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            joinError = "Please enter a Fuse code or paste a Fuse link."
            return
        }
        focusManager.clearFocus()
        scope.launch {
            isJoining = true
            joinError = null
            val res = repository.joinFuse(trimmed)
            isJoining = false
            res.fold(
                onSuccess = { joinResult ->
                    Toast.makeText(
                        context,
                        "Fuse ready: ${joinResult.playlist.name} (${joinResult.songs.size} tracks)",
                        Toast.LENGTH_SHORT
                    ).show()
                    onOpenPlaylist(joinResult.playlist.id, joinResult.playlist.name)
                },
                onFailure = { err ->
                    joinError = err.message ?: "Unable to join Fuse."
                }
            )
        }
    }

    // Resume an already-running Fuse if the user previously started one, without auto-creating on open
    LaunchedEffect(Unit) {
        val existing = repository.getActiveFuseSession()
        if (existing != null && !existing.isExpired()) {
            val secs = (existing.remainingMillis() / 1000L).toInt()
            hostState = HostFuseUiState.Active(existing, secs)
        }
    }

    // Auto-handle incoming shared Fuse deep link if present
    LaunchedEffect(initialJoinCode) {
        val incoming = initialJoinCode?.trim()
        if (!incoming.isNullOrEmpty()) {
            joinInput = incoming
            onConsumedInitialJoinCode()
            submitJoinFuse(incoming)
        }
    }

    // 1-second live countdown + automatic 5-minute deletion when unjoined
    val activeSession = (hostState as? HostFuseUiState.Active)?.session
    LaunchedEffect(activeSession?.code) {
        val session = activeSession ?: return@LaunchedEffect
        while (isActive) {
            val remainingMs = session.remainingMillis()
            if (remainingMs <= 0L) {
                repository.expireOrDeleteFuseSession(session)
                hostState = HostFuseUiState.ExpiredAndDeleted
                break
            }
            val secs = ((remainingMs + 999L) / 1000L).toInt()
            val current = hostState
            if (current is HostFuseUiState.Active && current.session.code == session.code) {
                hostState = current.copy(remainingSeconds = secs)
            }
            delay(1000L)
        }
    }

    // Poll every 2 seconds to detect when another user joins this Fuse
    LaunchedEffect(activeSession?.code) {
        val session = activeSession ?: return@LaunchedEffect
        while (isActive) {
            delay(2000L)
            if (session.isExpired()) {
                repository.expireOrDeleteFuseSession(session)
                hostState = HostFuseUiState.ExpiredAndDeleted
                break
            }
            when (val status = repository.pollFuseSession(session)) {
                is FusePollStatus.Joined -> {
                    hostState = HostFuseUiState.Completed(
                        playlist = status.playlist,
                        trackCount = status.songs.size
                    )
                    Toast.makeText(
                        context,
                        "Fuse created: ${status.playlist.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                    break
                }
                is FusePollStatus.Expired -> {
                    hostState = HostFuseUiState.ExpiredAndDeleted
                    break
                }
                is FusePollStatus.Waiting -> Unit
            }
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "fuse_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_dot"
    )

    Scaffold(
        containerColor = AlaktraBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 150.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 12.dp, end = 20.dp, bottom = 8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("fuse_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AlaktraTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.fuse_title),
                                style = MaterialTheme.typography.headlineSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 24.sp
                                ),
                                color = AlaktraTextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AlaktraMint.copy(alpha = 0.16f))
                                    .border(1.dp, AlaktraMint.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "5 MIN",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AlaktraMint
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.fuse_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = AlaktraTextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 1. HOST FUSE CARD
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = AlaktraCard,
                    border = BorderStroke(1.dp, AlaktraBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        AlaktraMint.copy(alpha = 0.08f),
                                        AlaktraCyan.copy(alpha = 0.03f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(22.dp)
                    ) {
                        when (val state = hostState) {
                            is HostFuseUiState.Idle -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    AlaktraMint.copy(alpha = 0.22f),
                                                    AlaktraCyan.copy(alpha = 0.22f)
                                                )
                                            )
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = AlaktraMint.copy(alpha = 0.45f),
                                            shape = CircleShape
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MergeType,
                                        contentDescription = null,
                                        tint = AlaktraMint,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = stringResource(R.string.fuse_idle_heading),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = stringResource(R.string.fuse_idle_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                Button(
                                    onClick = { startNewHostFuse(forceNew = true) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AlaktraMint,
                                        contentColor = Color(0xFF041C12)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("start_fuse_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.fuse_start_button),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            is HostFuseUiState.Creating -> {
                                Spacer(modifier = Modifier.height(20.dp))
                                CircularProgressIndicator(
                                    color = AlaktraMint,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Generating temporary Fuse...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlaktraTextSecondary
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                            }

                            is HostFuseUiState.Active -> {
                                val session = state.session
                                val totalSeconds = (FuseCodeCodec.FUSE_TTL_MS / 1000L).toFloat()
                                val progress = (state.remainingSeconds / totalSeconds).coerceIn(0f, 1f)
                                val mins = state.remainingSeconds / 60
                                val secs = state.remainingSeconds % 60
                                val formattedTimer = String.format(Locale.US, "%02d:%02d", mins, secs)

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(AlaktraSurface)
                                        .border(1.dp, AlaktraBorder, RoundedCornerShape(50))
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .alpha(pulseAlpha)
                                            .clip(CircleShape)
                                            .background(AlaktraMint)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Active • Expires in $formattedTimer",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = AlaktraTextPrimary,
                                        modifier = Modifier.testTag("fuse_countdown_timer")
                                    )
                                }

                                Spacer(modifier = Modifier.height(18.dp))

                                Text(
                                    text = stringResource(R.string.fuse_temp_code_label),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        letterSpacing = 1.6.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = AlaktraTextSecondary
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                // Character tiles for easy reading & sharing
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("fuse_code_display")
                                ) {
                                    session.code.forEach { ch ->
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .sizeIn(minWidth = 42.dp, minHeight = 52.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(AlaktraSurface)
                                                .border(
                                                    width = 1.2.dp,
                                                    brush = Brush.verticalGradient(
                                                        listOf(AlaktraMint.copy(alpha = 0.65f), AlaktraCyan.copy(alpha = 0.35f))
                                                    ),
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .padding(horizontal = 8.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = ch.toString(),
                                                style = MaterialTheme.typography.headlineSmall.copy(
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 24.sp
                                                ),
                                                color = AlaktraMint
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                LinearProgressIndicator(
                                    progress = { progress },
                                    color = if (state.remainingSeconds <= 60) AlaktraCoral else AlaktraMint,
                                    trackColor = AlaktraSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Text(
                                    text = "Share this code or send the Fuse link to a friend. If no one joins within 5 minutes, this Fuse is automatically deleted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Alaktra Fuse Code", session.code))
                                            Toast.makeText(context, "Fuse code copied: ${session.code}", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, AlaktraBorder),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = AlaktraSurface,
                                            contentColor = AlaktraTextPrimary
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("fuse_copy_code_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = stringResource(R.string.fuse_copy_code),
                                            tint = AlaktraMint,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.fuse_copy_code),
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val shareText = FuseCodeCodec.buildShareMessage(session.hostName, session.code)
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_SUBJECT, "Join my Alaktra Fuse")
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, "Send Fuse Link"))
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AlaktraMint,
                                            contentColor = Color(0xFF041C12)
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("fuse_share_link_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Share,
                                            contentDescription = stringResource(R.string.fuse_send_link),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.fuse_send_link),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            is HostFuseUiState.ExpiredAndDeleted -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(AlaktraCoral.copy(alpha = 0.14f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TimerOff,
                                        contentDescription = stringResource(R.string.fuse_expired_title),
                                        tint = AlaktraCoral,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = stringResource(R.string.fuse_expired_title),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                Text(
                                    text = stringResource(R.string.fuse_expired_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlaktraTextSecondary,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                Button(
                                    onClick = { startNewHostFuse(forceNew = true) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AlaktraMint,
                                        contentColor = Color(0xFF041C12)
                                    ),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("fuse_regenerate_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.fuse_create_new),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            is HostFuseUiState.Completed -> {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(listOf(AlaktraMint, AlaktraCyan))
                                        )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Fuse Ready",
                                        tint = Color(0xFF041C12),
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = state.playlist.name,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                                    color = AlaktraTextPrimary,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Text(
                                    text = "${state.trackCount} shared songs added for both listeners",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AlaktraMint,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(18.dp))

                                Button(
                                    onClick = { onOpenPlaylist(state.playlist.id, state.playlist.name) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = AlaktraMint,
                                        contentColor = Color(0xFF041C12)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .testTag("open_fuse_playlist_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.fuse_open_playlist),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 2. JOIN ANOTHER USER'S FUSE SECTION
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = AlaktraCard,
                    border = BorderStroke(1.dp, AlaktraBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AlaktraCyan.copy(alpha = 0.14f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MergeType,
                                    contentDescription = null,
                                    tint = AlaktraCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.fuse_join_heading),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                                Text(
                                    text = "Enter a friend's temporary code or paste their Fuse link",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraTextSecondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = joinInput,
                            onValueChange = {
                                joinInput = it
                                if (joinError != null) joinError = null
                            },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.fuse_join_placeholder),
                                    color = AlaktraTextMuted
                                )
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!isJoining) submitJoinFuse(joinInput)
                                }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = AlaktraTextPrimary,
                                unfocusedTextColor = AlaktraTextPrimary,
                                focusedBorderColor = AlaktraCyan,
                                unfocusedBorderColor = AlaktraBorder,
                                focusedContainerColor = AlaktraSurface,
                                unfocusedContainerColor = AlaktraSurface
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("fuse_join_code_input")
                        )

                        if (joinError != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = AlaktraCoral,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = joinError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AlaktraCoral
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { submitJoinFuse(joinInput) },
                            enabled = !isJoining && joinInput.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlaktraCyan,
                                contentColor = Color(0xFF001F29),
                                disabledContainerColor = AlaktraSurface,
                                disabledContentColor = AlaktraTextMuted
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("fuse_join_submit_button")
                        ) {
                            if (isJoining) {
                                CircularProgressIndicator(
                                    color = AlaktraCyan,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Building 30-song Fuse...",
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.fuse_join_action),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // How Fuse Works summary card
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = AlaktraMint.copy(alpha = 0.8f),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Prepares up to 30 common songs across Liked & custom playlists and saves \"user 1 fuse user 2\" for both of you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AlaktraTextMuted
                    )
                }
            }
        }
    }
}
