package com.example.ui.screens.equalizer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioController
import com.example.audio.EqualizerManager
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    audioController: AudioController,
    onBack: () -> Unit
) {
    val eq = audioController.equalizerManager
    val isEnabled by eq.isEnabled.collectAsState()
    val currentPreset by eq.currentPreset.collectAsState()
    val bandGains by eq.bandGainsDb.collectAsState()
    val bassBoost by eq.bassBoostStrength.collectAsState()
    val virtualizer by eq.virtualizerStrength.collectAsState()
    val playbackState by audioController.playbackState.collectAsState()

    // Smooth animation for frequency curve nodes
    val animGain0 by animateFloatAsState(targetValue = bandGains.getOrElse(0) { 0 }.toFloat(), animationSpec = tween(180), label = "g0")
    val animGain1 by animateFloatAsState(targetValue = bandGains.getOrElse(1) { 0 }.toFloat(), animationSpec = tween(180), label = "g1")
    val animGain2 by animateFloatAsState(targetValue = bandGains.getOrElse(2) { 0 }.toFloat(), animationSpec = tween(180), label = "g2")
    val animGain3 by animateFloatAsState(targetValue = bandGains.getOrElse(3) { 0 }.toFloat(), animationSpec = tween(180), label = "g3")
    val animGain4 by animateFloatAsState(targetValue = bandGains.getOrElse(4) { 0 }.toFloat(), animationSpec = tween(180), label = "g4")
    val animatedGains = listOf(animGain0, animGain1, animGain2, animGain3, animGain4)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Equalizer",
                        color = AlaktraTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
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
                    IconButton(onClick = { eq.resetToFlat() }) {
                        Icon(
                            imageVector = Icons.Default.RestartAlt,
                            contentDescription = "Reset to Flat",
                            tint = AlaktraMint
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AlaktraBackground)
            )
        },
        containerColor = AlaktraBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Now Playing Info Banner (if active)
                playbackState.currentSong?.let { song ->
                    Surface(
                        color = AlaktraSurface.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(AlaktraMint.copy(alpha = 0.15f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = AlaktraMint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LIVE AUDIO FX",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    ),
                                    color = AlaktraMint
                                )
                                Text(
                                    text = "${song.title} • ${song.artist}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = AlaktraTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Master Toggle Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isEnabled) AlaktraMint.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = if (isEnabled) AlaktraMint else AlaktraTextMuted,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = "Equalizer Processing",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraTextPrimary
                                )
                                Text(
                                    text = if (isEnabled) "Active sound shaping applied" else "Bypassed (Flat response)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isEnabled) AlaktraMint else AlaktraTextSecondary
                                )
                            }
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { eq.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF041C12),
                                checkedTrackColor = AlaktraMint,
                                uncheckedThumbColor = AlaktraTextMuted,
                                uncheckedTrackColor = AlaktraSurface
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Visualizer Curve Graph Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(18.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "FREQUENCY RESPONSE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    ),
                                    color = AlaktraTextSecondary
                                )
                                Text(
                                    text = "$currentPreset Mode",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = AlaktraMint
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Interactive Canvas
                            Box(modifier = Modifier.fillMaxSize()) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val w = size.width
                                    val h = size.height
                                    val midY = h / 2f
                                    val maxDbRange = 12f

                                    // Subtle horizontal grid lines at +12dB, 0dB, -12dB
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.05f),
                                        start = Offset(0f, 0f),
                                        end = Offset(w, 0f),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.12f),
                                        start = Offset(0f, midY),
                                        end = Offset(w, midY),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.05f),
                                        start = Offset(0f, h),
                                        end = Offset(w, h),
                                        strokeWidth = 1.dp.toPx()
                                    )

                                    val points = animatedGains.mapIndexed { index, gainDb ->
                                        val x = (index.toFloat() / (animatedGains.size - 1)) * w
                                        // Top is +12dB (y = 0), Bottom is -12dB (y = h)
                                        val normalized = (gainDb / maxDbRange).coerceIn(-1f, 1f)
                                        val y = midY - (normalized * (h * 0.42f))
                                        Offset(x, y)
                                    }

                                    // Build smooth cubic Bezier path
                                    val strokePath = Path().apply {
                                        moveTo(points.first().x, points.first().y)
                                        for (i in 0 until points.size - 1) {
                                            val p0 = points[i]
                                            val p1 = points[i + 1]
                                            val cx = (p0.x + p1.x) / 2f
                                            cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                        }
                                    }

                                    // Build gradient fill path
                                    val fillPath = Path().apply {
                                        addPath(strokePath)
                                        lineTo(w, h)
                                        lineTo(0f, h)
                                        close()
                                    }

                                    val curveBrush = if (isEnabled) {
                                        Brush.horizontalGradient(listOf(AlaktraMint, AlaktraCyan))
                                    } else {
                                        Brush.horizontalGradient(listOf(AlaktraTextMuted, AlaktraTextMuted))
                                    }

                                    val fillBrush = if (isEnabled) {
                                        Brush.verticalGradient(
                                            listOf(
                                                AlaktraMint.copy(alpha = 0.35f),
                                                AlaktraCyan.copy(alpha = 0.08f),
                                                Color.Transparent
                                            )
                                        )
                                    } else {
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.White.copy(alpha = 0.06f),
                                                Color.Transparent
                                            )
                                        )
                                    }

                                    drawPath(path = fillPath, brush = fillBrush)
                                    drawPath(
                                        path = strokePath,
                                        brush = curveBrush,
                                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                    )

                                    // Draw node dots
                                    points.forEach { pt ->
                                        drawCircle(
                                            color = if (isEnabled) Color(0xFF041C12) else Color(0xFF1E2028),
                                            radius = 6.dp.toPx(),
                                            center = pt
                                        )
                                        drawCircle(
                                            color = if (isEnabled) AlaktraMint else AlaktraTextSecondary,
                                            radius = 4.dp.toPx(),
                                            center = pt
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Presets Horizontal Slider
                Text(
                    text = "Acoustic Presets",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EqualizerManager.PRESETS.forEach { preset ->
                        val isSelected = currentPreset.equals(preset.name, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSelected) AlaktraMint else AlaktraSurface,
                            contentColor = if (isSelected) Color(0xFF041C12) else AlaktraTextPrimary,
                            border = if (isSelected) null else CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { eq.selectPreset(preset.name) }
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                ),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // 5-Band Slider Controllers
                Text(
                    text = "Graphic Equalizer (5-Band)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                    shape = RoundedCornerShape(16.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder))),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        EqualizerManager.DEFAULT_FREQUENCY_LABELS.forEachIndexed { index, label ->
                            val gainDb = bandGains.getOrElse(index) { 0 }
                            val subLabel = when (index) {
                                0 -> "Sub-Bass"
                                1 -> "Bass"
                                2 -> "Midrange"
                                3 -> "Upper Mid"
                                4 -> "Treble"
                                else -> ""
                            }

                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = AlaktraTextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "($subLabel)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AlaktraTextSecondary
                                        )
                                    }

                                    Surface(
                                        color = if (gainDb != 0) AlaktraMint.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = when {
                                                gainDb > 0 -> "+$gainDb dB"
                                                gainDb < 0 -> "$gainDb dB"
                                                else -> "0 dB"
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (gainDb != 0) AlaktraMint else AlaktraTextSecondary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Slider(
                                    value = gainDb.toFloat(),
                                    onValueChange = { eq.setBandGain(index, it.toInt()) },
                                    valueRange = -12f..12f,
                                    steps = 23,
                                    enabled = isEnabled,
                                    colors = SliderDefaults.colors(
                                        thumbColor = AlaktraMint,
                                        activeTrackColor = AlaktraMint,
                                        inactiveTrackColor = AlaktraSurface,
                                        disabledThumbColor = AlaktraTextMuted,
                                        disabledActiveTrackColor = AlaktraTextMuted
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            if (index < EqualizerManager.DEFAULT_FREQUENCY_LABELS.size - 1) {
                                Divider(
                                    color = AlaktraBorder.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Acoustic FX: Bass Boost & 3D Virtualizer
                Text(
                    text = "Acoustic Effects",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Bass Boost Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                        shape = RoundedCornerShape(16.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder)))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AlaktraMint.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.GraphicEq,
                                        contentDescription = null,
                                        tint = AlaktraMint,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Bass Boost",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )
                                    Text(
                                        text = "${(bassBoost / 10f).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraMint
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Slider(
                                value = bassBoost.toFloat(),
                                onValueChange = { eq.setBassBoost(it.toInt()) },
                                valueRange = 0f..1000f,
                                enabled = isEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = AlaktraMint,
                                    activeTrackColor = AlaktraMint,
                                    inactiveTrackColor = AlaktraSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 3D Virtualizer Card
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = AlaktraCard),
                        shape = RoundedCornerShape(16.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(AlaktraBorder, AlaktraBorder)))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(AlaktraCyan.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Headset,
                                        contentDescription = null,
                                        tint = AlaktraCyan,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "3D Surround",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraTextPrimary
                                    )
                                    Text(
                                        text = "${(virtualizer / 10f).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                        color = AlaktraCyan
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Slider(
                                value = virtualizer.toFloat(),
                                onValueChange = { eq.setVirtualizer(it.toInt()) },
                                valueRange = 0f..1000f,
                                enabled = isEnabled,
                                colors = SliderDefaults.colors(
                                    thumbColor = AlaktraCyan,
                                    activeTrackColor = AlaktraCyan,
                                    inactiveTrackColor = AlaktraSurface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(120.dp))
            }
        }
    }
}
