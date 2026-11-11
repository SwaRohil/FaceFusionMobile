package com.swapstudio.ai.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Square "pick a photo" tile.
 *
 * @param bitmap preview, or null for the empty state
 * @param badge text over the top left corner ("SOURCE" / "TARGET")
 * @param hint text shown in the empty state
 * @param onClick opens the picker
 */
@Composable
fun PhotoPickTile(
    bitmap: Bitmap?,
    badge: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {
        Icon(Icons.Filled.AddAPhoto, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(28.dp))
    },
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(SwapColors.SurfaceElevated)
            .border(1.dp, SwapColors.Outline, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = badge,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon()
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SwapColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        StudioBadge(
            text = badge,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        )
    }
}

/** Side by side source + target tiles with a swap arrow between them. */
@Composable
fun SourceTargetPicker(
    source: Bitmap?,
    target: Bitmap?,
    onPickSource: () -> Unit,
    onPickTarget: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PhotoPickTile(
            bitmap = source,
            badge = "Source",
            hint = "Identity donor",
            onClick = onPickSource,
            modifier = Modifier.weight(1f),
            icon = { Icon(Icons.Filled.PersonOutline, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(28.dp)) },
        )
        Column(
            modifier = Modifier
                .width(44.dp)
                .clickable(onClick = onSwap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap roles", tint = SwapColors.Secondary, modifier = Modifier.size(26.dp))
        }
        PhotoPickTile(
            bitmap = target,
            badge = "Target",
            hint = "Body and scene",
            onClick = onPickTarget,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Horizontal strip of face thumbnails, one per detected face, for the "tap to select" interaction.
 *
 * @param selected index of the active face
 * @param onSelect called with the tapped index
 */
@Composable
fun FaceThumbnailStrip(
    faces: List<Bitmap>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Face",
) {
    if (faces.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        faces.forEachIndexed { index, bitmap ->
            val isSelected = index == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) SwapColors.Accent else SwapColors.Outline,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelect(index) },
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "$label ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (isSelected) SwapColors.Accent else SwapColors.TextSecondary,
                )
            }
        }
    }
}
