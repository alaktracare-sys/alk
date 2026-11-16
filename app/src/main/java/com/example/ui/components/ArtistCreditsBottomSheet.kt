package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.Song
import com.example.model.SongArtistCredit
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistCreditsBottomSheet(
    song: Song,
    onDismiss: () -> Unit,
    onSelectArtist: (artistId: Int, artistName: String) -> Unit
) {
    val credits = remember(song) {
        if (song.artists.isNotEmpty()) {
            song.artists.sortedWith(
                compareBy<SongArtistCredit> { if (it.role.equals("primary", ignoreCase = true)) 0 else 1 }
                    .thenBy { it.position }
            )
        } else {
            listOf(
                SongArtistCredit(
                    id = song.primaryArtistId ?: 0,
                    name = song.primaryArtistName,
                    role = "primary",
                    position = 0
                )
            )
        }
    }

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
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .testTag("artist_credits_sheet")
            ) {
                Text(
                    text = stringResource(R.string.artist_credits_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = AlaktraTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${song.title} • ${stringResource(R.string.artist_credits_subtitle)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AlaktraTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = AlaktraBorder)
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = credits,
                        key = { "${it.id}_${it.role}_${it.name}" }
                    ) { credit ->
                        ArtistCreditRow(
                            credit = credit,
                            fallbackArtUrl = song.coverUrl,
                            onClick = {
                                onDismiss()
                                onSelectArtist(credit.id, credit.name)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ArtistCreditRow(
    credit: SongArtistCredit,
    fallbackArtUrl: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isPrimary = credit.role.equals("primary", ignoreCase = true)
    val avatarModelUrl = credit.avatarUrl.ifBlank { fallbackArtUrl }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(AlaktraSurface)
            .border(1.dp, AlaktraBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("artist_credit_row_${credit.id}")
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        if (isPrimary) listOf(AlaktraMint.copy(alpha = 0.35f), AlaktraCyan.copy(alpha = 0.25f))
                        else listOf(AlaktraViolet.copy(alpha = 0.35f), AlaktraCyan.copy(alpha = 0.2f))
                    )
                )
        ) {
            if (avatarModelUrl.isNotBlank()) {
                AsyncImage(
                    model = remember(avatarModelUrl) {
                        ImageRequest.Builder(context)
                            .data(avatarModelUrl)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = credit.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = credit.name,
                    tint = AlaktraTextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = credit.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = AlaktraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (credit.isVerified) {
                    Spacer(modifier = Modifier.width(5.dp))
                    Icon(
                        imageVector = Icons.Default.Verified,
                        contentDescription = stringResource(R.string.artist_verified_badge),
                        tint = AlaktraCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (isPrimary) AlaktraMint.copy(alpha = 0.16f)
                        else AlaktraViolet.copy(alpha = 0.18f)
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (isPrimary) stringResource(R.string.artist_role_primary)
                    else stringResource(R.string.artist_role_featured),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isPrimary) AlaktraMint else Color(0xFFC4B5FD)
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = stringResource(R.string.menu_view_artist),
            tint = AlaktraTextSecondary
        )
    }
}
