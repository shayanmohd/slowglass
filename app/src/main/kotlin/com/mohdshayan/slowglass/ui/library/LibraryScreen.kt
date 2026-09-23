package com.mohdshayan.slowglass.ui.library

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mohdshayan.slowglass.core.stack.StackMode
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.data.db.SessionEntity
import com.mohdshayan.slowglass.di.ServiceLocator
import com.mohdshayan.slowglass.ui.components.AppTopBar
import com.mohdshayan.slowglass.ui.components.ChoiceChip
import com.mohdshayan.slowglass.ui.components.EmptyState
import com.mohdshayan.slowglass.ui.components.PhotoView
import com.mohdshayan.slowglass.ui.components.SkeletonBlock
import com.mohdshayan.slowglass.ui.components.rememberDelayedFlag
import com.mohdshayan.slowglass.ui.theme.RadiusMd
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    /** Null while the first read is in flight. */
    val sessions: StateFlow<List<SessionEntity>?> = ServiceLocator.sessions.observeAll()
        .map<List<SessionEntity>, List<SessionEntity>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenCamera: () -> Unit,
    vm: LibraryViewModel = viewModel(),
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = { AppTopBar("Library", onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val list = sessions
        when {
            list == null -> if (rememberDelayedFlag()) LibrarySkeleton(Modifier.padding(pad)) else Box(Modifier.padding(pad))
            list.isEmpty() -> EmptyState(
                title = "Nothing stacked yet",
                body = "Your long exposures will appear here.",
                actionLabel = "Open camera",
                onAction = onOpenCamera,
                modifier = Modifier.fillMaxSize().padding(pad),
            )
            else -> {
                // A filter whose last session was deleted falls back to All instead of an empty grid.
                val active = filter?.takeIf { f -> list.any { it.mode == f } }
                val shown = if (active == null) list else list.filter { it.mode == active }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoiceChip("All", active == null, { filter = null })
                            StackMode.entries.forEach { m ->
                                if (list.any { it.mode == m.id }) ChoiceChip(m.shortLabel, active == m.id, { filter = m.id })
                            }
                        }
                    }
                    items(shown, key = { it.id }) { s -> SessionTile(s, onClick = { onOpen(s.id) }) }
                }
            }
        }
    }
}

@Composable
private fun SessionTile(s: SessionEntity, onClick: () -> Unit) {
    val mode = StackMode.fromId(s.mode)
    Column(
        Modifier
            .clip(RoundedCornerShape(RadiusMd))
            .clickable(role = Role.Button, onClickLabel = "Open ${mode.label}", onClick = onClick),
    ) {
        PhotoView(
            uri = s.stackUri,
            maxSide = 480,
            contentScale = ContentScale.Crop,
            description = "${mode.label} photo",
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${mode.label}, ${ExposureClock.format(s.durationMs)}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        Text(
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun LibrarySkeleton(modifier: Modifier) {
    Column(modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { SkeletonBlock(Modifier.width(72.dp).height(44.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(2) {
                    Column(Modifier.weight(1f)) {
                        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                        Spacer(Modifier.height(8.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(14.dp))
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
