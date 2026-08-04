package com.xopp.android

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.InputSettings
import com.xopp.android.render.PaletteInvocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the two button-free ways of summoning the radial palette
 * ([PaletteInvocation]). Like `StylusInputTest`, these need real `MotionEvent`s carrying a tool type
 * and two pointers — `adb input` can inject neither — so they run via `connectedDebugAndroidTest`.
 * The pure decision rules behind the two-finger tap are unit-tested in `PaletteTapDetectorTest`;
 * this proves the view is wired to them and that neither gesture steals a stroke or a pan.
 */
@RunWith(AndroidJUnit4::class)
class PaletteInvocationInputTest {

    /** Two fingers down, still, and up promptly: the palette opens and no ink is left behind. */
    @Test
    fun twoFingerTapOpensThePalette() = onView { view ->
        view.inputSettings = InputSettings(paletteInvocation = PaletteInvocation.TWO_FINGER_TAP)
        twoFingerTap(view, holdMs = 40)
        assertTrue("the tap opened the palette", view.paletteOpen)
        assertEquals("the tap left no stroke", 0, strokesOf(view).size)
    }

    /** Two fingers that travel are a pan or a pinch — the palette must stay shut. */
    @Test
    fun twoFingerPanDoesNotOpenThePalette() = onView { view ->
        view.inputSettings = InputSettings(paletteInvocation = PaletteInvocation.TWO_FINGER_TAP)
        twoFingerTap(view, holdMs = 40, travelPx = 220f)
        assertFalse("a pan did not open the palette", view.paletteOpen)
    }

    /** The same tap with the setting left on its default does nothing — only the chosen gesture is live. */
    @Test
    fun twoFingerTapIsInertUnderTheDefaultInvocation() = onView { view ->
        view.inputSettings = InputSettings(paletteInvocation = PaletteInvocation.NONE)
        twoFingerTap(view, holdMs = 40)
        assertFalse("the default invocation ignores the two-finger tap", view.paletteOpen)
    }

