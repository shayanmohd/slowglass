package com.mohdshayan.slowglass.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.slowglass.capture.CamStatus
import com.mohdshayan.slowglass.capture.CaptureConnection
import com.mohdshayan.slowglass.capture.EngineState
import com.mohdshayan.slowglass.capture.Phase
import com.mohdshayan.slowglass.capture.SaveOutcome
import com.mohdshayan.slowglass.capture.SavedResult
import com.mohdshayan.slowglass.core.stack.LightningSensitivity
import com.mohdshayan.slowglass.core.stack.NoiseSmoothing
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.stack.StarSensitivity
import com.mohdshayan.slowglass.core.timer.BulbPreset
import com.mohdshayan.slowglass.core.timer.DimTimer
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.core.timer.TripodDelay
import com.mohdshayan.slowglass.data.prefs.Settings as AppSettings
import com.mohdshayan.slowglass.ui.components.ButtonShape
import com.mohdshayan.slowglass.ui.components.ChoiceChip
import com.mohdshayan.slowglass.ui.components.PhotoLoad
import com.mohdshayan.slowglass.ui.components.PhotoView
import com.mohdshayan.slowglass.ui.components.RisingSheet
import com.mohdshayan.slowglass.ui.components.SegmentedToggle
import com.mohdshayan.slowglass.ui.components.rememberPhoto
import com.mohdshayan.slowglass.ui.components.sharePhoto
import com.mohdshayan.slowglass.ui.theme.DurationChipStyle
import com.mohdshayan.slowglass.ui.theme.LocalReducedMotion
import com.mohdshayan.slowglass.ui.theme.ModeNameStyle
import com.mohdshayan.slowglass.ui.theme.RadiusMd
import com.mohdshayan.slowglass.ui.theme.RadiusSm
import com.mohdshayan.slowglass.ui.theme.ViewfinderScrim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Sheet { NONE, DURATION, TUNE, NOTIFY }

