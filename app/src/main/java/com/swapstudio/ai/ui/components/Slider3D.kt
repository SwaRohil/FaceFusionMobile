package com.swapstudio.ai.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Before / After slider.
 *
 * Both images are drawn into the same canvas; the "after" image is clipped to the part left of the
 * handle and a vertical line with a round grip is drawn on top. Dragging anywhere on the image moves the
 * split, which is the interaction people expect from CapCut / Remini.
 *
 * @param before the original target
 * @param after the swapped result
 */
@Composable
fun BeforeAfterSlider(
    before: Bitmap,
    after: Bitmap,
    modifier: Modifier = Modifier,
    initial: Float = 0.5f,
) {
    var split by remember { mutableFloatStateOf(initial.coerceIn(0f, 1f)) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SwapColors.SurfaceElevated)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    split = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                }
            },
    ) {
        androidx.compose.foundation.Image(
            bitmap = after.asImageBitmap(),
            contentDescription = "After",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val image = before.asImageBitmap()
            val scale = minOf(size.width / image.width, size.height / image.height)
            val drawWidth = image.width * scale
            val drawHeight = image.height * scale
            val left = (size.width - drawWidth) / 2f
            val top = (size.height - drawHeight) / 2f
            val x = size.width * split
            clipRect(right = x) {
                drawImage(
                    image = image,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(image.width, image.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                    dstSize = androidx.compose.ui.unit.IntSize(drawWidth.toInt(), drawHeight.toInt()),
                )
            }
            // Divider + grip.
            drawLine(
                color = SwapColors.Accent,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 3f,
            )
            drawCircle(color = SwapColors.Accent, radius = 22f, center = Offset(x, size.height / 2f))
            drawCircle(color = Color(0xFF00120E), radius = 16f, center = Offset(x, size.height / 2f))
            drawArc(
                color = SwapColors.Accent,
                startAngle = -50f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = Offset(x - 9f, size.height / 2f - 9f),
                size = Size(18f, 18f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
        }
        Text(
            text = "BEFORE",
            style = MaterialTheme.typography.labelSmall,
            color = SwapColors.TextPrimary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            text = "AFTER",
            style = MaterialTheme.typography.labelSmall,
            color = SwapColors.Accent,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Placeholder that keeps the layout stable while a bitmap is loading. */
@Composable
fun MediaPlaceholder(modifier: Modifier = Modifier, text: String = "Loading") {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(SwapColors.SurfaceElevated, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SwapColors.TextSecondary)
    }
}