    /** A stylus held still on the glass opens the palette, and the held tip commits no ink. */
    @Test
    fun penTipLongPressOpensThePalette() = onView { view ->
        view.tool = Tool.PEN
        view.inputSettings = InputSettings(paletteInvocation = PaletteInvocation.PEN_TIP_LONG_PRESS)
        val downTime = SystemClock.uptimeMillis()
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(400f), floatArrayOf(600f))
        // The hold runs on the view's own message queue, so let it come round.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(LONG_PRESS_WAIT_MS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("the hold opened the palette", view.paletteOpen)
        assertEquals("the held tip committed no stroke", 0, strokesOf(view).size)
    }

    /** A tip that moves off is writing, not summoning: the stroke survives and no menu appears. */
    @Test
    fun penTipThatMovesStillDraws() = onView { view ->
        view.tool = Tool.PEN
        view.inputSettings = InputSettings(paletteInvocation = PaletteInvocation.PEN_TIP_LONG_PRESS)
        val downTime = SystemClock.uptimeMillis()
        var x = 400f
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(x), floatArrayOf(600f))
        repeat(5) {
            x += 40f
            send(view, downTime, now(), MotionEvent.ACTION_MOVE, floatArrayOf(x), floatArrayOf(600f))
        }
        SystemClock.sleep(LONG_PRESS_WAIT_MS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse("a moving tip did not open the palette", view.paletteOpen)
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(x), floatArrayOf(600f))
        assertEquals("the stroke was not stolen", 1, strokesOf(view).size)
    }

    /**
     * The menu survives a pick — several settings in one summoning — and only a release clear of
     * the whole ring ("click off it") puts it away.
     */
    @Test
    fun aPickLeavesTheMenuUpAndOnlyAnOutsideTapClosesIt() = onView { view ->
        view.inputSettings = InputSettings(paletteInvocation = PaletteInvocation.TWO_FINGER_TAP)
        twoFingerTap(view, holdMs = 40)
        assertTrue("the tap opened the palette", view.paletteOpen)

        // A pick on the inner ring, straight up from where the tap anchored it.
        tapAt(view, 550f, 900f - 100f)
        assertTrue("the pick left the menu up", view.paletteOpen)

        // A second pick, proving the menu is still live rather than merely still painted.
        tapAt(view, 550f + 100f, 900f)
        assertTrue("the menu is still up after a second pick", view.paletteOpen)

        // Well past the dismiss radius: this is the click-off that closes it.
        tapAt(view, 550f, 900f + 450f)
        assertFalse("a tap clear of the ring closed the menu", view.paletteOpen)
        assertEquals("nothing the menu did left a stroke", 0, strokesOf(view).size)
    }

    /** One stylus down-and-up at ([x], [y]) — a pick when the menu is up. */
    private fun tapAt(view: DrawingSurfaceView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(x), floatArrayOf(y))
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(x), floatArrayOf(y))
    }

    // --- harness -------------------------------------------------------------------------------

    /** Two fingers down together, optionally dragged [travelPx], held [holdMs], then lifted. */
    private fun twoFingerTap(view: DrawingSurfaceView, holdMs: Long, travelPx: Float = 0f) {
        val downTime = SystemClock.uptimeMillis()
        val ax = 400f
        val bx = 700f
        val y = 900f
        val finger = MotionEvent.TOOL_TYPE_FINGER
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(ax), floatArrayOf(y), intArrayOf(finger))
        send(
            view, downTime, downTime, MotionEvent.ACTION_POINTER_DOWN,
            floatArrayOf(ax, bx), floatArrayOf(y, y), intArrayOf(finger, finger), actionIndex = 1,
        )
        if (travelPx != 0f) {
            send(
                view, downTime, now(), MotionEvent.ACTION_MOVE,
                floatArrayOf(ax, bx), floatArrayOf(y + travelPx, y + travelPx), intArrayOf(finger, finger),
            )
        }
        SystemClock.sleep(holdMs)
        send(
            view, downTime, now(), MotionEvent.ACTION_POINTER_UP,
            floatArrayOf(ax, bx), floatArrayOf(y, y), intArrayOf(finger, finger), actionIndex = 1,
        )
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(ax), floatArrayOf(y), intArrayOf(finger))
    }

    /**
     * Run [body] against a laid-out surface on the main thread. Unlike the other input tests this
     * one lets the view's own `postDelayed` work run, which is what the long-press hold rides on.
     */
    private fun onView(body: (DrawingSurfaceView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingSurfaceView
        instrumentation.runOnMainSync {
            view = DrawingSurfaceView(instrumentation.targetContext)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, VIEW_W, VIEW_H)
            view.load(blankDocument())
        }
        instrumentation.waitForIdleSync()
        body(view)
        instrumentation.waitForIdleSync()
    }

    private fun send(
        view: View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        toolTypes: IntArray = IntArray(xs.size) { MotionEvent.TOOL_TYPE_STYLUS },
        actionIndex: Int = 0,
    ) {
        val n = xs.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply { id = i; this.toolType = toolTypes[i] }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply { x = xs[i]; y = ys[i]; pressure = 1f; size = 1f }
        }
        val maskedAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(
            downTime, eventTime, maskedAction, n, props, coords,
            0, 0, 1f, 1f, 0, 0, 0x00004002 /* InputDevice.SOURCE_STYLUS */, 0,
        )
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.onTouchEvent(event) }
        } finally {
            event.recycle()
        }
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun strokesOf(view: DrawingSurfaceView): List<Stroke> =
        view.toDocument().pages.flatMap { p -> p.layers.flatMap { it.elements } }.filterIsInstance<Stroke>()

    private fun blankDocument(): Document =
        Document(
            pages = listOf(
                Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(0xFFFFFFFF.toInt(), "graph"), listOf(Layer(emptyList()))),
            ),
        )

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val VIEW_W = 1080
        const val VIEW_H = 1920
        /** Comfortably past the platform long-press timeout (500 ms on every current device). */
        const val LONG_PRESS_WAIT_MS = 900L
    }
}