@Composable
fun CaptureScreen(
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onFirstRunCheck: () -> Unit,
    vm: CaptureViewModel = viewModel(),
) {
    val context = LocalContext.current
    val engine by vm.engine.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val service by CaptureConnection.service.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasCamera(context)) }
    // Decided in the result callback: a second denial changes no other state, so reading the
    // rationale flag during composition left "Allow camera" on screen doing nothing.
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val activity = context as? Activity
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        permanentlyDenied = !ok && activity != null &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        if (ok) vm.onPermissionGranted()
    }

    // The camera is open only while this screen is on top.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, service) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> {
                    granted = hasCamera(context)
                    vm.setVisible(true)
                }
                Lifecycle.Event.ON_STOP -> vm.setVisible(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.setVisible(true)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            vm.setVisible(false)
        }
    }

    // First launch: once the camera is allowed, run the camera check before the first photo.
    // Offered once per visit: Back from an unfinished or failed check returns to the camera instead
    // of reopening the check, which stays one tap away in Settings.
    var checkOffered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(granted) {
        // Read the stored flag, not the cached flow value, so a finished check never reopens.
        if (granted && !checkOffered && !vm.cameraCheckDone()) {
            checkOffered = true
            onFirstRunCheck()
        }
    }

    val s = settings ?: AppSettings()
    val phase = engine.phase
    val running = phase is Phase.Running
    val busy = phase !is Phase.Idle

    // Volume keys are a shutter while this screen is on top.
    DisposableEffect(s.volumeKeysShutter) {
        CaptureConnection.volumeKeysActive = s.volumeKeysShutter
        onDispose { CaptureConnection.volumeKeysActive = false }
    }

    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.markNotificationAsked()
        vm.shutter()
    }
    val onShutter: () -> Unit = {
        val long = s.bulbPresetMs == BulbPreset.UNLIMITED || s.bulbPresetMs > 30_000
        if (phase == Phase.Idle && long && needsNotificationAsk(context) && !s.notificationAsked) {
            sheet = Sheet.NOTIFY
        } else {
            vm.shutter()
        }
    }
    LaunchedEffect(Unit) {
        CaptureConnection.volumePresses.collect { onShutter() }
    }

    // Keep the screen on during a session and dim it to minimum after 10 s without a touch.
    val dim = remember { DimTimer() }
    var dimmed by remember { mutableStateOf(false) }
    LaunchedEffect(running, s.dimDuringSession) {
        dim.touch(System.currentTimeMillis())
        dimmed = false
        while (running) {
            delay(500)
            dimmed = dim.shouldDim(System.currentTimeMillis(), running, s.dimDuringSession)
        }
    }
    DisposableEffect(busy, dimmed) {
        val w = activity?.window
        if (w != null) {
            if (busy) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = w.attributes
            lp.screenBrightness = if (dimmed && busy) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        }
        onDispose {
            activity?.window?.let { win ->
                win.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = win.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                win.attributes = lp
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    dim.touch(System.currentTimeMillis())
                    dimmed = false
                }
            },
    ) {
        if (!granted) {
            PermissionState(
                permanentlyDenied = permanentlyDenied,
                onAllow = { permission.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                },
                onLibrary = onOpenLibrary,
                onAppSettings = onOpenSettings,
            )
            return@Box
        }

        Viewfinder(engine = engine, onTapFocus = { u, v -> vm.focusAt(u, v) })
        // The status bar sits on the Pane scrim so its icons stay readable over a bright scene.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(ViewfinderScrim))

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            var brightnessOpen by rememberSaveable { mutableStateOf(false) }
            TopControls(
                engine = engine,
                busy = busy,
                brightnessOpen = brightnessOpen,
                onBrightness = { brightnessOpen = !brightnessOpen },
                onTune = { sheet = Sheet.TUNE },
                onSettings = onOpenSettings,
                onZoom = vm::setZoom,
                onEv = vm::setEv,
                modifier = Modifier.align(Alignment.TopCenter),
                endInset = if (wide) 240.dp else 0.dp,
            )
            CameraProblem(engine.camera, onRetry = vm::retryCamera, modifier = Modifier.align(Alignment.Center))
            BottomControls(
                engine = engine,
                settings = s,
                latestUri = latest?.stackUri,
                wide = wide,
                onMode = vm::setMode,
                onDuration = { sheet = Sheet.DURATION },
                onShutter = onShutter,
                onLibrary = onOpenLibrary,
                onNoticeShown = vm::clearNotice,
                modifier = Modifier.align(if (wide) Alignment.CenterEnd else Alignment.BottomCenter),
            )
        }

        // Duration, mode settings and the notification reason.
        RisingSheet(visible = sheet == Sheet.DURATION, onDismiss = { sheet = Sheet.NONE }) {
            DurationSheet(s.bulbPresetMs) { vm.setPreset(it); sheet = Sheet.NONE }
        }
        RisingSheet(visible = sheet == Sheet.TUNE, onDismiss = { sheet = Sheet.NONE }) {
            TuneSheet(engine.mode, s, vm)
        }
        RisingSheet(visible = sheet == Sheet.NOTIFY, onDismiss = { sheet = Sheet.NONE }) {
            Text("Stop from the notification", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(
                "Slowglass shows the time and a Stop button in a notification while a long exposure runs.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(shape = ButtonShape, onClick = { sheet = Sheet.NONE; vm.markNotificationAsked(); vm.shutter() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Not now")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    shape = ButtonShape,
                    onClick = {
                        sheet = Sheet.NONE
                        if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.shutter()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Allow notifications") }
            }
        }

        SavedSheet(engine.outcome, vm, onOpenSession)
    }
}

