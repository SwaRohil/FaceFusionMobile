package com.swapstudio.ai.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swapstudio.ai.core.swap.PerformanceMode
import com.swapstudio.ai.ui.components.PhotoPickTile
import com.swapstudio.ai.ui.components.StudioBadge
import com.swapstudio.ai.ui.components.StudioBanner
import com.swapstudio.ai.ui.components.StudioCard
import com.swapstudio.ai.ui.components.StudioModeChip
import com.swapstudio.ai.ui.components.StudioPrimaryButton
import com.swapstudio.ai.ui.components.StudioProgressBar
import com.swapstudio.ai.ui.components.StudioSecondaryButton
import com.swapstudio.ai.ui.components.StudioTopBar
import com.swapstudio.ai.ui.components.StudioToggleRow
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Video swap screen.
 *
 * Everything the long render depends on is visible before the user commits: source resolution, fps,
 * duration, estimated file size and the hardware mode. During the render the live fps, ETA and frame
 * counter come from [VideoProcessor]; the same numbers are mirrored into the foreground notification so
 * the user can lock the phone.
 */
@Composable
fun VideoSwapScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: VideoSwapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) viewModel.onVideoPicked(uri)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) viewModel.onSourcePicked(uri)
    }

    Scaffold(
        containerColor = SwapColors.Background,
        topBar = {
            StudioTopBar(
                title = "Video swap",
                subtitle = when {
                    !state.consentAccepted -> "Consent required"
                    state.busy -> state.progress.stage
                    state.videoUri != null -> "${viewModel.durationLabel(state.videoDurationMs)} - ${state.videoWidth}x${state.videoHeight} @ %.0f fps".format(state.videoFps)
                    else -> "Pick a clip and an identity photo"
                },
                onBack = onBack,
                actions = {
                    if (state.progress.fps > 0f) {
                        StudioBadge(text = "%.1f fps".format(state.progress.fps), color = SwapColors.Success)
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

            // ---- clip picker ----------------------------------------------------------------
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SwapColors.SurfaceElevated)
                    .border(1.dp, SwapColors.Outline, RoundedCornerShape(20.dp))
                    .clickable { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                contentAlignment = Alignment.Center,
            ) {
                if (state.videoThumbnail != null) {
                    Image(
                        bitmap = state.videoThumbnail!!.asImageBitmap(),
                        contentDescription = "Selected clip",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(32.dp))
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StudioBadge(text = viewModel.durationLabel(state.videoDurationMs))
                        StudioBadge(text = "${state.videoWidth}x${state.videoHeight}", color = SwapColors.Secondary)
                        StudioBadge(text = if (state.hevc) "H.265" else "H.264", color = SwapColors.Warning)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Movie, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pick a video\n1080p / 4K, 30 / 60 fps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SwapColors.TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (state.videoName.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    state.videoName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SwapColors.TextDisabled,
                    maxLines = 1,
                )
            }

            Spacer(Modifier.height(12.dp))
            PhotoPickTile(
                bitmap = state.sourcePreview,
                badge = "Identity",
                hint = "Pick the face to insert",
                onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth(0.46f),
            )
            // The donor photo is restored from the last session; make it easy to get rid of.
            if (state.sourcePreview != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Forget this photo",
                    style = MaterialTheme.typography.labelSmall,
                    color = SwapColors.TextSecondary,
                    modifier = Modifier
                        .clickable { viewModel.clearSource() }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }

            state.blocked?.let {
                Spacer(Modifier.height(12.dp))
                StudioBanner(text = it, isWarning = true)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                StudioBanner(text = it, isWarning = true)
            }
            if (state.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.warnings.take(3).forEach { warning ->
                    StudioBanner(text = warning, isWarning = true)
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- export settings -----------------------------------------------------------
            Spacer(Modifier.height(16.dp))
            StudioCard {
                Column {
                    Text("EXPORT", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1080, 1440, 2160).forEach { height ->
                            StudioModeChip(
                                label = if (height == 2160) "4K" else "${height}p",
                                caption = if (height == 2160) "20+ Mbps" else "High",
                                selected = state.outputHeight == height,
                                onClick = { viewModel.setOutputHeight(height) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    StudioToggleRow(
                        title = "H.265 / HEVC",
                        subtitle = if (state.hevc) "Smaller files, same quality - needs a HEVC capable player" else "H.264 - maximum compatibility",
                        checked = state.hevc,
                        onCheckedChange = viewModel::setHevc,
                    )
                    StudioToggleRow(
                        title = "Keep original audio",
                        subtitle = "Stream copy, no re-encode (ffmpeg -c:a copy)",
                        checked = state.copyAudio,
                        onCheckedChange = viewModel::setCopyAudio,
                    )
                    Text(
                        "Bitrate ${state.bitrateMbps} Mbps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Slider(
                        value = state.bitrateMbps.toFloat(),
                        onValueChange = { viewModel.setBitrate(it.toInt()) },
                        valueRange = 12f..48f,
                        colors = SliderDefaults.colors(
                            thumbColor = SwapColors.Accent,
                            activeTrackColor = SwapColors.Accent,
                            inactiveTrackColor = SwapColors.SurfaceHigh,
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Timer, contentDescription = null, tint = SwapColors.TextSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Estimated output %.2f GB".format(viewModel.estimatedOutputGb()),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                            color = SwapColors.TextDisabled,
                        )
                    }
                }
            }

            // ---- quality -------------------------------------------------------------------
            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("MICRO EXPRESSIONS", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Blendshapes are smoothed with 0.85 previous + 0.15 new over 5 frames, landmarks with a " +
                            "Kalman filter fed by optical flow. Blinks, speech and brow movement stay intact; " +
                            "flicker and identity drift are removed.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = SwapColors.TextDisabled,
                    )
                    Spacer(Modifier.height(8.dp))
                    StudioToggleRow(
                        title = "Hyper Realistic per frame",
                        subtitle = "Eyes, teeth and grain refined on every frame",
                        checked = state.hyperRealistic,
                        onCheckedChange = viewModel::setHyperRealistic,
                    )
                    Text(
                        "Enhancer strength ${state.enhancerStrength}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Slider(
                        value = state.enhancerStrength.toFloat(),
                        onValueChange = { viewModel.setEnhancerStrength(it.toInt()) },
                        valueRange = 0f..100f,
                        enabled = state.hyperRealistic,
                        colors = SliderDefaults.colors(
                            thumbColor = SwapColors.Accent,
                            activeTrackColor = SwapColors.Accent,
                            inactiveTrackColor = SwapColors.SurfaceHigh,
                        ),
                    )
                }
            }

            // ---- performance ---------------------------------------------------------------
            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("PERFORMANCE", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioModeChip(
                            label = "Battery",
                            caption = "CPU",
                            selected = state.performanceMode == PerformanceMode.BATTERY_SAVER,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.BATTERY_SAVER) },
                            modifier = Modifier.weight(1f),
                        )
                        StudioModeChip(
                            label = "Balanced",
                            caption = "CPU+GPU",
                            selected = state.performanceMode == PerformanceMode.BALANCED,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.BALANCED) },
                            modifier = Modifier.weight(1f),
                        )
                        StudioModeChip(
                            label = "Max",
                            caption = "GPU+NPU",
                            selected = state.performanceMode == PerformanceMode.MAX,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.MAX) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ---- consent -------------------------------------------------------------------
            Spacer(Modifier.height(12.dp))
            StudioCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.consentAccepted,
                        onCheckedChange = { viewModel.setConsent(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = SwapColors.Accent,
                            uncheckedColor = SwapColors.Outline,
                            checkmarkColor = Color(0xFF00120E),
                        ),
                    )
                    Text(
                        "I have consent from the person in this clip and I will not use this to deceive. " +
                            "Exports carry a visible and an invisible AI Generated marker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                }
            }

            // ---- run -----------------------------------------------------------------------
            Spacer(Modifier.height(16.dp))
            if (state.busy) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.progress.stage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SwapColors.TextPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text("${state.progress.percent}%", style = MaterialTheme.typography.titleMedium, color = SwapColors.Accent)
                    }
                    Spacer(Modifier.height(6.dp))
                    StudioProgressBar(progress = state.progress.fraction)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Frame ${state.progress.frameIndex}/${state.progress.totalFrames} - %.1f fps - ETA %s".format(
                            state.progress.fps,
                            if (state.progress.etaSeconds >= 0) viewModel.durationLabel(state.progress.etaSeconds * 1000) else "--:--",
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = SwapColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    StudioSecondaryButton(
                        text = "Cancel render",
                        onClick = viewModel::cancelRender,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                StudioPrimaryButton(
                    text = "Start video swap",
                    onClick = { viewModel.startRender(onDone) },
                    enabled = state.canStart,
                    icon = { Icon(Icons.Filled.Movie, contentDescription = null, tint = Color(0xFF00120E), modifier = Modifier.size(18.dp)) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
