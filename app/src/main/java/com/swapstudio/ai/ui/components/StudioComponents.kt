package com.swapstudio.ai.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Gradient top bar used by every screen.
 *
 * @param title screen title
 * @param subtitle optional second line (e.g. "3 faces found")
 * @param onBack shows a back arrow when not null
 * @param actions trailing content (settings, mesh toggle, ...)
 */
@Composable
fun StudioTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(SwapColors.Surface, SwapColors.Background),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = SwapColors.TextPrimary)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = SwapColors.TextPrimary,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                }
            }
            actions()
        }
    }
}

/** Thick studio action button (the big "SWAP" / "DOWNLOAD TO PHONE" buttons). */
@Composable
fun StudioPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Color = SwapColors.Accent,
    icon: (@Composable () -> Unit)? = null,
) {
    val background by animateColorAsState(
        targetValue = if (enabled) accent else SwapColors.SurfaceHigh,
        animationSpec = tween(220),
        label = "buttonBackground",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) Color(0xFF00120E) else SwapColors.TextDisabled,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

/** Secondary outlined button. */
@Composable
fun StudioSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, SwapColors.Outline, RoundedCornerShape(14.dp))
            .background(SwapColors.SurfaceElevated)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) SwapColors.TextPrimary else SwapColors.TextDisabled,
        )
    }
}

/**
 * Row of colour swatches for a single-choice setting (used by the mesh overlay colour).
 *
 * Each swatch is a circle in its own colour; the selected one gets a ring so it is obvious even for
 * low-contrast palettes, and the whole row is a single row of 40 dp touch targets.
 */
@Composable
fun StudioColorSwatches(
    colors: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String>? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEachIndexed { index, argb ->
            val isSelected = argb == selected
            val colour = Color(argb)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) SwapColors.TextPrimary else SwapColors.Outline,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(argb) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 26.dp else 22.dp)
                        .background(colour, CircleShape),
                )
            }
            if (labels != null && labels.getOrNull(index) != null) {
                Text(
                    labels[index],
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) SwapColors.TextPrimary else SwapColors.TextSecondary,
                )
                if (index != colors.lastIndex) Spacer(Modifier.width(2.dp))
            }
        }
    }
}

/** Card used by every panel: rounded, dark, one pixel outline. */
@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SwapColors.Surface)
            .border(1.dp, SwapColors.Outline, RoundedCornerShape(20.dp))
            .padding(padding.dp),
    ) {
        content()
    }
}

/** Toggle row with a title, an explanation and a Material3 switch. */
@Composable
fun StudioToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SwapColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SwapColors.TextSecondary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00120E),
                checkedTrackColor = SwapColors.Accent,
                uncheckedThumbColor = SwapColors.TextSecondary,
                uncheckedTrackColor = SwapColors.SurfaceHigh,
                uncheckedBorderColor = SwapColors.Outline,
            ),
        )
    }
}

/** Horizontal progress bar with an animated fill. */
@Composable
fun StudioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = SwapColors.Accent,
    height: Int = 8,
) {
    val animated by animateFloatAsState(targetValue = progress.coerceIn(0f, 1f), animationSpec = tween(300), label = "progress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp))
            .background(SwapColors.SurfaceHigh),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(height.dp)
                .clip(RoundedCornerShape(height.dp))
                .background(Brush.horizontalGradient(listOf(color, SwapColors.Secondary))),
        )
    }
}

/** Small pill label ("AI GENERATED", "4K", "H.265"). */
@Composable
fun StudioBadge(
    text: String,
    color: Color = SwapColors.Accent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** Warning / info banner with an icon. */
@Composable
fun StudioBanner(
    text: String,
    isWarning: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tint = if (isWarning) SwapColors.Warning else SwapColors.Success
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isWarning) Icons.Filled.Warning else Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SwapColors.TextPrimary)
    }
}

/**
 * Circular gauge for the identity score / progress rings.
 *
 * @param value 0..1
 * @param label centre text
 */
@Composable
fun StudioRingGauge(
    value: Float,
    label: String,
    caption: String? = null,
    modifier: Modifier = Modifier,
    color: Color = SwapColors.Accent,
) {
    val animated by animateFloatAsState(targetValue = value.coerceIn(0f, 1f), animationSpec = tween(500), label = "gauge")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(96.dp)) {
                val stroke = size.minDimension * 0.09f
                drawArc(
                    color = SwapColors.SurfaceHigh,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = stroke),
                    size = Size(size.width - stroke, size.height - stroke),
                    topLeft = Offset(stroke / 2, stroke / 2),
                )
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    style = Stroke(width = stroke),
                    size = Size(size.width - stroke, size.height - stroke),
                    topLeft = Offset(stroke / 2, stroke / 2),
                )
            }
            Text(label, style = MaterialTheme.typography.titleMedium, color = SwapColors.TextPrimary)
        }
        if (caption != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = SwapColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Section header inside a card ("QUALITY", "EXPORT"). */
@Composable
fun StudioSectionTitle(text: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = SwapColors.TextSecondary)
        }
    }
}

/** "Power" chip for the performance mode row. */
@Composable
fun StudioModeChip(
    label: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) SwapColors.Accent.copy(alpha = 0.16f) else SwapColors.SurfaceElevated)
            .border(1.dp, if (selected) SwapColors.Accent else SwapColors.Outline, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.Bolt,
            contentDescription = null,
            tint = if (selected) SwapColors.Accent else SwapColors.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) SwapColors.Accent else SwapColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 10.sp),
            color = SwapColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