private fun hasCamera(context: android.content.Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun needsNotificationAsk(context: android.content.Context) =
    Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionState(
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onLibrary: () -> Unit,
    onAppSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        EngravedMark()
        Spacer(Modifier.height(28.dp))
        Text(
            "Slowglass needs the camera to make long exposures.",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (permanentlyDenied) {
                "Camera access is off for Slowglass. Turn it on in the phone's settings, then come back."
            } else {
                "Frames are stacked on this phone and never leave it."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(shape = ButtonShape, onClick = if (permanentlyDenied) onOpenSettings else onAllow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (permanentlyDenied) "Open settings" else "Allow camera")
        }
        // Without the camera, earlier photos, export and import still work.
        Spacer(Modifier.height(16.dp))
        Row {
            TextButton(shape = ButtonShape, onClick = onLibrary, modifier = Modifier.heightIn(min = 48.dp)) { Text("Library") }
            Spacer(Modifier.width(8.dp))
            TextButton(shape = ButtonShape, onClick = onAppSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Slowglass settings") }
        }
    }
}

/** The idle ring and clock, drawn once, as the mark on the permission screen. */
@Composable
private fun EngravedMark() {
    Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
        ShutterRing(running = true, elapsedMs = 24_000, enabled = false, onClick = {}, ringSize = 72.dp, decorative = true)
        Spacer(Modifier.width(16.dp))
        EngravedClock("0:24", MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun Viewfinder(engine: EngineState, onTapFocus: (Float, Float) -> Unit) {
    val service by CaptureConnection.service.collectAsStateWithLifecycle()
    var focus by remember { mutableStateOf<Offset?>(null) }
    var focusStamp by remember { mutableLongStateOf(0L) }
    val reduced = LocalReducedMotion.current
    Box(Modifier.fillMaxSize()) {
        val svc = service
        if (svc != null) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) = Unit
                            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                                svc.attachViewfinder(h.surface, width, height)
                            }
                            override fun surfaceDestroyed(h: SurfaceHolder) = svc.detachViewfinder()
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Viewfinder. Tap to focus." }
                    .pointerInput(engine.outputWidth, engine.streamWidth) {
                        detectTapGestures { p ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val (u, v) = viewToImage(p.x / w, p.y / h, w / h, imageAspect(engine))
                            focus = p
                            focusStamp = System.currentTimeMillis()
                            onTapFocus(u, v)
                        }
                    },
            )
        }
        // Until the first frame arrives the viewfinder is the Dusk ground, not a black hole.
        if (!engine.streaming) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        val f = focus
        val accent = MaterialTheme.colorScheme.primary
        AnimatedVisibility(
            visible = f != null,
            enter = if (reduced) fadeIn(snap()) else fadeIn(tween(120)),
            exit = if (reduced) fadeOut(snap()) else fadeOut(tween(300)),
        ) {
            val density = LocalDensity.current
            val half = with(density) { 32.dp.roundToPx() }
            val at = f ?: Offset.Zero
            Canvas(
                Modifier
                    .offset { IntOffset(at.x.roundToInt() - half, at.y.roundToInt() - half) }
                    .size(64.dp),
            ) {
                val l = size.width
                val arm = l * 0.28f
                val sw = 2.dp.toPx()
                val st = Stroke(width = sw)
                // Four corner brackets, the focus mark.
                listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(l, 0f) to Offset(-1f, 1f), Offset(0f, l) to Offset(1f, -1f), Offset(l, l) to Offset(-1f, -1f))
                    .forEach { (c, d) ->
                        drawLine(accent, c, Offset(c.x + d.x * arm, c.y), sw)
                        drawLine(accent, c, Offset(c.x, c.y + d.y * arm), sw)
                    }
                drawCircle(accent, radius = 2.dp.toPx(), style = st)
            }
        }
        LaunchedEffect(focusStamp) {
            if (focusStamp == 0L) return@LaunchedEffect
            delay(1200)
            focus = null
        }
    }
}

private fun imageAspect(e: EngineState): Float {
    val w = if (e.outputWidth > 0) e.outputWidth else 1080
    val h = if (e.outputHeight > 0) e.outputHeight else 1920
    return w.toFloat() / h
}

/** Maps a tap in a centre-cropped view to 0..1 image coordinates from the top left. */
internal fun viewToImage(x: Float, y: Float, viewAspect: Float, imageAspect: Float): Pair<Float, Float> =
    if (viewAspect > imageAspect) {
        val k = imageAspect / viewAspect
        x to (0.5f + (y - 0.5f) * k)
    } else {
        val k = viewAspect / imageAspect
        (0.5f + (x - 0.5f) * k) to y
    }

