package com.mohdshayan.slowglass.ui.components

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mohdshayan.slowglass.save.PhotoSaver
import com.mohdshayan.slowglass.ui.theme.LocalReducedMotion
import com.mohdshayan.slowglass.ui.theme.RadiusMd
import com.mohdshayan.slowglass.ui.theme.RadiusSm
import com.mohdshayan.slowglass.ui.theme.SheetShape
import kotlinx.coroutines.delay

/**
 * Centred empty state: a headline, one sentence and one filled action that says what to do next.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 340.dp),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(shape = ButtonShape, onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(actionLabel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/** A still block in the shape of content that is on its way. No shimmer: the content is static. */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(RadiusMd)).background(MaterialTheme.colorScheme.surfaceVariant))
}

/** True once [ms] has passed, so a skeleton only appears for loads that take a noticeable time. */
@Composable
fun rememberDelayedFlag(ms: Long = 300): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(ms)
        shown = true
    }
    return shown
}

sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Missing : PhotoLoad
    data class Ready(val bitmap: Bitmap) : PhotoLoad
}

@Composable
fun rememberPhoto(uri: String?, maxSide: Int): PhotoLoad {
    val context = LocalContext.current
    val state by produceState<PhotoLoad>(PhotoLoad.Loading, uri, maxSide) {
        value = if (uri == null) {
            PhotoLoad.Missing
        } else {
            PhotoSaver.loadPreview(context, Uri.parse(uri), maxSide)?.let { PhotoLoad.Ready(it) } ?: PhotoLoad.Missing
        }
    }
    return state
}

/** A saved photo: skeleton while it decodes, a plain note when the file is gone. */
@Composable
fun PhotoView(
    uri: String?,
    maxSide: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    description: String,
) {
    when (val p = rememberPhoto(uri, maxSide)) {
        PhotoLoad.Loading -> if (rememberDelayedFlag()) SkeletonBlock(modifier) else Box(modifier)
        PhotoLoad.Missing -> Box(
            modifier.clip(RoundedCornerShape(RadiusMd)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Photo not on this phone",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }
        is PhotoLoad.Ready -> Image(
            bitmap = p.bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = contentScale,
            modifier = modifier.clip(RoundedCornerShape(RadiusMd)),
        )
    }
}

/** Two-way toggle, used for Stack and Sharpest. */
@Composable
fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(RadiusMd))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(RadiusMd)),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(RadiusMd))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A chip in the radius scale's small step. Selected chips carry the accent. */
@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(RadiusSm)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A bottom sheet with the 20dp top radius. It rises in 250 ms, or appears at once under reduced
 * motion, and closes on the scrim or Back.
 */
@Composable
fun BoxScope.RisingSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    dismissible: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(snap()) else fadeIn(tween(250)),
        exit = if (reduced) fadeOut(snap()) else fadeOut(tween(200)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = dismissible,
                    onClick = onDismiss,
                ),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(snap()) else slideInVertically(tween(250)) { it },
        exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(200)) { it },
        // Below the status bar and scrollable, so large system text never pushes a button off screen.
        modifier = Modifier.align(Alignment.BottomCenter).statusBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .clip(SheetShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(RadiusSm))
                    .background(MaterialTheme.colorScheme.outline)
                    .height(4.dp)
                    .widthIn(min = 36.dp, max = 36.dp),
            )
            content()
        }
    }
    if (visible && dismissible) BackHandler(onBack = onDismiss)
}

/** Buttons take the 12dp step of the radius scale, not Material's default pill. */
val ButtonShape = RoundedCornerShape(RadiusMd)
