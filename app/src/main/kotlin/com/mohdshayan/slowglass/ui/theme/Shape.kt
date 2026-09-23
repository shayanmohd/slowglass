package com.mohdshayan.slowglass.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/*
 * The whole radius scale, and the rule for using it:
 *   RadiusSm  6dp   chips
 *   RadiusMd  12dp  buttons and thumbnails
 *   RadiusLg  20dp  sheet tops
 * The shutter and the zoom chips are full circles: the documented camera-hardware exception.
 */
val RadiusSm = 6.dp
val RadiusMd = 12.dp
val RadiusLg = 20.dp

val SheetShape = RoundedCornerShape(topStart = RadiusLg, topEnd = RadiusLg)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(RadiusSm),
    small = RoundedCornerShape(RadiusSm),
    medium = RoundedCornerShape(RadiusMd),
    large = RoundedCornerShape(RadiusLg),
    extraLarge = RoundedCornerShape(RadiusLg),
)