@Composable
private fun ScrimCircleButton(description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(ViewfinderScrim)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun TopControls(
    engine: EngineState,
    busy: Boolean,
    brightnessOpen: Boolean,
    onBrightness: () -> Unit,
    onTune: () -> Unit,
    onSettings: () -> Unit,
    onZoom: (Float) -> Unit,
    onEv: (Float) -> Unit,
    modifier: Modifier = Modifier,
    endInset: androidx.compose.ui.unit.Dp,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp + endInset, top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (engine.zoomChips.size > 1) {
                Row(
                    Modifier.clip(CircleShape).background(ViewfinderScrim).padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    engine.zoomChips.forEach { z ->
                        val on = abs(engine.zoom - z) < 0.05f
                        val label = if (z < 1f) String.format(Locale.US, "%.1f", z) else "${z.roundToInt()}x"
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (on) MaterialTheme.colorScheme.primary else ViewfinderScrim.copy(alpha = 0f))
                                .selectable(selected = on, enabled = !busy, role = Role.RadioButton) { onZoom(z) }
                                .semantics { contentDescription = "Zoom $label" },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else ink,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val evLabel = if (engine.ev == 0f) "Brightness" else "Brightness ${evText(engine.ev)}"
            Row(
                Modifier
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(if (brightnessOpen) MaterialTheme.colorScheme.primary else ViewfinderScrim)
                    .clickable(enabled = !busy && engine.evMax > engine.evMin, role = Role.Button, onClick = onBrightness)
                    .semantics { contentDescription = evLabel }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (brightnessOpen) MaterialTheme.colorScheme.onPrimary else ink
                Icon(Icons.Rounded.BrightnessMedium, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                if (engine.ev != 0f) {
                    Spacer(Modifier.width(6.dp))
                    Text(evText(engine.ev), style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
            Spacer(Modifier.width(8.dp))
            ScrimCircleButton("Mode settings and tripod delay", onTune, enabled = !busy) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = ink)
            }
            Spacer(Modifier.width(8.dp))
            ScrimCircleButton("Settings", onSettings, enabled = !busy) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = ink)
            }
        }
        if (brightnessOpen && !busy) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .align(Alignment.End)
                    .clip(CircleShape)
                    .background(ViewfinderScrim)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScrimCircleButton("Darker", { onEv(engine.ev - engine.evStep) }, enabled = engine.ev > engine.evMin) {
                    Icon(Icons.Rounded.Remove, contentDescription = null, tint = ink)
                }
                Text(
                    "${evText(engine.ev)} EV",
                    style = MaterialTheme.typography.labelLarge,
                    color = ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(72.dp),
                )
                ScrimCircleButton("Brighter", { onEv(engine.ev + engine.evStep) }, enabled = engine.ev < engine.evMax) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = ink)
                }
            }
        }
    }
}

private fun evText(ev: Float): String = when {
    ev == 0f -> "0"
    ev > 0f -> "+" + trimFloat(ev)
    else -> "−" + trimFloat(-ev)
}

private fun trimFloat(v: Float): String =
    if (v % 1f == 0f) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

@Composable
private fun CameraProblem(status: CamStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val message = when (status) {
        CamStatus.IN_USE -> "Another app is using the camera. Close it and tap to retry."
        CamStatus.DISABLED -> "The camera is turned off on this phone. Turn it on in quick settings, then tap to retry."
        CamStatus.NO_CAMERA -> "This phone has no back camera that Slowglass can use."
        CamStatus.FAILED -> "The camera stopped responding. Tap to retry."
        else -> null
    } ?: return
    Column(
        modifier
            .padding(24.dp)
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(RadiusMd))
            .background(ViewfinderScrim)
            .clickable(enabled = status != CamStatus.NO_CAMERA, onClick = onRetry)
            .padding(20.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        if (status != CamStatus.NO_CAMERA) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(shape = ButtonShape, onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
        }
    }
}

