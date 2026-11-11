package com.swapstudio.ai.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swapstudio.ai.ui.components.StudioBadge
import com.swapstudio.ai.ui.components.StudioBanner
import com.swapstudio.ai.ui.components.StudioCard
import com.swapstudio.ai.ui.components.StudioPrimaryButton
import com.swapstudio.ai.ui.components.StudioProgressBar
import com.swapstudio.ai.ui.components.StudioSecondaryButton
import com.swapstudio.ai.ui.components.StudioTopBar
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Landing screen.
 *
 * Four actions: photo swap, video swap, model manager, settings. On top of that it shows the hardware
 * profile (SoC, cores, NPU availability, live RAM/fps) because the whole point of the app is that it runs
 * locally on the phone's accelerators, and the user needs to know which mode they are in.
 */
@Composable
fun HomeScreen(
    onOpenPhoto: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Re-read the model list whenever the screen comes back into view (e.g. after a download).
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    Scaffold(
        containerColor = SwapColors.Background,
        topBar = {
            StudioTopBar(
                title = "SwapStudio AI",
                subtitle = "Hyper realistic - on device",
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = SwapColors.TextSecondary)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            HeroCard(onOpenPhoto = onOpenPhoto, onOpenVideo = onOpenVideo)
            Spacer(Modifier.height(16.dp))

            if (!state.ready) {
                StudioBanner(
                    text = if (state.totalCount == 0) {
                        "Checking the model packs..."
                    } else {
                        "Models missing: ${state.installedCount}/${state.totalCount} installed. Download them once, then everything runs offline."
                    },
                    isWarning = true,
                )
                Spacer(Modifier.height(10.dp))
                StudioPrimaryButton(
                    text = "Open model manager",
                    onClick = onOpenModels,
                    accent = SwapColors.Warning,
                    icon = { Icon(Icons.Filled.Storage, contentDescription = null, tint = Color(0xFF00120E), modifier = Modifier.size(18.dp)) },
                )
                Spacer(Modifier.height(16.dp))
            }

            StudioCard {
                Column {
                    Text(
                        "DEVICE",
                        style = MaterialTheme.typography.labelLarge,
                        color = SwapColors.Accent,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.device.model,
                        style = MaterialTheme.typography.titleLarge,
                        color = SwapColors.TextPrimary,
                    )
                    if (state.device.soc.isNotEmpty()) {
                        Text(
                            text = "SoC ${state.device.soc}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SwapColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioBadge(
                            text = "${state.device.cores} cores",
                            color = SwapColors.Secondary,
                        )
                        if (state.device.qnnAvailable) {
                            StudioBadge(text = "NPU / QNN", color = SwapColors.Success)
                        } else {
                            StudioBadge(text = "NNAPI", color = SwapColors.Warning)
                        }
                        StudioBadge(text = modeLabel(state.settings.performanceMode.name), color = SwapColors.Accent)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "RAM ${state.device.ramLabel}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SwapColors.TextSecondary,
                            )
                            Spacer(Modifier.height(6.dp))
                            val used = if (state.device.ramTotalMb > 0) {
                                1f - state.device.ramAvailableMb.toFloat() / state.device.ramTotalMb
                            } else 0f
                            StudioProgressBar(progress = used, color = SwapColors.Secondary, height = 6)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (state.device.fps > 0f) "%.1f fps".format(state.device.fps) else "idle",
                                style = MaterialTheme.typography.titleMedium,
                                color = SwapColors.Accent,
                            )
                            Text(
                                "swap rate",
                                style = MaterialTheme.typography.labelSmall,
                                color = SwapColors.TextSecondary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MODELS", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${viewModel.formatBytes(state.modelsBytes)} on disk",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SwapColors.TextSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    StudioProgressBar(
                        progress = if (state.totalCount > 0) state.installedCount.toFloat() / state.totalCount else 0f,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.ready) {
                            "All required weights are installed. Fully offline."
                        } else {
                            "Missing ${viewModel.formatBytes(state.missingBytes)} of weights."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    StudioSecondaryButton(text = "Manage models", onClick = onOpenModels)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "100% on device - nothing is uploaded\nAI Generated labels are burned into every export",
                style = MaterialTheme.typography.bodyMedium,
                color = SwapColors.TextDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Big gradient hero with the two primary actions. */
@Composable
private fun HeroCard(onOpenPhoto: () -> Unit, onOpenVideo: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "heroGlow")
    val glow by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "glow",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        SwapColors.Accent.copy(alpha = 0.20f * glow),
                        SwapColors.Secondary.copy(alpha = 0.28f),
                        SwapColors.Surface,
                    ),
                ),
            )
            .border(1.dp, SwapColors.Outline, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("HYPER REALISTIC", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Swap any face.\nKeep every pore.",
                style = MaterialTheme.typography.displaySmall,
                color = SwapColors.TextPrimary,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "478 point mesh, 52 blendshapes, occlusion aware colour matching - all on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = SwapColors.TextSecondary,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(
                    icon = Icons.Filled.PhotoCamera,
                    title = "Photo",
                    subtitle = "4K JPG / PNG",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenPhoto,
                )
                ActionTile(
                    icon = Icons.Filled.VideoFile,
                    title = "Video",
                    subtitle = "H.264 / H.265",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenVideo,
                )
            }
        }
    }
}

/** One big tap target inside the hero card. */
@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(SwapColors.Background.copy(alpha = 0.55f))
            .border(1.dp, SwapColors.Accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Icon(icon, contentDescription = title, tint = SwapColors.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(10.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = SwapColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp), color = SwapColors.TextSecondary)
    }
}

private fun modeLabel(name: String): String = when (name) {
    "BATTERY_SAVER" -> "Battery saver"
    "BALANCED" -> "Balanced"
    else -> "Max (GPU+NPU)"
}

/** Small animated "AI" pulse used while work is running (kept for reuse by other screens). */
@Composable
fun AiPulse(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .size(10.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(50))
            .background(SwapColors.Accent),
    )
}
