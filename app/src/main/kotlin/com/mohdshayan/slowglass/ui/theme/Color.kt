package com.mohdshayan.slowglass.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Blue-hour graphite and ultraviolet (BLUEPRINT.md section 7). Graphite is the sky after sunset;
 * Ultraviolet is the one accent, used only on the trail ring, primary buttons, selected chips and
 * focus marks. No screen file holds a hex value; everything reads these tokens.
 *
 *   Dusk        background
 *   Pane        sheets and the scrim over the viewfinder; onPrimary in light
 *   Ink         text and the exposure clock
 *   Haze        secondary text and icons; dividers at 24 percent
 *   Ultraviolet primary; Dusk is onPrimary in dark
 *   Flare       errors, heat and battery notes
 */

// Light mode
val LightBackground = Color(0xFFEEEDF3) // Dusk
val LightSurface = Color(0xFFF8F7FB) // Pane
val LightSurfaceVariant = Color(0xFFE3E1EB) // Dusk, one step deeper, for skeletons and chip grounds
val LightOutline = Color(0x3D585566) // Haze at 24 percent
val LightOnBackground = Color(0xFF1B1A24) // Ink
val LightOnSurfaceVariant = Color(0xFF585566) // Haze
val LightAccent = Color(0xFF5B3FC4) // Ultraviolet
val LightOnAccent = Color(0xFFF8F7FB) // Pane
val LightError = Color(0xFFB0343A) // Flare
val LightOnError = Color(0xFFF8F7FB)

// Dark mode, and the Capture screen always
val DarkBackground = Color(0xFF15141C) // Dusk
val DarkSurface = Color(0xFF1F1D28) // Pane
val DarkSurfaceVariant = Color(0xFF2A2834) // Pane, one step lighter, for skeletons and chip grounds
val DarkOutline = Color(0x3DA3A0B4) // Haze at 24 percent
val DarkOnBackground = Color(0xFFE7E5EF) // Ink
val DarkOnSurfaceVariant = Color(0xFFA3A0B4) // Haze
val DarkAccent = Color(0xFFAE9FE8) // Ultraviolet
val DarkOnAccent = Color(0xFF15141C) // Dusk
val DarkError = Color(0xFFF0948F) // Flare
val DarkOnError = Color(0xFF15141C)

/** The Pane scrim that text over the live image sits on, 90 percent opaque. */
val ViewfinderScrim = Color(0xE61F1D28)