@Composable
private fun BottomControls(
    engine: EngineState,
    settings: AppSettings,
    latestUri: String?,
    wide: Boolean,
    onMode: (StackMode) -> Unit,
    onDuration: () -> Unit,
    onShutter: () -> Unit,
    onLibrary: () -> Unit,
    onNoticeShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = engine.phase
    val running = phase is Phase.Running
    val busy = phase !is Phase.Idle
    val elapsed = rememberElapsed((phase as? Phase.Running)?.startedAtRealtime)
    val ink = MaterialTheme.colorScheme.onBackground
    val haze = MaterialTheme.colorScheme.onSurfaceVariant

    LaunchedEffect(engine.noticeId) {
        if (engine.notice != null) {
            delay(3500)
            onNoticeShown()
        }
    }
    val status: String? = engine.notice ?: when (phase) {
        is Phase.Countdown -> "Starting in ${phase.secondsLeft}"
        is Phase.Running -> if (engine.mode == StackMode.LIGHTNING) {
            when (engine.strikes) {
                0 -> "Armed. Watching for lightning"
                1 -> "1 strike saved"
                else -> "${engine.strikes} strikes saved"
            }
        } else {
            String.format(Locale.US, "%,d frames stacked", engine.frames)
        }
        Phase.Saving -> "Saving to Pictures/Slowglass"
        Phase.Idle -> null
    }

    val clockText = when {
        running -> ExposureClock.format(elapsed)
        phase is Phase.Countdown -> "0:0${phase.secondsLeft.coerceAtMost(9)}"
        else -> ExposureClock.format(0)
    }
    val clockColor = if (running) ink else haze
    val shutterEnabled = phase != Phase.Saving && (engine.camera == CamStatus.READY || busy)

    val content: @Composable androidx.compose.foundation.layout.ColumnScope.(Boolean) -> Unit = { vertical ->
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (engine.notice != null) MaterialTheme.colorScheme.primary else haze,
                    textAlign = TextAlign.Center,
                )
            } else {
                ModeStrip(engine.mode, onMode, vertical)
            }
        }
        Spacer(Modifier.height(if (vertical) 12.dp else 4.dp))
        EngravedClock(clockText, clockColor, Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        val thumb: @Composable () -> Unit = {
            Thumbnail(latestUri, enabled = !busy, onClick = onLibrary)
        }
        val chip: @Composable () -> Unit = {
            DurationChip(settings.bulbPresetMs, enabled = !busy, onClick = onDuration)
        }
        if (vertical) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                chip()
                Spacer(Modifier.height(16.dp))
                ShutterRing(running = running, elapsedMs = elapsed, enabled = shutterEnabled, onClick = onShutter)
                Spacer(Modifier.height(16.dp))
                thumb()
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { thumb() }
                ShutterRing(running = running, elapsedMs = elapsed, enabled = shutterEnabled, onClick = onShutter)
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { chip() }
            }
        }
    }

    if (wide) {
        Column(
            modifier
                .fillMaxHeight()
                .width(240.dp)
                .background(ViewfinderScrim)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
        ) { content(true) }
    } else {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(ViewfinderScrim)
                .navigationBarsPadding()
                .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp),
        ) { content(false) }
    }
}

@Composable
private fun ModeStrip(mode: StackMode, onMode: (StackMode) -> Unit, vertical: Boolean) {
    val items: @Composable () -> Unit = {
        StackMode.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(RadiusSm))
                    .background(if (on) MaterialTheme.colorScheme.primary else ViewfinderScrim.copy(alpha = 0f))
                    .selectable(selected = on, role = Role.Tab) { onMode(m) }
                    .semantics { contentDescription = m.label }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.shortLabel,
                    style = ModeNameStyle,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
    } else {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { items() }
    }
}

