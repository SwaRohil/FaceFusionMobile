package com.swapstudio.ai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swapstudio.ai.core.models.DownloadStatus
import com.swapstudio.ai.ui.components.StudioBadge
import com.swapstudio.ai.ui.components.StudioCard
import com.swapstudio.ai.ui.components.StudioPrimaryButton
import com.swapstudio.ai.ui.components.StudioProgressBar
import com.swapstudio.ai.ui.components.StudioSecondaryButton
import com.swapstudio.ai.ui.components.StudioTopBar
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * FaceFusion style model manager.
 *
 * Header: free/used storage with a stacked bar. Below it, one card per model with its size, state,
 * speed, ETA and the action button (DOWNLOAD / PAUSE / RESUME / DELETE). Two global actions: "Download
 * all" and "Pause all".
 */
@Composable
fun ModelManagerScreen(
    onBack: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = SwapColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            StudioTopBar(
                title = "Models",
                subtitle = "${state.rows.count { it.installed }}/${state.rows.size} installed - pause and resume any time",
                onBack = onBack,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                StorageCard(
                    freeGb = state.storage.freeGb,
                    modelsGb = state.storage.modelsGb,
                    modelsFraction = state.storage.modelsFraction,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StudioPrimaryButton(
                        text = if (state.downloadingAnything) "Downloading" else "Download all",
                        onClick = viewModel::downloadAll,
                        enabled = !state.downloadingAnything,
                        modifier = Modifier.weight(1f),
                        icon = {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = Color(0xFF00120E), modifier = Modifier.size(18.dp))
                        },
                    )
                    StudioSecondaryButton(
                        text = "Pause all",
                        onClick = viewModel::pauseAll,
                        modifier = Modifier.weight(1f),
                        enabled = state.downloadingAnything,
                    )
                }
            }
            items(state.rows, key = { it.descriptor.id }) { row ->
                ModelCard(row = row, onAction = { viewModel.onAction(row) }, formatBytes = viewModel::formatBytes)
            }
            item {
                Text(
                    text = "Weights are downloaded once and stay on this device. Nothing is uploaded, and the " +
                        "downloads can be paused at any time - the partial file is resumed with an HTTP range request.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = SwapColors.TextDisabled,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun StorageCard(freeGb: Float, modelsGb: Float, modelsFraction: Float) {
    StudioCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = SwapColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("STORAGE", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.1f GB free".format(freeGb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SwapColors.TextPrimary,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Stacked bar: models in accent, the rest of the used space in grey.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SwapColors.SurfaceHigh),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(modelsFraction.coerceIn(0f, 1f))
                        .height(10.dp)
                        .background(SwapColors.Accent),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Models %.2f GB of the disk".format(modelsGb),
                style = MaterialTheme.typography.bodyMedium,
                color = SwapColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ModelCard(
    row: ModelRow,
    onAction: () -> Unit,
    formatBytes: (Long) -> String,
) {
    val descriptor = row.descriptor
    val state = row.state
    val isActive = state.status == DownloadStatus.DOWNLOADING || state.status == DownloadStatus.QUEUED
    StudioCard {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        descriptor.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = SwapColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        descriptor.description,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                        color = SwapColors.TextDisabled,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(10.dp))
                StudioBadge(
                    text = when {
                        row.installed -> "Installed"
                        state.status == DownloadStatus.PAUSED -> "Paused"
                        state.status == DownloadStatus.ERROR -> "Error"
                        state.status == DownloadStatus.VERIFYING -> "Verifying"
                        isActive -> "Downloading"
                        else -> "${descriptor.sizeMb} MB"
                    },
                    color = when {
                        row.installed -> SwapColors.Success
                        state.status == DownloadStatus.ERROR -> SwapColors.Hot
                        state.status == DownloadStatus.PAUSED -> SwapColors.Warning
                        else -> SwapColors.Accent
                    },
                )
            }
            if (isActive || state.status == DownloadStatus.PAUSED || state.status == DownloadStatus.VERIFYING) {
                Spacer(Modifier.height(10.dp))
                StudioProgressBar(progress = if (state.progress < 0f) 0.02f else state.progress)
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        state.mbLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = SwapColors.TextSecondary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        buildString {
                            if (state.speedMbPerSecond > 0f) append("%.1f MB/s".format(state.speedMbPerSecond))
                            if (state.etaSeconds >= 0) append(" - %ds left".format(state.etaSeconds))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SwapColors.TextSecondary,
                    )
                }
                state.message?.let { message ->
                    // Errors are red, but a paused transfer also has something to say ("waiting for
                    // Wi-Fi", "paused by user") and that is exactly what the user needs to see.
                    if (state.status == DownloadStatus.ERROR || state.status == DownloadStatus.PAUSED) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                            color = if (state.status == DownloadStatus.ERROR) SwapColors.Hot else SwapColors.Warning,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (row.primaryAction) {
                    ModelAction.DOWNLOAD, ModelAction.UPDATE -> StudioSecondaryButton(
                        text = if (row.primaryAction == ModelAction.UPDATE) "Update" else "Download",
                        onClick = onAction,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction.PAUSE -> StudioSecondaryButton(
                        text = "Pause",
                        onClick = onAction,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction.RESUME -> StudioSecondaryButton(
                        text = "Resume",
                        onClick = onAction,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction.DELETE -> StudioSecondaryButton(
                        text = "Delete",
                        onClick = onAction,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction.NONE -> StudioSecondaryButton(
                        text = "Verifying",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.weight(1f),
                    )
                    ModelAction.CANCEL -> StudioSecondaryButton(
                        text = "Cancel",
                        onClick = onAction,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.installed) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = SwapColors.TextDisabled,
                        modifier = Modifier.size(20.dp),
                    )
                } else if (isActive) {
                    Icon(Icons.Filled.Pause, contentDescription = null, tint = SwapColors.TextSecondary, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = SwapColors.TextDisabled, modifier = Modifier.size(18.dp))
                }
            }
            if (row.installed && row.installedBytes > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "On disk: ${formatBytes(row.installedBytes)} - v${descriptor.version}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SwapColors.TextDisabled,
                )
            }
            if (!descriptor.hasHash && !row.installed) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "No published SHA-256 for this asset: the download is verified by exact byte count.",
                    style = MaterialTheme.typography.labelSmall,
                    color = SwapColors.Warning,
                )
            }
        }
    }
}
