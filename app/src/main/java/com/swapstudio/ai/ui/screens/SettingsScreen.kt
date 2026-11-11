package com.swapstudio.ai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swapstudio.ai.core.swap.PerformanceMode
import com.swapstudio.ai.ui.components.StudioCard
import com.swapstudio.ai.ui.components.StudioModeChip
import com.swapstudio.ai.ui.components.StudioSecondaryButton
import com.swapstudio.ai.ui.components.StudioTopBar
import com.swapstudio.ai.ui.components.StudioToggleRow
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Settings screen: default quality, hardware mode, watermark/consent state, storage and diagnostics.
 *
 * The interesting part for the "S26 Ultra" requirement is the performance section: it shows which
 * execution provider each mode actually resolves to on this device (QNN when the Hexagon libraries are
 * present, NNAPI otherwise).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = SwapColors.Background,
        topBar = {
            StudioTopBar(
                title = "Settings",
                subtitle = "Defaults, hardware, privacy",
                onBack = onBack,
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

            StudioCard {
                Column {
                    Text("HARDWARE", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioModeChip(
                            label = "Battery",
                            caption = "CPU",
                            selected = settings.performanceMode == PerformanceMode.BATTERY_SAVER,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.BATTERY_SAVER) },
                            modifier = Modifier.weight(1f),
                        )
                        StudioModeChip(
                            label = "Balanced",
                            caption = "CPU+GPU",
                            selected = settings.performanceMode == PerformanceMode.BALANCED,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.BALANCED) },
                            modifier = Modifier.weight(1f),
                        )
                        StudioModeChip(
                            label = "Max",
                            caption = "GPU+NPU",
                            selected = settings.performanceMode == PerformanceMode.MAX,
                            onClick = { viewModel.setPerformanceMode(PerformanceMode.MAX) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Active provider: ${viewModel.providerFor(settings.performanceMode)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Text(
                        "FP16 inference only. The QNN provider drives the Hexagon NPU on Snapdragon, NNAPI is the " +
                            "portable fallback. MediaPipe uses the GPU delegate for the 478 point mesh in Max and " +
                            "Balanced mode.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = SwapColors.TextDisabled,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("QUALITY DEFAULTS", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(6.dp))
                    StudioToggleRow(
                        title = "Hyper Realistic Mode",
                        subtitle = "GFPGAN detail pass on by default",
                        checked = settings.hyperRealistic,
                        onCheckedChange = viewModel::setHyperRealistic,
                    )
                    Text(
                        "Enhancer strength ${settings.enhancerStrength}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Slider(
                        value = settings.enhancerStrength.toFloat(),
                        onValueChange = { viewModel.setEnhancerStrength(it.toInt()) },
                        valueRange = 0f..100f,
                        enabled = settings.hyperRealistic,
                        colors = SliderDefaults.colors(
                            thumbColor = SwapColors.Accent,
                            activeTrackColor = SwapColors.Accent,
                            inactiveTrackColor = SwapColors.SurfaceHigh,
                        ),
                    )
                    StudioToggleRow(
                        title = "Mesh overlay",
                        subtitle = "Show the 478 landmarks on the target",
                        checked = settings.showMesh,
                        onCheckedChange = viewModel::setShowMesh,
                    )
                    StudioToggleRow(
                        title = "Upscale exports to 4K",
                        subtitle = "Real-ESRGAN x2 before saving",
                        checked = settings.upscale4k,
                        onCheckedChange = viewModel::setUpscale4k,
                    )
                    StudioToggleRow(
                        title = "PNG photos",
                        subtitle = "Lossless but much larger",
                        checked = settings.exportPng,
                        onCheckedChange = viewModel::setExportPng,
                    )
                    StudioToggleRow(
                        title = "Film grain matching",
                        subtitle = "Copy the target's grain onto the swapped face",
                        checked = settings.grainMatch,
                        onCheckedChange = viewModel::setGrainMatch,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("VIDEO DEFAULTS", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(6.dp))
                    StudioToggleRow(
                        title = "H.265 / HEVC",
                        subtitle = "Half the size at the same quality",
                        checked = settings.hevc,
                        onCheckedChange = viewModel::setHevc,
                    )
                    StudioToggleRow(
                        title = "Keep original audio",
                        subtitle = "Stream copy - no re-encode",
                        checked = settings.copyAudio,
                        onCheckedChange = viewModel::setCopyAudio,
                    )
                    Text(
                        "Bitrate ${settings.bitrateMbps} Mbps",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Slider(
                        value = settings.bitrateMbps.toFloat(),
                        onValueChange = { viewModel.setBitrate(it.toInt()) },
                        valueRange = 12f..48f,
                        colors = SliderDefaults.colors(
                            thumbColor = SwapColors.Accent,
                            activeTrackColor = SwapColors.Accent,
                            inactiveTrackColor = SwapColors.SurfaceHigh,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("PRIVACY & SAFETY", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Everything runs on this device: no image, video or face embedding is ever uploaded. " +
                            "The only network traffic is the one-time model download.\n\n" +
                            "Every export gets a visible \"AI Generated\" label and metadata that marks it as AI " +
                            "generated. Explicit content is blocked before processing. Consent from the people in " +
                            "the media is mandatory.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SwapColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    StudioToggleRow(
                        title = "Consent accepted",
                        subtitle = "Required before any swap can run",
                        checked = settings.consentAccepted,
                        onCheckedChange = viewModel::setConsent,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("DIAGNOSTICS", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(6.dp))
                    viewModel.diagnostics().forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SwapColors.TextSecondary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.labelSmall,
                                color = SwapColors.TextPrimary,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudioSecondaryButton(
                            text = "Reset app",
                            onClick = viewModel::reset,
                            modifier = Modifier.weight(1f),
                        )
                        StudioSecondaryButton(
                            text = "Delete models",
                            onClick = viewModel::deleteAllModels,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "SwapStudio AI 1.0 - Kotlin, Compose, ONNX Runtime, MediaPipe, OpenCV",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                color = SwapColors.TextDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
