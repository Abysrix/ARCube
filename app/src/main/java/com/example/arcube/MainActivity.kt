package com.example.arcube

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import io.github.sceneview.RenderQuality
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private const val CUBE_SIZE_METERS = 0.15f

/** Surface area (m²) that must be mapped before Place mode unlocks. */
private const val MIN_SCAN_AREA_M2 = 0.10f

/** Surface area (m²) that counts as a "complete" scan. */
private const val TARGET_SCAN_AREA_M2 = 1.5f

/** How often (ms) the scan statistics are recomputed. */
private const val SCAN_STATS_INTERVAL_MS = 250L

private val AccentBlue = Color(0xFF2196F3)
private val ReadyGreen = Color(0xFF4CAF50)
private val WarnAmber = Color(0xFFFFB300)
private val PointCloudCyan = Color(0xFF00E5FF)

/** The two interaction modes for the AR experience. */
private enum class Mode { SCAN, USE }

/**
 * Depth-mesh density presets for the LiDAR-style scan.
 *
 * Lower stride = denser mesh = heavier per-frame rebuild. [OFF] skips the mesh entirely (depth
 * placement keeps working) as a fallback when the mesh costs too much; its stride/refresh values
 * are unused.
 */
private enum class MeshQuality(val label: String, val stride: Int, val refreshIntervalMs: Long) {
    HIGH("High", 4, 150L),
    BALANCED("Balanced", 6, 300L),
    LOW("Low", 8, 500L),
    OFF("Off", 6, 300L);

    val meshEnabled: Boolean get() = this != OFF
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                ARCubeApp()
            }
        }
    }
}

@Composable
fun ARCubeApp() {
    var hasCameraPermission by remember { mutableStateOf(value = false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasCameraPermission = granted
        }

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        hasCameraPermission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (hasCameraPermission) {
        ARCubeScreen()
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Camera permission is required for AR.",
                color = Color.White,
            )
        }
    }
}

