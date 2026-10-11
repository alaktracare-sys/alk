package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Song
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongMenuBottomSheet(
    song: Song,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onToggleDownload: () -> Unit,
    onPlayNext: () -> Unit = {},
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AlaktraCard,
        contentColor = AlaktraTextPrimary,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AlaktraTextMuted.copy(alpha = 0.5f))
            )
        }
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp)
            ) {
                // Header with song info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                SongCover(imageUrl = song.coverUrl, size = 56.dp, cornerRadius = 12.dp)
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp),
                        color = AlaktraTextPrimary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlaktraTextSecondary,
                        maxLines = 1
                    )
                }
            }

            HorizontalDivider(color = AlaktraBorder, modifier = Modifier.padding(bottom = 10.dp))

            // Action: Play Next (Spotify / Apple Music priority)
            MenuActionRow(
                icon = Icons.Default.PlaylistPlay,
                title = "Play Next",
                tint = AlaktraMint,
                onClick = {
                    onPlayNext()
                    onDismiss()
                }
            )

            // Action: Add to Queue
            MenuActionRow(
                icon = Icons.Default.QueueMusic,
                title = "Add to Queue",
                tint = AlaktraTextPrimary,
                onClick = {
                    onAddToQueue()
                    onDismiss()
                }
            )

            // Action: Add to Playlist
            MenuActionRow(
                icon = Icons.Default.PlaylistAdd,
                title = "Add to Playlist",
                tint = AlaktraTextPrimary,
                onClick = {
                    onAddToPlaylist()
                    onDismiss()
                }
            )

            // Action: Remove from this Playlist (if inside custom playlist)
            if (onRemoveFromPlaylist != null) {
                MenuActionRow(
                    icon = Icons.Default.DeleteOutline,
                    title = "Remove from this Playlist",
                    tint = Color(0xFFFF5252),
                    onClick = {
                        onRemoveFromPlaylist()
                        onDismiss()
                    }
                )
            }

            // Action: Download / Remove Download
            MenuActionRow(
                icon = if (isDownloaded) Icons.Default.DeleteOutline else Icons.Default.ArrowDownward,
                title = if (isDownloaded) "Remove Download" else "Download Offline",
                tint = if (isDownloaded) Color(0xFFFF5252) else AlaktraMint,
                onClick = {
                    onToggleDownload()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
}

@Composable
private fun MenuActionRow(
    icon: ImageVector,
    title: String,
    tint: Color = AlaktraTextPrimary,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AlaktraSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = tint
        )
    }
}

