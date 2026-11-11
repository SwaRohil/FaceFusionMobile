package com.swapstudio.ai.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.swapstudio.ai.ui.components.BeforeAfterSlider
import com.swapstudio.ai.ui.components.StudioBadge
import com.swapstudio.ai.ui.components.StudioBanner
import com.swapstudio.ai.ui.components.StudioCard
import com.swapstudio.ai.ui.components.StudioPrimaryButton
import com.swapstudio.ai.ui.components.StudioProgressBar
import com.swapstudio.ai.ui.components.StudioSecondaryButton
import com.swapstudio.ai.ui.components.StudioTopBar
import com.swapstudio.ai.ui.shared.ResultHolder
import com.swapstudio.ai.ui.theme.SwapColors

/**
 * Result screen: ExoPlayer preview, before/after slider for photos, identity score, the quality report
 * (resolution, bitrate, audio) and the three actions the spec asks for: DOWNLOAD TO PHONE, SHARE and
 * OPEN. The "AI Generated" label is shown on the preview exactly as it is burned into the export.
 */
@Composable
fun ResultScreen(
    kind: String,
    onBack: () -> Unit,
    onHome: () -> Unit,
    viewModel: ResultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photo = ResultHolder.photo
    val video = ResultHolder.video
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.savedPath) {
        val path = state.savedPath ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            message = "Saved to $path",
            actionLabel = "OPEN",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.openIntent()?.let { intent ->
                runCatching { context.startActivity(intent) }
            }
        }
    }

    Scaffold(
        containerColor = SwapColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            StudioTopBar(
                title = "Result",
                subtitle = when {
                    video != null -> "${video.width}x${video.height} - ${video.frameCount} frames"
                    photo != null -> "${photo.width}x${photo.height} - identity %.2f".format(photo.identityScore)
                    else -> "Nothing to show"
                },
                onBack = onBack,
                actions = {
                    StudioBadge(text = "AI Generated", color = SwapColors.Warning)
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

            when {
                video != null -> VideoPreview(video = video, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                photo != null -> BeforeAfterSlider(
                    before = photo.before,
                    after = photo.after,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                )
                state.savedUri != null -> androidx.compose.foundation.Image(
                    bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentScale = ContentScale.Fit,
                )
                else -> StudioBanner(text = "The result is no longer in memory. Open the gallery instead.", isWarning = true)
            }

            // ---- quality report -----------------------------------------------------------
            Spacer(Modifier.height(12.dp))
            StudioCard {
                Column {
                    Text("QUALITY", style = MaterialTheme.typography.labelLarge, color = SwapColors.Accent)
                    Spacer(Modifier.height(6.dp))
                    val info = state.videoInfo
                    when {
                        video != null -> {
                            InfoRow("Resolution", "${video.width} x ${video.height}")
                            InfoRow("Frames", "${video.frameCount} @ %.0f fps".format(video.fps))
                            InfoRow("Bitrate", viewModel.bitrateLabel())
                            InfoRow("Audio", if (video.hasAudio) "Original track, stream copied" else "No audio")
                            InfoRow("Render time", "${video.elapsedMs / 1000} s")
                            if (info != null && !viewModel.meetsQualityBar() && info.bitrate > 0) {
                                Spacer(Modifier.height(6.dp))
                                StudioBanner(
                                    text = "The encoder produced ${info.width}x${info.height} at ${viewModel.bitrateLabel()}. " +
                                        "If this is below 4K/20 Mbps your device's H.265 encoder capped it - raise the bitrate in Settings " +
                                        "or choose H.264.",
                                    isWarning = true,
                                )
                            }
                        }
                        photo != null -> {
                            InfoRow("Resolution", "${photo.width} x ${photo.height}")
                            InfoRow("Identity score", "%.2f (target > 0.85)".format(photo.identityScore))
                            InfoRow("Render time", "${photo.elapsedMs / 1000} s")
                            InfoRow("Colour pipeline", "LAB + regional histogram + Poisson")
                            if (photo.identityScore in 0.01f..0.85f) {
                                Spacer(Modifier.height(6.dp))
                                StudioBanner(
                                    text = "Identity score %.2f is below the 0.85 target: use a sharper, more frontal source " +
                                        "photo, or raise the enhancer strength so fewer details are regenerated.".format(photo.identityScore),
                                    isWarning = true,
                                )
                            }
                        }
                        else -> InfoRow("Status", "Saved")
                    }
                }
            }

            val warnings = photo?.warnings ?: video?.warnings ?: emptyList()
            if (warnings.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                warnings.take(3).forEach {
                    StudioBanner(text = it, isWarning = true)
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ---- save / share -------------------------------------------------------------
            Spacer(Modifier.height(16.dp))
            if (state.saving) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = SwapColors.Accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Saving to DCIM/SwapStudio - ${state.saveProgress}%", style = MaterialTheme.typography.bodyMedium, color = SwapColors.TextPrimary)
                }
                Spacer(Modifier.height(8.dp))
                StudioProgressBar(progress = state.saveProgress / 100f)
            } else if (state.isSaved) {
                StudioSecondaryButton(
                    text = "Saved - tap to open",
                    onClick = { viewModel.openIntent()?.let { runCatching { context.startActivity(it) } } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    state.savedPath ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = SwapColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                StudioPrimaryButton(
                    text = "Download to phone",
                    onClick = { viewModel.saveToGallery(png = photo != null && state.exportPng) },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null, tint = Color(0xFF00120E), modifier = Modifier.size(18.dp)) },
                )
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                StudioBanner(text = it, isWarning = true)
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudioSecondaryButton(
                    text = "Share",
                    onClick = {
                        viewModel.shareIntent()?.let { intent ->
                            runCatching {
                                context.startActivity(Intent.createChooser(intent, "Share to WhatsApp, TikTok, Instagram"))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                StudioSecondaryButton(
                    text = "Gallery",
                    onClick = { viewModel.openIntent()?.let { runCatching { context.startActivity(it) } } },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = SwapColors.TextDisabled, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "WhatsApp, TikTok and Instagram all accept the export from the share sheet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.sp),
                    color = SwapColors.TextDisabled,
                )
            }

            Spacer(Modifier.height(12.dp))
            StudioSecondaryButton(text = "Back to home", onClick = onHome, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.release() }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SwapColors.TextSecondary, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = SwapColors.TextPrimary)
    }
}

/**
 * ExoPlayer preview of the finished MP4.
 *
 * The player is created and released with the composable, so navigating away never leaks a decoder (which
 * on Android is a hard limit: too many leaked codecs and the next render fails with "resource
 * unavailable").
 */
@Composable
private fun VideoPreview(video: ResultHolder.VideoResult, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(video.videoFile.toURI().toString()))
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(modifier = modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        StudioBadge(
            text = "AI Generated",
            color = SwapColors.Warning,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
        )
        // A poster (first frame) is shown underneath the player until the decoder is ready.
        video.thumbnail?.let { poster ->
            Image(
                bitmap = poster.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                alpha = 0.0f,
            )
        }
    }
}
