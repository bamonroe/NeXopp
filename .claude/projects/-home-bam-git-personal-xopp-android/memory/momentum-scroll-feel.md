---
name: momentum-scroll-feel
description: Owner's intended feel for pan momentum — one-finger only, quadratic in flick speed
metadata:
  type: feedback
---

The owner's mental model for pan momentum ("throw a rock"): coast distance should
span a wide dynamic range keyed to flick speed — a tiny/slow flick barely drifts,
a fast corner-to-corner swipe flies many pages. A plain *linear* velocity→coast
map felt too compressed (tiny flick coasted ~half a page while a huge fast swipe
only managed ~1.5 pages even at 5×).

**Two-finger pans should carry NO momentum at all — that is the intended feel, not
a bug.** Momentum applies only to **one-finger** panning (Hand tool, or finger with
finger-draw off).

**Why:** matches physical intuition and keeps two-finger panning precise/predictable.

**How to apply:** momentum coast is shaped by a user-selectable `MomentumCurve`
(LINEAR/QUADRATIC/CUBIC/EXPONENTIAL, default QUADRATIC) via `Momentum.seed`
(`render/Fling.kt`), pivoting at `REFERENCE_SPEED_PX` (every curve pinned to
factor(0)=0, factor(1)=1). Keep the selectable-curve design and the reference-point
invariant; tune `REFERENCE_SPEED_PX` (pivot) or the strength range if asked. Don't
add two-finger momentum. Related code: the fling seed in `DrawingSurfaceView`,
`Momentum.seed`/`MomentumCurve`, and `VelocityEstimator`.
