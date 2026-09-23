package com.mohdshayan.slowglass.ui.capture

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mohdshayan.slowglass.core.timer.ExposureClock
import com.mohdshayan.slowglass.ui.theme.ClockStyle
import com.mohdshayan.slowglass.ui.theme.LocalReducedMotion
import com.mohdshayan.slowglass.ui.theme.RadiusSm
import kotlinx.coroutines.delay
import android.os.SystemClock

/**
 * Elapsed session time, read every frame while running, or once a second under reduced motion.
 * Zero when no session runs.
 */
@Composable
fun rememberElapsed(startedAtRealtime: Long?): Long {
    val reduced = LocalReducedMotion.current
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtRealtime, reduced) {
        if (startedAtRealtime == null) {
            elapsed = 0
            return@LaunchedEffect
        }
        while (true) {
            val now = SystemClock.elapsedRealtime() - startedAtRealtime
            elapsed = ExposureClock.quantise(now, reduced)
            if (reduced) delay(1000 - now % 1000) else withFrameMillis { }
        }
    }
    return elapsed
}

/**
 * The exposure clock in Michroma, each character in a fixed-width slot so the numerals never jitter
 * as they change.
 */
@Composable
fun EngravedClock(text: String, color: Color, modifier: Modifier = Modifier, style: TextStyle = ClockStyle) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val digit = remember(style, density) {
        with(density) { measurer.measure("0", style).size.width.toDp() }
    }
    val colon = remember(style, density) {
        with(density) { measurer.measure(":", style).size.width.toDp() }
    }
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "Elapsed $text" }) {
        for (c in text) {
            Text(
                c.toString(),
                style = style,
                color = color,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(if (c.isDigit()) digit else colon + 2.dp),
            )
        }
    }
}

/**
 * The trail ring and the shutter: one element that is the exposure clock, the stop button and the
 * brand. The ring draws itself like a light trail, thin at the tail and thick at the head, one lap
 * per minute; laps already completed stay as a quiet full circle underneath.
 */
@Composable
fun ShutterRing(
    running: Boolean,
    elapsedMs: Long,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ringSize: Dp = 104.dp,
    decorative: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline
    val ink = MaterialTheme.colorScheme.onBackground
    val fraction = if (running) ExposureClock.ringFraction(elapsedMs) else 0f
    val laps = if (running) ExposureClock.laps(elapsedMs) else 0L
    Box(
        modifier
            .size(ringSize)
            .then(
                if (decorative) Modifier else Modifier
                    .semantics {
                        role = Role.Button
                        contentDescription = if (running) "Stop and save" else "Start exposure"
                        stateDescription = if (running) "Exposure running, ${ExposureClock.format(elapsedMs)}" else "Ready"
                    }
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onClick),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ringSize)) {
            val inset = 4.dp.toPx()
            val d = size.minDimension - inset * 2
            val topLeft = Offset(inset, inset)
            val arcSize = Size(d, d)
            drawArc(
                color = if (laps > 0) accent.copy(alpha = 0.45f) else track,
                startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = if (laps > 0) 2.dp.toPx() else 1.5.dp.toPx()),
            )
            if (fraction > 0f) {
                // The trail: short segments whose width grows from the tail to the head.
                val sweep = 360f * fraction
                val segments = (sweep / 3f).toInt().coerceIn(1, 120)
                val per = sweep / segments
                for (i in 0 until segments) {
                    val t = (i + 1f) / segments
                    val w = (1.dp.toPx() + 3.dp.toPx() * t * t)
                    drawArc(
                        color = accent,
                        startAngle = -90f + i * per,
                        sweepAngle = per + 0.6f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = w, cap = if (i == segments - 1) StrokeCap.Round else StrokeCap.Butt),
                    )
                }
            }
        }
        // Shutter to Stop: the disc shrinks into a rounded square in 200 ms.
        val spec = if (reduced) snap<Dp>() else tween(200)
        val side by animateDpAsState(if (running) 30.dp else 64.dp, spec, label = "shutter-size")
        val corner by animateDpAsState(if (running) RadiusSm else 32.dp, spec, label = "shutter-corner")
        val alpha by animateFloatAsState(if (enabled || decorative) 1f else 0.4f, if (reduced) snap() else tween(200), label = "shutter-alpha")
        Box(
            Modifier
                .size(76.dp)
                .border(2.dp, ink.copy(alpha = alpha), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(side)
                    .clip(RoundedCornerShape(corner))
                    .background(ink.copy(alpha = alpha)),
            )
        }
    }
}
