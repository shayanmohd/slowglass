package com.mohdshayan.slowglass.ui.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.slowglass.core.export.SessionCodec
import com.mohdshayan.slowglass.core.stack.LightningSensitivity
import com.mohdshayan.slowglass.core.stack.NoiseSmoothing
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.timer.BulbPreset
import com.mohdshayan.slowglass.core.timer.TripodDelay
import com.mohdshayan.slowglass.data.prefs.Settings
import com.mohdshayan.slowglass.data.prefs.ThemeMode
import com.mohdshayan.slowglass.data.repo.ImportOutcome
import com.mohdshayan.slowglass.data.repo.SessionRepository
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.ui.components.ButtonShape
import com.mohdshayan.slowglass.ui.components.AppTopBar
import com.mohdshayan.slowglass.ui.components.ChoiceChip
import com.mohdshayan.slowglass.ui.components.SkeletonBlock
import com.mohdshayan.slowglass.ui.components.rememberDelayedFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_IMPORT_BYTES = 16 * 1024 * 1024

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = ServiceLocator.appPrefs
    private val repo = ServiceLocator.sessions

    val settings: StateFlow<Settings?> = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun update(block: suspend () -> Unit) = viewModelScope.launch { block() }
    val p get() = prefs

    private fun plural(n: Int) = if (n == 1) "1 session" else "$n sessions"

    fun exportTo(uri: Uri, csv: Boolean) = viewModelScope.launch {
        try {
            val (text, n) = if (csv) repo.exportCsv() else repo.exportJson()
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    ?: error("no stream")
            }
            _messages.send("Exported ${plural(n)}")
        } catch (e: Exception) {
            _messages.send("Could not write that file. Pick another folder and try again.")
        }
    }

    /** Writes the JSON log to the cache and opens the share sheet with it. */
    fun share() = viewModelScope.launch {
        val app = getApplication<Application>()
        try {
            val (text, _) = repo.exportJson()
            val file = withContext(Dispatchers.IO) {
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                File(dir, SessionCodec.fileName(SessionRepository.today(), "json")).apply { writeText(text) }
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            app.startActivity(Intent.createChooser(send, "Share session log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            _messages.send("Could not prepare the session log. Free some space and try again.")
        }
    }

    fun importFrom(uri: Uri) = viewModelScope.launch {
        val text = try {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    // A session log is a few hundred kilobytes; anything past the cap is not one,
                    // and reading a huge file whole would run the app out of memory.
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (out.size() <= MAX_IMPORT_BYTES) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    if (out.size() > MAX_IMPORT_BYTES) null else out.toByteArray().decodeToString()
                }
            }
        } catch (e: Exception) {
            null
        }
        if (text == null) {
            _messages.send("That file is not a Slowglass session log.")
            return@launch
        }
        val outcome = try {
            repo.import(text)
        } catch (e: Exception) {
            _messages.send("Could not import that file. Free some space and try again.")
            return@launch
        }
        when (val r = outcome) {
            ImportOutcome.NotSlowglass -> _messages.send("That file is not a Slowglass session log.")
            is ImportOutcome.Imported -> {
                val base = "Imported ${plural(r.added)}"
                val extra = buildList {
                    if (r.skipped > 0) add("${r.skipped} already here")
                    if (r.missingPhotos > 0) add("${r.missingPhotos} with photos Slowglass cannot open here")
                }
                _messages.send(if (extra.isEmpty()) base else "$base. " + extra.joinToString(", ") + ".")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCameraCheck: () -> Unit,
    onPrivacy: () -> Unit,
    onLicences: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let { u -> vm.exportTo(u, csv = false) } }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let { u -> vm.exportTo(u, csv = true) } }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFrom) }

    Scaffold(
        topBar = { AppTopBar("Settings", onBack) },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val settings = s
        if (settings == null) {
            if (rememberDelayedFlag()) {
                Column(Modifier.padding(pad).padding(20.dp)) {
                    repeat(6) { SkeletonBlock(Modifier.fillMaxWidth(0.6f).height(16.dp)); Spacer(Modifier.height(28.dp)) }
                }
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .widthIn(max = 680.dp),
        ) {
            Section("New sessions start with")
            Choices("Mode", StackMode.entries, settings.defaultMode, { it.shortLabel }) { m -> vm.update { vm.p.setDefaultMode(m) } }
            Choices("Exposure length", BulbPreset.presetsMs, settings.bulbPresetMs, BulbPreset::label) { v -> vm.update { vm.p.setBulbPreset(v) } }
            Choices("Tripod delay", TripodDelay.options, settings.tripodDelayS, TripodDelay::label) { v -> vm.update { vm.p.setTripodDelay(v) } }
            Choices("Noise smoothing for trails", NoiseSmoothing.entries, settings.noiseSmoothing, { it.label }) { v -> vm.update { vm.p.setNoiseSmoothing(v) } }
            Choices("Lightning trigger", LightningSensitivity.entries, settings.lightningSensitivity, { it.label }) { v -> vm.update { vm.p.setLightningSensitivity(v) } }

            Section("During a session")
            Toggle("Dim the screen", "Drops to minimum brightness after 10 seconds without a touch.", settings.dimDuringSession) { v -> vm.update { vm.p.setDimDuringSession(v) } }
            Toggle("Volume keys start and stop", "Press either volume key instead of touching the phone.", settings.volumeKeysShutter) { v -> vm.update { vm.p.setVolumeKeysShutter(v) } }
            Toggle("Save the sharpest frame", "Keeps the crispest single frame beside each stack.", settings.saveSharpest) { v -> vm.update { vm.p.setSaveSharpest(v) } }

            Section("Appearance")
            Choices("Theme", ThemeMode.entries, settings.themeMode, { it.label }) { v -> vm.update { vm.p.setThemeMode(v) } }
            Text(
                "The camera screen is always dark.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Your sessions")
            Text(
                "Photos are ordinary files in Pictures/Slowglass. The session log holds the details of each one and can move to a new phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            val today = SessionRepository.today()
            LinkRow("Export sessions as JSON") { exportJson.launch(SessionCodec.fileName(today, "json")) }
            LinkRow("Export sessions as CSV") { exportCsv.launch(SessionCodec.fileName(today, "csv")) }
            LinkRow("Share the session log") { vm.share() }
            LinkRow("Import sessions") { importJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

            Section("Camera")
            LinkRow("Camera check", onCameraCheck)

            Section("Local counts")
            Toggle(
                "Count sessions on this phone",
                "Counts sessions, saves and strikes here only. Nothing leaves the phone; you can paste them into a support email.",
                settings.usageCountsOptIn,
            ) { v -> vm.update { vm.p.setUsageCountsOptIn(v) } }
            if (settings.usageCountsOptIn) {
                Text(
                    "${settings.countSessions} sessions, ${settings.countSaves} saves, ${settings.countStrikes} strikes",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                TextButton(shape = ButtonShape, onClick = { vm.update { vm.p.clearCounts() } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear counts") }
            }

            Section("About")
            LinkRow("Privacy policy", onPrivacy)
            LinkRow("Licences", onLicences)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(label: String, options: List<T>, selected: T, text: (T) -> String, onPick: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> ChoiceChip(text(o), o == selected, { onPick(o) }) }
    }
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = 14.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}
