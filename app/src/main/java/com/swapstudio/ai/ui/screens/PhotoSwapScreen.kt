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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swapstudio.ai.core.swap.PerformanceMode
import com.swapstudio.ai.ui.components.FaceThumbnailStrip
import com.swapstudio.ai.ui.components.SourceTargetPicker
import com.swapstudio.ai.ui.components.StudioBadge
import com.swapstudio.ai.ui.components.StudioBanner
import com.swapstudio.ai.ui.components.StudioCard
import com.swapstudio.ai.ui.components.StudioModeChip
import com.swapstudio.ai.ui.components.StudioPrimaryButton
import com.swapstudio.ai.ui.components.StudioProgressBar
import com.swapstudio.ai.ui.components.StudioTopBar
import com.swapstudio.ai.ui.components.StudioToggleRow
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Photo swap screen.
 *
 * Layout, top to bottom: source/target pickers, per image face selectors, quality controls (Hyper
 * Realistic toggle + enhancer slider, mesh overlay, 4K, PNG), the performance mode chips, the consent
 * gate and the big SWAP button with its progress readout.
 */
@Composable
fun PhotoSwapScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: PhotoSwapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingSlot by remember { mutableStateOf(PhotoSlot.SOURCE) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) viewModel.onImagePicked(pendingSlot, uri)
    }
    fun openPicker(slot: PhotoSlot) {
        pendingSlot = slot
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Mesh overlay: computed off the main thread when the toggle turns on.
    var meshPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(state.showMesh, state.targetUri, state.targetIndex) {
        meshPreview = if (state.showMesh) viewModel.meshPreview() else null
    }

    Scaffold(
        containerColor = SwapColors.Background,
        topBar = {
            StudioTopBar(
                title = "Photo swap",
                subtitle = when {
                    !state.consentAccepted -> "Consent required"
                    state.busy -> state.stage
                    state.analysis != null -> "${viewModel.targetFaceCount()} face(s) in the target"
                    else -> "Pick a source and a target"
                },
                onBack = onBack,
                actions = {
                    if (state.lastIdentityScore > 0f) {
                        StudioBadge(text = "ID %.2f".format(state.lastIdentityScore), color = SwapColors.Success)
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
            SourceTargetPicker(
                source = state.sourcePreview,
                target = state.targetPreview,
                onPickSource = { openPicker(PhotoSlot.SOURCE) },
                onPickTarget = { openPicker(PhotoSlot.TARGET) },
                onSwap = viewModel::swapRoles,
            )
            // The previous session is restored automatically; this is the way out of it.
            if (state.sourcePreview != null || state.targetPreview != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Forget these photos",
                    style = MaterialTheme.typography.labelSmall,
                    color = SwapColors.TextSecondary,
                    modifier = Modifier
                        .clickable {
                            viewModel.clearImages()
                            viewModel.forgetSession()
                        }
                        .padding(vertical = 4.dp, horizontal = 2.dp),
                )
            }

            if (state.sourceFaces.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("SOURCE FACE", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                Spacer(Modifier.height(6.dp))
                FaceThumbnailStrip(
                    faces = state.sourceFaces,
                    selected = state.sourceIndex,
                    onSelect = viewModel::selectSourceFace,
                    label = "Source face",
                )
            }
            if (state.targetFaces.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text("TARGET FACE", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                Spacer(Modifier.height(6.dp))
                FaceThumbnailStrip(
                    faces = state.targetFaces,
                    selected = state.targetIndex,
                    onSelect = viewModel::selectTargetFace,
                    label = "Target face",
                )
            }

            // Warnings from the analysis (small face, soft source, missing mesh points).
            if (state.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                state.warnings.take(3).forEach { warning ->
                    StudioBanner(text = warning, isWarning = true)
                    Spacer(Modifier.height(6.dp))
                }
            }
            state.blocked?.let {
                Spacer(Modifier.height(12.dp))
                StudioBanner(text = it, isWarning = true)
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                StudioBanner(text = it, isWarning = true)
            }

            // Mesh overlay preview.
            if (state.showMesh && meshPreview != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, SwapColors.Accent.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                ) {
                    Image(
                        bitmap = meshPreview!!.asImageBitmap(),
                        contentDescription = "478 point mesh overlay",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StudioBadge(
                        text = "478 pt mesh",
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            StudioCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.HighQuality, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("QUALITY", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    }
                    Spacer(Modifier.height(4.dp))
                    StudioToggleRow(
                        title = "Hyper Realistic Mode",
                        subtitle = "GFPGAN detail pass, pores and moles survive",
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
                    Text(
                        "Higher keeps more of the original face (identity, moles, wrinkles); lower lets the " +
                            "enhancer invent finer detail. 70-85 % is the sweet spot.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = SwapColors.TextDisabled,
                    )
                    Spacer(Modifier.height(8.dp))
                    StudioToggleRow(
                        title = "Show 478 point mesh",
                        subtitle = "Debug overlay: landmarks, eye and lip rings",
                        checked = state.showMesh,
                        onCheckedChange = viewModel::setShowMesh,
                    )
                    StudioToggleRow(
                        title = "Upscale to 4K",
                        subtitle = "Real-ESRGAN x2 tiled pass before export",
                        checked = state.upscale4k,
                        onCheckedChange = viewModel::setUpscale4k,
                    )
                    StudioToggleRow(
                        title = "PNG export (lossless)",
                        subtitle = if (state.exportPng) "PNG, files are 3-4x larger" else "JPEG quality 100",
                        checked = state.exportPng,
                        onCheckedChange = viewModel::setExportPng,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = SwapColors.Warning, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("PERFORMANCE", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioModeChip(
                            label = "Battery",
                            caption = "CPU only",
                            selected = state.performanceMode == PerformanceMode.BATTERY_SAVER,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.BATTERY_SAVER) },
                            modifier = Modifier.weight(1f),
                        )
                        StudioModeChip(
                            label = "Balanced",
                            caption = "CPU + GPU",
                            selected = state.performanceMode == PerformanceMode.BALANCED,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.BALANCED) },
                            modifier = Modifier.weight(1f),
                        )
                        StudioModeChip(
                            label = "Max",
                            caption = "GPU + NPU",
                            selected = state.performanceMode == PerformanceMode.MAX,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.MAX) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Consent gate (mandatory).
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
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = SwapColors.TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "I have consent from the person in both photos and I will not use this to deceive. " +
                            "Every export is labelled AI Generated.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (state.busy) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = SwapColors.Accent,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            state.stage.ifBlank { "Working" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SwapColors.TextPrimary,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.titleMedium,
                            color = SwapColors.Accent,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    StudioProgressBar(progress = state.progress)
                    Spacer(Modifier.height(12.dp))
                }
            }
            StudioPrimaryButton(
                text = if (state.busy) "Swapping..." else "Swap faces",
                onClick = { viewModel.runSwap(onDone) },
                enabled = state.canSwap,
                icon = { Icon(Icons.Filled.Face, contentDescription = null, tint = Color(0xFF00120E), modifier = Modifier.size(18.dp)) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Everything runs on this device. Nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = SwapColors.TextDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
