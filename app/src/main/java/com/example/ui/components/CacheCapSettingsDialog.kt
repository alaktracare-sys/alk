package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.audio.AudioCache
import com.example.ui.theme.AlaktraBorder
import com.example.ui.theme.AlaktraCard
import com.example.ui.theme.AlaktraCardElevated
import com.example.ui.theme.AlaktraCyan
import com.example.ui.theme.AlaktraMint
import com.example.ui.theme.AlaktraTextMuted
import com.example.ui.theme.AlaktraTextPrimary
import com.example.ui.theme.AlaktraTextSecondary
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CacheCapSettingsDialog(
    currentCapMb: Int,
    currentUsageBytes: Long,
    onDismissRequest: () -> Unit,
    onApplyCap: (newCapMb: Int) -> Unit
) {
    var selectedCapMb by remember { mutableIntStateOf(currentCapMb) }
    val currentUsageMb = currentUsageBytes / (1024.0 * 1024.0)
    val selectedCapBytes = selectedCapMb.toLong() * 1024L * 1024L
    val usageRatio = (currentUsageBytes.toFloat() / selectedCapBytes.toFloat()).coerceIn(0f, 1f)
    val willEvict = selectedCapBytes < currentUsageBytes
    val excessBytes = if (willEvict) currentUsageBytes - selectedCapBytes else 0L

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("cache_cap_settings_dialog"),
            colors = CardDefaults.cardColors(containerColor = AlaktraCard),
            border = BorderStroke(1.dp, AlaktraBorder),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(AlaktraMint.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = AlaktraMint,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Song Cache Memory Cap",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = AlaktraTextPrimary
                            )
                            Text(
                                text = "Storage limit for offline playback & streaming",
                                style = MaterialTheme.typography.bodySmall,
                                color = AlaktraTextSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AlaktraTextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Storage usage overview card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = AlaktraCardElevated),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AlaktraBorder.copy(alpha = 0.7f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Current Audio Cache",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AlaktraTextSecondary
                                )
                                Text(
                                    text = String.format(Locale.US, "%.1f MB used", currentUsageMb),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Proposed Cap",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AlaktraTextSecondary
                                )
                                Text(
                                    text = AudioCache.formatCapMb(selectedCapMb),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraMint
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Progress bar
                        LinearProgressIndicator(
                            progress = { usageRatio },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (willEvict) Color(0xFFF59E0B) else AlaktraMint,
                            trackColor = AlaktraBorder,
                            strokeCap = StrokeCap.Round
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(usageRatio * 100f).roundToInt()}% of proposed cap",
                                style = MaterialTheme.typography.labelSmall,
                                color = AlaktraTextMuted
                            )
                            Text(
                                text = "Holds ~${AudioCache.estimateSongsForCap(selectedCapMb)} full tracks",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = AlaktraCyan
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Quick Presets
                Text(
                    text = "Quick Presets",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = AlaktraTextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AudioCache.PRESET_CAPS_MB.forEach { presetMb ->
                        val isSelected = selectedCapMb == presetMb
                        val label = AudioCache.formatCapMb(presetMb)
                        val isDefault = presetMb == AudioCache.DEFAULT_CACHE_CAP_MB

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) AlaktraMint.copy(alpha = 0.2f) else AlaktraCardElevated,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AlaktraMint else AlaktraBorder
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedCapMb = presetMb }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = AlaktraMint,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = if (isDefault) "$label (Default)" else label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (isSelected) AlaktraMint else AlaktraTextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Custom Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Fine Tune Memory Cap",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = AlaktraTextPrimary
                    )
                    Text(
                        text = "${selectedCapMb} MB",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = AlaktraMint
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Slider(
                    value = selectedCapMb.toFloat(),
                    onValueChange = { newVal ->
                        // Snap to 64 MB multiples
                        val rounded = ((newVal / 64f).roundToInt() * 64).coerceIn(
                            AudioCache.MIN_CACHE_CAP_MB,
                            4096
                        )
                        selectedCapMb = rounded
                    },
                    valueRange = AudioCache.MIN_CACHE_CAP_MB.toFloat()..4096f,
                    colors = SliderDefaults.colors(
                        thumbColor = AlaktraMint,
                        activeTrackColor = AlaktraMint,
                        inactiveTrackColor = AlaktraBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cache_cap_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "64 MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlaktraTextMuted
                    )
                    Text(
                        text = "512 MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlaktraTextMuted
                    )
                    Text(
                        text = "2.0 GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlaktraTextMuted
                    )
                    Text(
                        text = "4.0 GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlaktraTextMuted
                    )
                }

                // Eviction warning if reducing below current usage
                AnimatedVisibility(visible = willEvict) {
                    Column {
                        Spacer(modifier = Modifier.height(14.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2008)),
                            border = BorderStroke(1.dp, Color(0xFFB45309).copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Lowering the cap below current usage will immediately evict ~${AudioCache.formatBytes(excessBytes)} of least-recently played song chunks to free storage space.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFDE68A)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Educational note about LRU and offline backup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AlaktraTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Songs in cache memory automatically appear in your 'Offline Backup' playlist so playback never stops when disconnected. Permanent offline downloads are kept separate and never evicted.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AlaktraTextMuted,
                        lineHeight = 15.sp
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (selectedCapMb != AudioCache.DEFAULT_CACHE_CAP_MB) {
                        TextButton(
                            onClick = { selectedCapMb = AudioCache.DEFAULT_CACHE_CAP_MB },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Reset (512 MB)",
                                style = MaterialTheme.typography.labelMedium,
                                color = AlaktraTextSecondary
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onDismissRequest,
                            border = BorderStroke(1.dp, AlaktraBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = AlaktraTextSecondary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Cancel")
                        }

                        Button(
                            onClick = { onApplyCap(selectedCapMb) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AlaktraMint,
                                contentColor = Color(0xFF0C0D12)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("apply_cache_cap_button")
                        ) {
                            Text(
                                text = "Apply Cap",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
