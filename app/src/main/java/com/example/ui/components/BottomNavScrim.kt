package com.example.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.ui.theme.AlaktraCard
import com.example.ui.theme.AlaktraTextPrimary

fun bottomNavScrimBrush(bottomColor: Color = Color.Black): Brush = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.42f to bottomColor.copy(alpha = 0.54f),
    0.76f to bottomColor.copy(alpha = 0.90f),
    1.0f to bottomColor
)

/**
 * Reusable bottom navigation bar gradient scrim fading smoothly from transparent to pitch black
 * behind the Android system navigation bar (matching the Home screen bottom fade).
 */
@Composable
fun BottomNavGradientScrim(
    modifier: Modifier = Modifier,
    extraHeight: Dp = 48.dp,
    minHeight: Dp = 72.dp,
    bottomColor: Color = Color.Black
) {
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val scrimHeight = (navBarBottom + extraHeight).coerceAtLeast(minHeight)
    if (scrimHeight > 0.dp) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(scrimHeight)
                .background(bottomNavScrimBrush(bottomColor))
        )
    }
}

/**
 * Edge-to-edge ModalBottomSheet wrapper that lets sheet surfaces draw behind the Android system
 * navigation bar and pins a transparent-to-black gradient scrim at the visible bottom edge
 * across partially expanded, expanded, dragged, and compact states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlaktraModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    containerColor: Color = AlaktraCard,
    contentColor: Color = AlaktraTextPrimary,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    scrimExtraHeight: Dp = 48.dp,
    scrimMinHeight: Dp = 72.dp,
    scrimBottomColor: Color = Color.Black,
    content: @Composable BoxScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        contentColor = contentColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = dragHandle
    ) {
        val density = LocalDensity.current
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val effectiveMinHeight = if (isLandscape && navBarBottom == 0.dp) 0.dp else scrimMinHeight
        val scrimHeight = if (navBarBottom > 0.dp || effectiveMinHeight > 0.dp) {
            (navBarBottom + scrimExtraHeight).coerceAtLeast(effectiveMinHeight)
        } else {
            0.dp
        }
        val scrimHeightPx = with(density) { scrimHeight.toPx() }
        var visibleBottomInBoxPx by remember { mutableFloatStateOf(0f) }
        var hasMeasured by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val rootHeight = coords.findRootCoordinates().size.height.toFloat()
                    val boxTopInRoot = coords.localToRoot(Offset.Zero).y
                    val boxHeight = coords.size.height.toFloat()
                    visibleBottomInBoxPx = (rootHeight - boxTopInRoot).coerceIn(0f, boxHeight)
                    hasMeasured = true
                }
        ) {
            content()

            if (scrimHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(scrimHeight)
                        .zIndex(10f)
                        .graphicsLayer {
                            translationY = (visibleBottomInBoxPx - scrimHeightPx).coerceAtLeast(0f)
                            alpha = if (hasMeasured) 1f else 0f
                        }
                        .background(bottomNavScrimBrush(scrimBottomColor))
                )
            }
        }
    }
}
