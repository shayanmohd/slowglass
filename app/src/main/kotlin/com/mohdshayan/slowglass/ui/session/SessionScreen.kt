package com.mohdshayan.slowglass.ui.session

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.data.db.SessionEntity
import com.mohdshayan.slowglass.data.db.StrikeEntity
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.save.MediaDeleter
import com.mohdshayan.slowglass.ui.components.ButtonShape
import com.mohdshayan.slowglass.ui.components.AppTopBar
import com.mohdshayan.slowglass.ui.components.EmptyState
import com.mohdshayan.slowglass.ui.components.PhotoView
import com.mohdshayan.slowglass.ui.components.SegmentedToggle
import com.mohdshayan.slowglass.ui.components.SkeletonBlock
import com.mohdshayan.slowglass.ui.components.rememberDelayedFlag
import com.mohdshayan.slowglass.ui.components.sharePhoto
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Loading, a missing entry, or the session. */
sealed interface SessionLoad {
    data object Loading : SessionLoad
    data object Gone : SessionLoad
    data class Ready(val session: SessionEntity) : SessionLoad
}

class SessionViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.sessions
    private var id = -1L
    lateinit var session: StateFlow<SessionLoad>
        private set
    lateinit var strikes: StateFlow<List<StrikeEntity>>
        private set

    fun bind(sessionId: Long) {
        if (id == sessionId) return
        id = sessionId
        session = repo.observe(sessionId)
            .map { if (it == null) SessionLoad.Gone else SessionLoad.Ready(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionLoad.Loading)
        strikes = repo.observeStrikes(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    suspend fun exists(uri: String?) = repo.exists(uri)

    /** Deletes the photos and the entry; returns Android's delete question when it needs one. */
    suspend fun delete(s: SessionEntity, strikes: List<StrikeEntity>): IntentSender? {
        val uris = (listOfNotNull(s.stackUri, s.sharpestUri) + strikes.map { it.photoUri }).map(Uri::parse)
        val sender = MediaDeleter.delete(getApplication(), uris)
        repo.deleteEntry(s.id)
        return sender
    }

    fun deleteEntryOnly(s: SessionEntity) = viewModelScope.launch { repo.deleteEntry(s.id) }
}

@Composable
fun SessionScreen(id: Long, onBack: () -> Unit, onOpenCamera: () -> Unit, vm: SessionViewModel = viewModel()) {
    vm.bind(id)
    val load by vm.session.collectAsStateWithLifecycle()
    val strikes by vm.strikes.collectAsStateWithLifecycle()
    val title = (load as? SessionLoad.Ready)?.session?.let { StackMode.fromId(it.mode).label } ?: "Session"
    Scaffold(topBar = { AppTopBar(title, onBack) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
        when (val l = load) {
            SessionLoad.Loading -> if (rememberDelayedFlag()) {
                Column(Modifier.padding(pad).padding(16.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                    Spacer(Modifier.height(16.dp))
                    repeat(4) { SkeletonBlock(Modifier.fillMaxWidth(0.8f).height(18.dp)); Spacer(Modifier.height(12.dp)) }
                }
            }
            SessionLoad.Gone -> EmptyState(
                title = "This session was deleted",
                body = "Its photos and details are gone from Slowglass.",
                actionLabel = "Open camera",
                onAction = onOpenCamera,
                modifier = Modifier.fillMaxSize().padding(pad),
            )
            is SessionLoad.Ready -> SessionBody(l.session, strikes, vm, onBack, Modifier.padding(pad))
        }
    }
}

@Composable
private fun SessionBody(s: SessionEntity, strikes: List<StrikeEntity>, vm: SessionViewModel, onDeleted: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onDeleted() }
    val mode = StackMode.fromId(s.mode)
    var which by rememberSaveable { mutableIntStateOf(0) }
    val exists by produceState<Boolean?>(null, s.stackUri) { value = vm.exists(s.stackUri) }
    val uri = if (which == 1 && s.sharpestUri != null) s.sharpestUri else s.stackUri

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        if (exists == false) {
            // The photo was deleted outside Slowglass: say so and offer to tidy the entry.
            Column(Modifier.padding(vertical = 24.dp)) {
                Text("Photo not on this phone", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Slowglass cannot open this photo any more. It was deleted, or it was saved by another phone or an earlier install of the app, in which case it may still be in your gallery.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(shape = ButtonShape, onClick = { vm.deleteEntryOnly(s); onDeleted() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete entry") }
            }
        } else {
            if (s.sharpestUri != null) {
                SegmentedToggle(listOf("Stack", "Sharpest"), which, { which = it }, Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
            }
            val aspect = (s.streamWidth.toFloat() / s.streamHeight.coerceAtLeast(1)).coerceIn(0.4f, 2.5f)
            PhotoView(
                uri = uri,
                maxSide = 1600,
                description = if (which == 1) "Sharpest single frame" else "${mode.label} stack",
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .aspectRatio(aspect),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(shape = ButtonShape, onClick = { uri?.let { sharePhoto(context, Uri.parse(it)) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Share") }
                OutlinedButton(
                    shape = ButtonShape,
                    onClick = {
                        scope.launch {
                            val sender = vm.delete(s, strikes)
                            if (sender != null) consent.launch(IntentSenderRequest.Builder(sender).build()) else onDeleted()
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Details", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(4.dp))
        val gapOffsets = s.gapLog.trim('[', ']').split(',').mapNotNull { it.trim().toLongOrNull() }
        val rows = buildList {
            add("Taken" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt)))
            add("Length" to ExposureClock.format(s.durationMs))
            add("Frames stacked" to String.format(Locale.US, "%,d", s.frameCount))
            if (s.droppedFrames > 0) add("Frames dropped" to String.format(Locale.US, "%,d", s.droppedFrames))
            if (mode == StackMode.STARS) {
                add(
                    "Gaps" to when {
                        s.gapsBridged == 0 -> "None"
                        gapOffsets.size == 1 -> "1 gap bridged at ${ExposureClock.format(gapOffsets[0])}"
                        gapOffsets.isNotEmpty() -> "${gapOffsets.size} gaps bridged at " + gapOffsets.joinToString(", ") { ExposureClock.format(it) }
                        else -> "${s.gapsBridged} bridged"
                    },
                )
            }
            add("Stream size" to "${s.streamWidth} x ${s.streamHeight}")
            add(
                "Precision" to when {
                    s.precision == "RGBA16F" -> "16-bit"
                    mode == StackMode.LIGHTNING -> "8-bit, exact for lightening"
                    else -> "8-bit with dithering"
                },
            )
            if (mode == StackMode.LIGHTNING) add("Strikes" to strikes.size.toString())
        }
        rows.forEachIndexed { i, (k, v) ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(k, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(v, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1.2f))
            }
        }
        if (strikes.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Strikes", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            strikes.forEachIndexed { i, st ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhotoView(st.photoUri, 240, Modifier.size(60.dp), ContentScale.Crop, "Strike ${i + 1}")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Strike ${i + 1}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                        Text(
                            DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(st.at)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { sharePhoto(context, Uri.parse(st.photoUri)) }) {
                        Icon(Icons.Rounded.Share, contentDescription = "Share strike ${i + 1}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
