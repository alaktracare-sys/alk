package com.example.ui.screens.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ArtistProfile
import com.example.model.Song
import com.example.ui.components.SongCover
import com.example.ui.theme.*

val STUDIO_ACCENT_SWATCHES = listOf(
    "#00E599" to Color(0xFF00E599),
    "#00D2FF" to Color(0xFF00D2FF),
    "#8B5CF6" to Color(0xFF8B5CF6),
    "#F43F5E" to Color(0xFFF43F5E),
    "#F59E0B" to Color(0xFFF59E0B),
    "#3B82F6" to Color(0xFF3B82F6)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeaturedCollaboratorsSelector(
    primaryArtistId: Int,
    catalogArtists: List<ArtistProfile>,
    selectedFeaturedIds: List<Int>,
    onToggleFeaturedId: (Int) -> Unit,
    customFeaturedNames: List<String>,
    onAddCustomName: (String) -> Unit,
    onRemoveCustomName: (String) -> Unit
) {
    var searchText by remember { mutableStateOf("") }

    val matchingCatalog = remember(catalogArtists, primaryArtistId, searchText, selectedFeaturedIds) {
        val q = searchText.trim().lowercase()
        catalogArtists
            .filter { it.id != primaryArtistId && !selectedFeaturedIds.contains(it.id) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
            .take(12)
    }

    val selectedCatalogProfiles = remember(catalogArtists, selectedFeaturedIds) {
        selectedFeaturedIds.mapNotNull { id -> catalogArtists.firstOrNull { it.id == id } }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Featured Collaborators (Optional)",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = AlaktraTextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Allot featured artists alongside your primary stage credit.",
            style = MaterialTheme.typography.bodySmall,
            color = AlaktraTextSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))

        if (selectedCatalogProfiles.isNotEmpty() || customFeaturedNames.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                selectedCatalogProfiles.forEach { artist ->
                    InputChip(
                        selected = true,
                        onClick = { onToggleFeaturedId(artist.id) },
                        label = {
                            Text(
                                text = artist.name,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove ${artist.name}",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = InputChipDefaults.inputChipColors(
                            selectedContainerColor = AlaktraViolet.copy(alpha = 0.22f),
                            selectedLabelColor = AlaktraTextPrimary,
                            selectedTrailingIconColor = AlaktraTextSecondary
                        ),
                        border = InputChipDefaults.inputChipBorder(
                            enabled = true,
                            selected = true,
                            selectedBorderColor = AlaktraViolet.copy(alpha = 0.6f)
                        )
                    )
                }

                customFeaturedNames.forEach { customName ->
                    InputChip(
                        selected = true,
                        onClick = { onRemoveCustomName(customName) },
                        label = {
                            Text(
                                text = "$customName (new)",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove $customName",
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        colors = InputChipDefaults.inputChipColors(
                            selectedContainerColor = AlaktraCyan.copy(alpha = 0.20f),
                            selectedLabelColor = AlaktraTextPrimary,
                            selectedTrailingIconColor = AlaktraTextSecondary
                        ),
                        border = InputChipDefaults.inputChipBorder(
                            enabled = true,
                            selected = true,
                            selectedBorderColor = AlaktraCyan.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Search catalog or type collaborator name...", fontSize = 13.sp, color = AlaktraTextMuted) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AlaktraTextPrimary,
                    unfocusedTextColor = AlaktraTextPrimary,
                    focusedContainerColor = AlaktraSurface,
                    unfocusedContainerColor = AlaktraSurface,
                    focusedBorderColor = AlaktraMint,
                    unfocusedBorderColor = AlaktraBorder
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("studio_collaborator_search_input")
            )

            if (searchText.trim().length >= 2) {
                val exactCatalog = catalogArtists.firstOrNull {
                    it.id != primaryArtistId && it.name.equals(searchText.trim(), ignoreCase = true)
                }
                OutlinedButton(
                    onClick = {
                        if (exactCatalog != null) {
                            if (!selectedFeaturedIds.contains(exactCatalog.id)) {
                                onToggleFeaturedId(exactCatalog.id)
                            }
                        } else {
                            onAddCustomName(searchText.trim())
                        }
                        searchText = ""
                    },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AlaktraMint),
                    modifier = Modifier.height(52.dp)
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add collaborator", tint = AlaktraMint, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add", color = AlaktraMint, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (matchingCatalog.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matchingCatalog, key = { it.id }) { candidate ->
                    AssistChip(
                        onClick = {
                            onToggleFeaturedId(candidate.id)
                            searchText = ""
                        },
                        label = { Text("+ ${candidate.name}", color = AlaktraTextPrimary, fontSize = 12.sp) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = AlaktraSurface),
                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = AlaktraBorder)
                    )
                }
            }
        }
    }
}

@Composable
fun EditSongCreditsDialog(
    song: Song,
    primaryArtist: ArtistProfile,
    catalogArtists: List<ArtistProfile>,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, featuredIds: List<Int>, featuredNames: List<String>) -> Unit
) {
    var editedTitle by remember(song.id) { mutableStateOf(song.title) }
    var featuredIds by remember(song.id) {
        mutableStateOf(
            song.artists
                .filter { !it.role.equals("primary", ignoreCase = true) && it.id != primaryArtist.id && it.id > 0 }
                .map { it.id }
        )
    }
    var customNames by remember(song.id) { mutableStateOf<List<String>>(emptyList()) }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        containerColor = AlaktraCard,
        titleContentColor = AlaktraTextPrimary,
        textContentColor = AlaktraTextSecondary,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SongCover(imageUrl = song.coverUrl, size = 40.dp, cornerRadius = 8.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Edit Track Credits", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text("Primary: ${primaryArtist.name}", fontSize = 12.sp, color = AlaktraMint)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = editedTitle,
                    onValueChange = { editedTitle = it },
                    label = { Text("Track Title") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AlaktraTextPrimary,
                        unfocusedTextColor = AlaktraTextPrimary,
                        focusedContainerColor = AlaktraSurface,
                        unfocusedContainerColor = AlaktraSurface,
                        focusedBorderColor = AlaktraMint,
                        unfocusedBorderColor = AlaktraBorder
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                FeaturedCollaboratorsSelector(
                    primaryArtistId = primaryArtist.id,
                    catalogArtists = catalogArtists,
                    selectedFeaturedIds = featuredIds,
                    onToggleFeaturedId = { id ->
                        featuredIds = if (featuredIds.contains(id)) featuredIds - id else featuredIds + id
                    },
                    customFeaturedNames = customNames,
                    onAddCustomName = { name ->
                        if (customNames.none { it.equals(name, ignoreCase = true) } &&
                            !name.equals(primaryArtist.name, ignoreCase = true)
                        ) {
                            customNames = customNames + name
                        }
                    },
                    onRemoveCustomName = { name ->
                        customNames = customNames.filterNot { it.equals(name, ignoreCase = true) }
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text("Cancel", color = AlaktraTextSecondary)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(editedTitle.trim().ifBlank { song.title }, featuredIds, customNames) },
                enabled = !isSaving && editedTitle.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AlaktraMint, contentColor = Color(0xFF042014)),
                modifier = Modifier.testTag("studio_save_credits_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF042014))
                } else {
                    Text("Save Allotment", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

@Composable
fun SwatchPickerRow(
    selectedHex: String,
    onSelectHex: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        STUDIO_ACCENT_SWATCHES.forEach { (hex, color) ->
            val isSelected = selectedHex.equals(hex, ignoreCase = true)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.22f))
                    .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) color else AlaktraBorder,
                        shape = CircleShape
                    )
                    .clickable { onSelectHex(hex) }
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected accent $hex",
                        tint = Color.Black,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
