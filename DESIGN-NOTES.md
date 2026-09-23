# Slowglass design notes

**Design read.** Reading this as: a night-photography camera for phone owners without a stock long exposure mode, with an observatory-at-dusk language (dark viewfinder, wide engraved numerals like a lens barrel, one violet light), leaning toward Michroma plus Hanken Grotesk on a blue-hour graphite and ultraviolet palette.

**Dials.**
- DESIGN_VARIANCE 3: a camera puts the shutter in the same place every time. Symmetric bottom rail, fixed control positions.
- MOTION_INTENSITY 3: motion reports exposure progress (the ring) and answers taps. Nothing else moves.
- VISUAL_DENSITY 4: the viewfinder is the content; controls stay few and large for cold hands.

**The one memorable thing.** The trail ring: a thin Ultraviolet arc around the shutter that draws itself one lap per minute, with the elapsed time above it in wide Michroma numerals set in fixed-width digit slots. Exposure clock, stop button and brand in one element. Everything around it stays graphite and quiet.

**Tokens (blueprint section 7).**

| Token | Light | Dark (Capture always) |
|---|---|---|
| Dusk | #EEEDF3 | #15141C |
| Pane | #F8F7FB | #1F1D28 |
| Ink | #1B1A24 | #E7E5EF |
| Haze | #585566 | #A3A0B4 |
| Ultraviolet | #5B3FC4 | #AE9FE8 |
| Flare | #B0343A | #F0948F |

**Type.** Michroma 400 only where a camera would engrave: the clock, the duration chip and the mode names. Hanken Grotesk 400/500/600 for everything else. No serif.

**Shape.** 6dp chips, 12dp buttons and thumbnails, 20dp sheet tops. Shutter and zoom chips are full circles (camera-hardware exception). Lists use spacing and dividers, not cards.

**Motion.** No first-run moment. Ring advances per frame (per second under reduced motion). Shutter morphs to Stop in 200 ms. Saved sheet rises in 250 ms. All snap under LocalReducedMotion.

**Rules held for the whole app.** One accent. No hex in screen files. No gradient or glow in the UI. Text over the live image sits on the Pane scrim. Sentence case. No middle dots, no arrows on buttons, no all-caps labels.

## Build log

- Icon: the pane-of-glass drafts, with trails cut into a tilted sheet, kept reading as a document with lines at 48 px. Redrawn as the trails alone: three light trails fanning up from one vanishing point round a bend, the shape a night road leaves in a long exposure, on a violet glass tile.
- Buttons: Material's pill default replaced with the 12dp step of the radius scale everywhere.
- Viewfinder: a Pane scrim band behind the status bar keeps its icons readable over bright scenes; before the first frame the viewfinder shows the Dusk ground with every control in place instead of a black surface.
- Mode strip: Michroma at 13sp fits all five names at 384dp once horizontal padding dropped to 9dp.
- Saved sheet: the Stack and Sharpest toggle moved under the title so "Saved to Pictures/Slowglass." never wraps.
- Trail ring: tapered segments, 1dp at the tail to 4dp at the head, flat Ultraviolet, one lap per minute; completed laps stay as a quiet full circle.