@Composable
private fun Thumbnail(uri: String?, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RadiusMd)
    Box(
        Modifier
            .size(52.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Library" },
        contentAlignment = Alignment.Center,
    ) {
        val photo = if (uri != null) rememberPhoto(uri, 160) else PhotoLoad.Missing
        if (photo is PhotoLoad.Ready) {
            Image(photo.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DurationChip(presetMs: Long, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(RadiusSm)
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 64.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Exposure length, ${BulbPreset.label(presetMs)}" }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(BulbPreset.chip(presetMs), style = DurationChipStyle, color = MaterialTheme.colorScheme.onBackground)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DurationSheet(current: Long, onPick: (Long) -> Unit) {
    Text("Exposure length", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(4.dp))
    Text(
        "Tap the shutter again to stop early. Unlimited runs until you stop it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BulbPreset.presetsMs.forEach { ms ->
            ChoiceChip(BulbPreset.label(ms), selected = ms == current, onClick = { onPick(ms) })
        }
    }
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuneSheet(mode: StackMode, s: AppSettings, vm: CaptureViewModel) {
    Text(mode.label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(16.dp))
    val label: @Composable (String, String) -> Unit = { title, help ->
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
    }
    when (mode) {
        StackMode.TRAILS -> {
            label("Noise smoothing", "Averages 1, 3 or 8 frames before each lighten, so noise does not brighten the sky.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoiseSmoothing.entries.forEach { n -> ChoiceChip(n.label, n == s.noiseSmoothing, { vm.setNoise(n) }) }
            }
        }
        StackMode.WATER -> label("Silky water", "Every frame is averaged, so moving water turns to mist. Longer exposures give smoother water.")
        StackMode.MOTION -> {
            label("Keep lights", "How much bright lamps stay sharp while moving people dissolve.")
            var v by remember(s.keepLights) { mutableFloatStateOf(s.keepLights) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = v, onValueChange = { v = it }, onValueChangeFinished = { vm.setKeepLights(v) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Keep lights" },
                    colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                Text("${(v * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
        StackMode.STARS -> {
            label("Star sensitivity", "Frames averaged per step. More gathers fainter stars; fewer keeps trails finer.")
            var v by remember(s.starSensitivity) { mutableIntStateOf(s.starSensitivity) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = v.toFloat(), onValueChange = { v = it.roundToInt() }, onValueChangeFinished = { vm.setStarSensitivity(v) },
                    valueRange = StarSensitivity.MIN.toFloat()..StarSensitivity.MAX.toFloat(),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Star sensitivity" },
                    colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant),
                )
                Text("$v", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
            }
        }
        StackMode.LIGHTNING -> {
            label("Trigger sensitivity", "High catches faint flashes behind cloud; Low ignores headlights and distant flicker.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LightningSensitivity.entries.forEach { l -> ChoiceChip(l.label, l == s.lightningSensitivity, { vm.setLightning(l) }) }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    label("Tripod delay", "Waits after the tap so the touch does not shake the first frames.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TripodDelay.options.forEach { d -> ChoiceChip(TripodDelay.label(d), d == s.tripodDelayS, { vm.setDelay(d) }) }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SavedSheet(
    outcome: SaveOutcome?,
    vm: CaptureViewModel,
    onOpenSession: (Long) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
    // Keep the last outcome on screen while the sheet slides away.
    var shown by remember { mutableStateOf<SaveOutcome?>(null) }
    if (outcome != null) shown = outcome
    val dismiss: () -> Unit = { vm.doneWithSaved() }
    RisingSheet(visible = outcome != null, onDismiss = dismiss, dismissible = outcome !is SaveOutcome.Failed) {
        when (val o = shown) {
            is SaveOutcome.Saved -> SavedContent(
                result = o.result,
                onShare = { uri -> sharePhoto(context, uri) },
                onDelete = {
                    scope.launch {
                        vm.deleteSaved(o.result)?.let { deleteConsent.launch(IntentSenderRequest.Builder(it).build()) }
                    }
                },
                onDetails = { vm.doneWithSaved(); onOpenSession(o.result.sessionId) },
                onDone = dismiss,
            )
            SaveOutcome.Failed -> {
                Text("Could not save the photo.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Free some space, then tap Save. The stack is kept until you do.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(shape = ButtonShape, onClick = { vm.discard() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard") }
                    Spacer(Modifier.width(8.dp))
                    Button(shape = ButtonShape, onClick = { vm.retrySave() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save") }
                }
            }
            SaveOutcome.NothingCaught -> {
                Text("No strikes this time", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(
                    "No flash crossed the trigger while Lightning was armed, so nothing was saved. Try High sensitivity for flashes behind cloud.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Button(shape = ButtonShape, onClick = dismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text("Done") }
            }
            null -> Unit
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SavedContent(
    result: SavedResult,
    onShare: (Uri) -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onDone: () -> Unit,
) {
    var which by rememberSaveable(result.sessionId) { mutableIntStateOf(0) }
    val uri = if (which == 1 && result.sharpestUri != null) result.sharpestUri else result.stackUri
    Text("Saved to Pictures/Slowglass.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "${result.mode.label}, ${result.width} x ${result.height}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (result.sharpestUri != null) {
        Spacer(Modifier.height(12.dp))
        SegmentedToggle(listOf("Stack", "Sharpest"), which, { which = it }, Modifier.align(Alignment.CenterHorizontally))
    }
    Spacer(Modifier.height(12.dp))
    val aspect = (result.width.toFloat() / result.height).coerceIn(0.4f, 2.5f)
    PhotoView(
        uri = uri.toString(),
        maxSide = 1280,
        description = if (which == 1) "Sharpest single frame" else "${result.mode.label} stack",
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .heightIn(max = 380.dp)
            .aspectRatio(aspect, matchHeightConstraintsFirst = true)
            .clickable(onClickLabel = "Open details", onClick = onDetails),
    )
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(shape = ButtonShape, onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(shape = ButtonShape, onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Done") }
        Spacer(Modifier.width(8.dp))
        Button(shape = ButtonShape, onClick = { onShare(uri) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
    }
}