@Composable
fun ARCubeScreen() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val haptics = LocalHapticFeedback.current

    // Shared materials — a solid cube material, an unlit colour for the tracking cloud,
    // and a translucent colour for the LiDAR-style depth scan mesh.
    val cubeMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(
            colorOf(
                Color(0.15f, 0.45f, 1f, 1f),
            ),
        )
    }
    val pointCloudMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(PointCloudCyan)
    }
    val depthMeshMaterial = remember(materialLoader) {
        materialLoader.createUnlitColorInstance(Color(0x9900E5FF))
    }

    // ---- Session / UI state ----------------------------------------------------------------

    var mode by remember { mutableStateOf(Mode.SCAN) }

    val latestFrame = remember { AtomicReference<Frame?>(null) }
    val reticleHit = remember { AtomicReference<HitResult?>(null) }
    val lastScanStatsAt = remember { AtomicLong(0L) }
    val depthSupportChecked = remember { AtomicBoolean(false) }

    val anchors = remember { mutableStateListOf<Anchor>() }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var isTracking by remember { mutableStateOf(value = false) }
    var trackingFailure by remember { mutableStateOf<TrackingFailureReason?>(null) }
    var isSurfaceUnderReticle by remember { mutableStateOf(value = false) }

    // True once the session reports camera-only depth (motion stereo) is available.
    var depthSupported by remember { mutableStateOf(value = false) }

    // Depth-mesh density, plus the mesh on/off fallback for low-end devices.
    var meshQuality by remember { mutableStateOf(MeshQuality.BALANCED) }
    var meshSettingsOpen by remember { mutableStateOf(value = false) }

    // Scan-mode statistics (updated at ~4 Hz — never per frame).
    var scannedPlaneCount by remember { mutableIntStateOf(0) }
    var scannedArea by remember { mutableFloatStateOf(0f) }

    val scanProgress = (scannedArea / TARGET_SCAN_AREA_M2).coerceIn(0f, 1f)
    val scanReady = (scannedPlaneCount > 0) && (scannedArea >= MIN_SCAN_AREA_M2)

    // The depth mesh is only rendered when the device supports depth and the user hasn't
    // switched it off for performance.
    val meshLive = depthSupported && meshQuality.meshEnabled

    // Leaving Place mode invalidates the reticle-derived ready flag.
    LaunchedEffect(mode) {
        if (mode != Mode.USE) isSurfaceUnderReticle = false
    }

    // Release native anchors when the screen leaves composition.
    DisposableEffect(Unit) {
        onDispose {
            anchors.forEach { it.detach() }
            anchors.clear()
        }
    }

    // Pulsing ring for the scanning indicator.
    val infiniteTransition = rememberInfiniteTransition(label = "scanPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it },
    ) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            planeRenderer = true,

            // Battery/thermal-friendly preset tuned for overlays over a live camera feed.
            renderQuality = RenderQuality.Performance,

            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL,

            sessionConfiguration = { session, config ->
                config.focusMode = Config.FocusMode.AUTO
                config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                // Camera-only depth (motion stereo) is the LiDAR-style engine — enabled only on
                // devices that support it; the rest fall back to planes + point cloud.
                config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    Config.DepthMode.AUTOMATIC
                } else {
                    Config.DepthMode.DISABLED
                }
            },

            onTrackingFailureChanged = { reason ->
                trackingFailure = reason
            },

            onSessionUpdated = { session, frame ->
                latestFrame.set(frame)

                // Probe depth support once per session (device capability can't change).
                if (depthSupportChecked.compareAndSet(false, true)) {
                    val canUseDepth = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                    if (canUseDepth != depthSupported) depthSupported = canUseDepth
                }

                val frameTracking = frame.camera.trackingState == TrackingState.TRACKING
                if (frameTracking != isTracking) isTracking = frameTracking

                // Recompute scan statistics at a fixed, low rate (never per frame).
                val now = SystemClock.elapsedRealtime()
                if ((now - lastScanStatsAt.get()) >= SCAN_STATS_INTERVAL_MS) {
                    lastScanStatsAt.set(now)

                    val trackedPlanes = session
                        .getAllTrackables(Plane::class.java)
                        .filter { it.trackingState == TrackingState.TRACKING }

                    val count = trackedPlanes.size
                    val area = trackedPlanes
                        .sumOf { (it.extentX * it.extentZ).toDouble() }
                        .toFloat()

                    if (count != scannedPlaneCount) scannedPlaneCount = count
                    if (kotlin.math.abs(area - scannedArea) > 0.02f) scannedArea = area
                }
            },

            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { _: MotionEvent, _ ->
                    // Cubes are only added while in Place mode, at the reticle position.
                    if (mode == Mode.USE) {
                        reticleHit.get()?.createAnchor()?.let { newAnchor ->
                            anchors.add(newAnchor)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
            ),
        ) {
            // LiDAR-style depth mesh — kept visible in BOTH modes so you can see the scanned
            // geometry while placing. Changing density rebinds the live node (no recreation).
            if (meshLive) {
                val depthMesh = rememberDepthMesh(
                    refreshIntervalMs = meshQuality.refreshIntervalMs,
                    stride = meshQuality.stride,
                    materialInstance = depthMeshMaterial,
                    builder = {
                        castShadows(false)
                        receiveShadows(false)
                    },
                )
                DepthMeshNode(depthMesh)
            }

            // Fallback scan visualisation (no depth-capable device, or the mesh switched off):
            // the feature-point cloud, scan mode only. Never both passes at once.
            if ((mode == Mode.SCAN) && !meshLive) {
                val pointCloud = rememberPointCloud(
                    materialInstance = pointCloudMaterial,
                    refreshIntervalMs = 80L,
                )
                PointCloudNode(pointCloud)
            }

            anchors.forEach { anchor ->
                key(anchor) {
                    AnchorNode(anchor = anchor) {
                        CubeNode(
                            size = Size(
                                x = CUBE_SIZE_METERS,
                                y = CUBE_SIZE_METERS,
                                z = CUBE_SIZE_METERS,
                            ),
                            materialInstance = cubeMaterial,
                            position = Position(y = CUBE_SIZE_METERS / 2f),
                        )
                    }
                }
            }

            // Place mode: a smoothed 3D cursor that snaps to the surface under the centre.
            if (mode == Mode.USE) {
                PlacementReticle(
                    xPx = viewSize.width / 2f,
                    yPx = viewSize.height / 2f,
                    // With depth available, place on any real geometry (LiDAR-like);
                    // otherwise restrict placement to detected planes.
                    snapToPlane = !depthSupported,
                    depthPoint = depthSupported,
                    onHitResultChanged = { hit ->
                        reticleHit.set(hit)
                        val ready = hit != null
                        if (ready != isSurfaceUnderReticle) isSurfaceUnderReticle = ready
                    },
                )
            }
        }

        // Centre scanning ring — shown only while mapping the space.
        if (mode == Mode.SCAN) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                val ready = scanReady && isTracking
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .scale(if (ready) 1f else pulseScale)
                        .border(
                            width = 2.dp,
                            color = if (ready) ReadyGreen else AccentBlue,
                            shape = CircleShape,
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (ready) ReadyGreen else Color.White,
                            shape = CircleShape,
                        ),
                )
            }
        }

        StatusBanner(
            mode = mode,
            isTracking = isTracking,
            isSurfaceReady = isSurfaceUnderReticle,
            scanReady = scanReady,
            depthSupported = depthSupported,
            trackingFailure = trackingFailure,
            pulseScale = pulseScale,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
        )

        // Bottom area: scan progress (scan mode) or placement controls (use mode) + mode toggle.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mode == Mode.SCAN) {
                ScanProgressCard(
                    progress = scanProgress,
                    planeCount = scannedPlaneCount,
                    scanReady = scanReady,
                    depthActive = depthSupported,
                    meshOn = meshLive,
                )
            } else {
                PlacementControls(
                    cubeCount = anchors.size,
                    onUndo = {
                        anchors.removeLastOrNull()?.let {
                            it.detach()
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onClearAll = {
                        anchors.forEach { it.detach() }
                        anchors.clear()
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mesh density / low-cost fallback — only meaningful on depth-capable devices.
            if (depthSupported) {
                MeshQualityPanel(
                    quality = meshQuality,
                    expanded = meshSettingsOpen,
                    onToggleExpanded = {
                        meshSettingsOpen = !meshSettingsOpen
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onSelect = { selected ->
                        meshQuality = selected
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            ModeToggle(
                mode = mode,
                useEnabled = scanReady,
                onSelect = { selected ->
                    mode = selected
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            )
        }
    }
}

private data class ScanStatus(
    val title: String,
    val subtitle: String,
    val color: Color,
    val isReady: Boolean,
)

private fun buildStatus(
    mode: Mode,
    isTracking: Boolean,
    isSurfaceReady: Boolean,
    scanReady: Boolean,
    trackingFailure: TrackingFailureReason?,
    depthSupported: Boolean,
): ScanStatus = when {
    trackingFailure == TrackingFailureReason.EXCESSIVE_MOTION ->
        ScanStatus("Hold Steady", "Move the phone a little slower", WarnAmber, isReady = false)

    trackingFailure == TrackingFailureReason.INSUFFICIENT_LIGHT ->
        ScanStatus("Too Dark", "Move somewhere with more light", WarnAmber, isReady = false)

    trackingFailure == TrackingFailureReason.INSUFFICIENT_FEATURES ->
        ScanStatus("Not Enough Detail", "Point at a textured floor or table", WarnAmber, isReady = false)

    trackingFailure != null ->
        ScanStatus("Re-acquiring…", "Slowly move the phone to re-scan", WarnAmber, isReady = false)

    !isTracking ->
        ScanStatus("Starting Camera…", "Point at a flat surface", AccentBlue, isReady = false)

    (mode == Mode.SCAN) && scanReady ->
        ScanStatus("Area Mapped", "Tap Place to start adding cubes", ReadyGreen, isReady = true)

    mode == Mode.SCAN ->
        ScanStatus(
            "Scanning Area…",
            if (depthSupported) "Move slowly to build the depth map" else "Slowly pan across the floor or table",
            AccentBlue,
            isReady = false,
        )

    isSurfaceReady ->
        ScanStatus("Surface Ready", "Tap to place a cube", ReadyGreen, isReady = true)

    else ->
        ScanStatus("Aim at a Surface", "Point the reticle at the floor or table", AccentBlue, isReady = false)
}

@Composable
private fun StatusBanner(
    mode: Mode,
    isTracking: Boolean,
    isSurfaceReady: Boolean,
    scanReady: Boolean,
    trackingFailure: TrackingFailureReason?,
    depthSupported: Boolean,
    pulseScale: Float,
    modifier: Modifier = Modifier,
) {
    val status = remember(
        mode,
        isTracking,
        isSurfaceReady,
        scanReady,
        trackingFailure,
        depthSupported,
    ) {
        buildStatus(mode, isTracking, isSurfaceReady, scanReady, trackingFailure, depthSupported)
    }

    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.75f),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (status.isReady) {
                CheckCircleIcon(
                    modifier = Modifier.size(24.dp),
                    color = status.color,
                )
            } else {
                SearchIcon(
                    modifier = Modifier
                        .size(24.dp)
                        .scale(pulseScale),
                    color = status.color,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AnimatedContent(
                targetState = status,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "status",
            ) { (title, subtitle) ->
                Column {
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanProgressCard(
    progress: Float,
    planeCount: Int,
    scanReady: Boolean,
    depthActive: Boolean,
    meshOn: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .width(260.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (scanReady) "Scan complete" else "Scanning…",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = if (scanReady) ReadyGreen else AccentBlue,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Custom progress bar (no experimental Material API surface).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .background(if (scanReady) ReadyGreen else AccentBlue),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = buildString {
                    append("$planeCount surface${if (planeCount == 1) "" else "s"} mapped")
                    if (depthActive) append(if (meshOn) " · depth mesh on" else " · depth mesh off")
                },
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun PlacementControls(
    cubeCount: Int,
    onUndo: () -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Cubes: $cubeCount",
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )

            if (cubeCount > 0) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Undo",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onUndo)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Text(
                    text = "Clear All",
                    color = Color(0xFFFF5252),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable(onClick = onClearAll)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeToggle(
    mode: Mode,
    useEnabled: Boolean,
    onSelect: (Mode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.8f),
        shape = RoundedCornerShape(50),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            Segment(
                label = "Scan",
                selected = mode == Mode.SCAN,
                enabled = true,
                accent = AccentBlue,
            ) { onSelect(Mode.SCAN) }
            Segment(
                label = "Place",
                selected = mode == Mode.USE,
                enabled = useEnabled,
                accent = ReadyGreen,
            ) { onSelect(Mode.USE) }
        }
    }
}

@Composable
private fun MeshQualityPanel(
    quality: MeshQuality,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: (MeshQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (expanded) {
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = "Depth mesh density",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MeshQuality.entries.forEach { candidate ->
                            QualityChip(
                                label = candidate.label,
                                selected = candidate == quality,
                            ) { onSelect(candidate) }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (quality.meshEnabled) {
                            "Lower density keeps the camera smoother"
                        } else {
                            "Mesh off — lowest cost, placement still works"
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Surface(
            color = Color.Black.copy(alpha = 0.8f),
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = "Depth mesh: ${quality.label}",
                color = PointCloudCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AccentBlue else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun Segment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val selectedColor = if (enabled) accent else accent.copy(alpha = 0.35f)
    val labelColor = when {
        selected -> Color.White
        !enabled -> Color.White.copy(alpha = 0.35f)
        else -> Color.White.copy(alpha = 0.8f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) selectedColor else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = labelColor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun SearchIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width * 0.42f, size.height * 0.42f)
        val radius = size.width * 0.28f
        drawCircle(
            color = color,
            center = center,
            radius = radius,
            style = Stroke(width = 2.5.dp.toPx()),
        )
        drawLine(
            color = color,
            start = Offset(center.x + (radius * 0.7f), center.y + (radius * 0.7f)),
            end = Offset(size.width * 0.85f, size.height * 0.85f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CheckCircleIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.width / 2
        drawCircle(color = color, center = center, radius = radius)

        val path = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.5f)
            lineTo(size.width * 0.45f, size.height * 0.68f)
            lineTo(size.width * 0.72f, size.height * 0.35f)
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}
