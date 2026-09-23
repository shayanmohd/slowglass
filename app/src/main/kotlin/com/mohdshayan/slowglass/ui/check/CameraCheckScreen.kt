package com.mohdshayan.slowglass.ui.check

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.slowglass.capture.CaptureConnection
import com.mohdshayan.slowglass.capture.CheckProgress
import com.mohdshayan.slowglass.capture.EngineState
import com.mohdshayan.slowglass.core.check.CheckRow
import com.mohdshayan.slowglass.core.check.CheckVerdict
import com.mohdshayan.slowglass.core.check.RowStatus
import com.mohdshayan.slowglass.data.db.CameraCheckEntity
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.ui.components.ButtonShape
import com.mohdshayan.slowglass.ui.components.AppTopBar
import com.mohdshayan.slowglass.ui.components.SkeletonBlock
import com.mohdshayan.slowglass.ui.components.shareText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class CameraCheckViewModel(app: Application) : AndroidViewModel(app) {
    init {
        CaptureConnection.ensureBound(app)
    }

    val engine: StateFlow<EngineState> =
        CaptureConnection.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EngineState())
    val last: StateFlow<CameraCheckEntity?> =
        ServiceLocator.sessions.observeLatestCheck().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun run() = CaptureConnection.service.value?.runCheck()
    fun reset() = CaptureConnection.service.value?.resetCheck()
}

@Composable
fun CameraCheckScreen(firstRun: Boolean, onBack: () -> Unit, onDone: () -> Unit, vm: CameraCheckViewModel = viewModel()) {
    val engine by vm.engine.collectAsStateWithLifecycle()
    val last by vm.last.collectAsStateWithLifecycle()
    val service by CaptureConnection.service.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The first launch runs the check straight away; later visits wait for the button.
    LaunchedEffect(service, firstRun) {
        if (service != null && firstRun && engine.check == CheckProgress.Idle) vm.run()
    }
    DisposableEffect(Unit) { onDispose { vm.reset() } }

    Scaffold(topBar = { AppTopBar("Camera check", onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .widthIn(max = 640.dp),
        ) {
            when (val c = engine.check) {
                CheckProgress.Idle -> {
                    Intro()
                    if (last != null) {
                        Spacer(Modifier.height(20.dp))
                        Text("Last result", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(Modifier.height(6.dp))
                        Text(last!!.verdict, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(24.dp))
                    Button(shape = ButtonShape, onClick = { vm.run() }, enabled = service != null, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(if (last == null) "Run the check" else "Run it again")
                    }
                }
                is CheckProgress.Running -> {
                    Intro()
                    Spacer(Modifier.height(20.dp))
                    Text(c.step, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    // Rows in the shape of the result, filled when the check finishes.
                    repeat(6) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            SkeletonBlock(Modifier.size(24.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                SkeletonBlock(Modifier.fillMaxWidth(0.45f).height(14.dp))
                                Spacer(Modifier.height(6.dp))
                                SkeletonBlock(Modifier.fillMaxWidth(0.75f).height(12.dp))
                            }
                        }
                    }
                }
                is CheckProgress.Done -> {
                    Text(c.report.verdict, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.height(16.dp))
                    c.report.rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        ResultRow(r)
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(shape = ButtonShape, onClick = onDone, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start shooting") }
                        OutlinedButton(
                            shape = ButtonShape,
                            onClick = {
                                shareText(
                                    context,
                                    CheckVerdict.shareText(c.report, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.RELEASE),
                                    "Share report",
                                )
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Share report") }
                    }
                }
                is CheckProgress.Failed -> {
                    Text(c.message, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(20.dp))
                    Button(shape = ButtonShape, onClick = { vm.reset(); vm.run() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Run it again") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Intro() {
    Text(
        "Five seconds with the camera tells you what this phone can do before you rely on it: the stream size, the frame rate while stacking, and whether stars and 4K work.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Point the camera at anything. Nothing is saved.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ResultRow(r: CheckRow) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        val (icon, tint) = when (r.status) {
            RowStatus.PASS -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
            RowStatus.NOTE -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
            RowStatus.FAIL -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        }
        Icon(icon, contentDescription = r.status.name.lowercase().replaceFirstChar { it.uppercase() }, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
