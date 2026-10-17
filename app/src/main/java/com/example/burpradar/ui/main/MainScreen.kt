package com.example.burpradar.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation3.runtime.NavKey
import com.example.burpradar.db.BurpEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = view.findViewTreeLifecycleOwner()

    var activeTab by remember { mutableIntStateOf(0) }
    var showPlayerDialogFile by remember { mutableStateOf<File?>(null) }

    // Keep screen awake while charging/running
    LaunchedEffect(uiState.isRunning) {
        view.keepScreenOn = uiState.isRunning
    }

    // Permission check launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false

        if (recordAudioGranted && cameraGranted) {
            if (lifecycleOwner != null) {
                viewModel.startRadar(lifecycleOwner, null)
            }
        } else {
            Toast.makeText(
                context,
                "Camera & Microphone permissions are required to run BurpRadar",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Bind camera preview if we are running and preview is enabled
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    LaunchedEffect(uiState.isRunning, uiState.previewEnabled, previewViewRef) {
        if (uiState.isRunning && lifecycleOwner != null) {
            viewModel.bindCamera(lifecycleOwner, previewViewRef)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Camera, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") }
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") }
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (activeTab) {
                0 -> DashboardTab(
                    uiState = uiState,
                    onStartClick = {
                        val recordPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)

                        if (recordPermission == PackageManager.PERMISSION_GRANTED && cameraPermission == PackageManager.PERMISSION_GRANTED) {
                            if (lifecycleOwner != null) {
                                viewModel.startRadar(lifecycleOwner, previewViewRef)
                            }
                        } else {
                            permissionsLauncher.launch(
                                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                            )
                        }
                    },
                    onStopClick = { viewModel.stopRadar() },
                    onPreviewBound = { pv -> previewViewRef = pv },
                    onFlipCameraClick = {
                        if (lifecycleOwner != null) {
                            viewModel.toggleCamera(lifecycleOwner, previewViewRef)
                        }
                    }
                )
                1 -> HistoryTab(
                    events = uiState.eventsList,
                    onPlayClick = { file -> showPlayerDialogFile = file },
                    onDeleteClick = { event -> viewModel.deleteEvent(event) },
                    onClearAllClick = { viewModel.clearAllHistory() }
                )
                2 -> SettingsTab(
                    confidenceThreshold = uiState.confidenceThreshold,
                    onConfidenceChanged = { viewModel.setConfidenceThreshold(it) },
                    clipDurationMs = uiState.clipDurationMs,
                    onDurationChanged = { viewModel.setClipDuration(it) },
                    maxClips = uiState.maxClips,
                    onMaxClipsChanged = { viewModel.setMaxClips(it) },
                    previewEnabled = uiState.previewEnabled,
                    onPreviewToggled = { enabled ->
                        if (lifecycleOwner != null) {
                            viewModel.setPreviewEnabled(enabled, lifecycleOwner, previewViewRef)
                        }
                    }
                )
            }

            // Playback dialog overlay
            showPlayerDialogFile?.let { file ->
                VideoPlayerDialog(
                    videoFile = file,
                    onDismissRequest = { showPlayerDialogFile = null }
                )
            }
        }
    }
}

@Composable
fun DashboardTab(
    uiState: RadarUiState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPreviewBound: (PreviewView) -> Unit,
    onFlipCameraClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // App Title
        Text(
            text = "BURPRADAR SENTINEL",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp
        )

        // Main Counter Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "SESSION EVENTS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "${uiState.sessionCount}",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = 80.sp,
                        fontWeight = FontWeight.Black
                    ),
                    color = if (uiState.isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Total All-Time: ${uiState.allTimeCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Status Card
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (uiState.isRecording) {
                        Brush.horizontalGradient(listOf(Color(0xFFE53935), Color(0xFFD81B60)))
                    } else if (uiState.isRunning) {
                        Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary))
                    } else {
                        Brush.horizontalGradient(listOf(Color(0xFF424242), Color(0xFF616161)))
                    }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (uiState.isRecording) Icons.Default.Videocam else if (uiState.isRunning) Icons.Default.Hearing else Icons.Default.PowerSettingsNew,
                contentDescription = "Status Icon",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = uiState.detectorStatus.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Camera Preview Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isRunning && uiState.previewEnabled) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                onPreviewBound(this)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    // Overlay Flip Camera Button in Top-Right
                    IconButton(
                        onClick = onFlipCameraClick,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Flip Camera Source",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = if (uiState.isRunning) Icons.Default.VisibilityOff else Icons.Default.CameraAlt,
                        contentDescription = "Camera Info",
                        tint = Color.LightGray,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = if (uiState.isRunning) "Camera Processing in Background\n(Preview Disabled for OLED Protection)" else "Sentinel Disarmed",
                        textAlign = TextAlign.Center,
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Start/Stop Controls
        Button(
            onClick = {
                if (uiState.isRunning) {
                    onStopClick()
                } else {
                    onStartClick()
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (uiState.isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = if (uiState.isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = "Control Button"
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (uiState.isRunning) "STOP RADAR SENTINEL" else "START RADAR SENTINEL",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
fun HistoryTab(
    events: List<BurpEvent>,
    onPlayClick: (File) -> Unit,
    onDeleteClick: (BurpEvent) -> Unit,
    onClearAllClick: () -> Unit
) {
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LOGGED BURPS & BELCHES",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            if (events.isNotEmpty()) {
                IconButton(onClick = onClearAllClick) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear All Logs",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No events recorded yet. Start scanning!",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(events, key = { it.id }) { event ->
                    val file = File(event.videoPath)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (file.exists()) {
                                    onPlayClick(file)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = sdf.format(Date(event.timestamp)),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Text(
                                        text = "Match Score: ${(event.confidence * 100).toInt()}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Duration: ${event.durationMs / 1000}s",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                            IconButton(onClick = { onDeleteClick(event) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete event",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    confidenceThreshold: Float,
    onConfidenceChanged: (Float) -> Unit,
    clipDurationMs: Long,
    onDurationChanged: (Long) -> Unit,
    maxClips: Int,
    onMaxClipsChanged: (Int) -> Unit,
    previewEnabled: Boolean,
    onPreviewToggled: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            text = "CALIBRATION & PREFERENCES",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        // Confidence Threshold
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "AI Confidence Sensitivity",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${(confidenceThreshold * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Lower levels might capture false positives. Higher values require distinct belching sounds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = confidenceThreshold,
                onValueChange = onConfidenceChanged,
                valueRange = 0.10f..0.90f,
                steps = 15
            )
        }

        // Recording Duration
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Evidence Clip Length",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${clipDurationMs / 1000} seconds",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "The duration of the video recorded once a burp is registered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = (clipDurationMs / 1000).toFloat(),
                onValueChange = { onDurationChanged((it.toLong() * 1000)) },
                valueRange = 1f..10f,
                steps = 8
            )
        }

        // Max Clips Limit
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Max Historical Clips",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "$maxClips clips",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Prunes the oldest videos from disk once this limit is reached to save storage.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = maxClips.toFloat(),
                onValueChange = { onMaxClipsChanged(it.toInt()) },
                valueRange = 10f..1000f,
                steps = 98
            )
        }

        // Camera Preview Toggle
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Enable Live Camera Preview",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Turn off to protect OLED screens from burn-in and minimize device heat during 24h continuous operation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = previewEnabled,
                    onCheckedChange = onPreviewToggled
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerDialog(
    videoFile: File,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(videoFile) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoFile.absolutePath))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(videoFile) {
        onDispose {
            exoPlayer.release()
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black
            )
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
