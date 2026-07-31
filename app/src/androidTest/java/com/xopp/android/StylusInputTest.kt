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
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import com.xopp.android.render.BarrelAction
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.InputSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the stylus input layer (see `docs/architecture.md` → "Stylus &
 * selection roadmap"): the eraser tip, the finger-draw palm gate, the barrel button, and palm
 * rejection while a stylus writes. These need real Android `MotionEvent`s carrying a *tool type* and
 * *button state* (which `adb input` can't inject and JVM unit tests can't build), so they live here
 * and run via `connectedDebugAndroidTest`. The pure decision logic is unit-tested in
 * `InputClassifierTest`; this proves the view is wired to it.
 */
@RunWith(AndroidJUnit4::class)
class StylusInputTest {

    /** The eraser tip erases a stroke even though the selected tool is the pen. */
    @Test
    fun eraserTipErasesWithPenSelected() = onView { view ->
        view.tool = Tool.PEN
        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals("pen laid down a stroke", 1, strokesOf(view).size)

        // Same path, but with the flipped-over eraser tip: it should delete the stroke.
        drawLine(view, MotionEvent.TOOL_TYPE_ERASER)
        assertEquals("eraser tip removed the stroke", 0, strokesOf(view).size)
    }

    /** The barrel button erases while held, regardless of the pen being selected (default mapping). */
    @Test
    fun barrelButtonErasesWhileHeld() = onView { view ->
        view.tool = Tool.PEN
        view.inputSettings = InputSettings(barrelAction = BarrelAction.ERASE)
        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals(1, strokesOf(view).size)

        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS, buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertEquals("barrel-held stylus erased the stroke", 0, strokesOf(view).size)
    }

    /** With finger-draw off, a finger only pans — it must not lay ink; a stylus still draws. */
    @Test
    fun fingerDrawOffStopsFingerButNotStylus() = onView { view ->
        view.tool = Tool.PEN
        view.inputSettings = InputSettings(fingerDraws = false)

        drawLine(view, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals("finger did not draw", 0, strokesOf(view).size)

        drawLine(view, MotionEvent.TOOL_TYPE_STYLUS)
        assertEquals("stylus still draws", 1, strokesOf(view).size)
    }

    /** A palm (finger) resting mid-stroke while the stylus writes is ignored, not drawn/panned. */
    @Test
    fun palmRestingDuringStylusStrokeIsIgnored() = onView { view ->
        view.tool = Tool.PEN
        val downTime = SystemClock.uptimeMillis()

        // Stylus tip goes down and moves right.
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(200f), floatArrayOf(300f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))
        send(view, downTime, now(), MotionEvent.ACTION_MOVE, floatArrayOf(260f), floatArrayOf(300f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))

        // A palm lands as a second pointer and moves — it must not perturb the stroke.
        send(view, downTime, now(), MotionEvent.ACTION_POINTER_DOWN,
            floatArrayOf(260f, 850f), floatArrayOf(300f, 900f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER), actionIndex = 1)
        send(view, downTime, now(), MotionEvent.ACTION_MOVE,
            floatArrayOf(320f, 860f), floatArrayOf(300f, 910f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER))
        // Palm lifts, then the stylus lifts.
        send(view, downTime, now(), MotionEvent.ACTION_POINTER_UP,
            floatArrayOf(320f, 860f), floatArrayOf(300f, 910f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_FINGER), actionIndex = 1)
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(320f), floatArrayOf(300f), intArrayOf(MotionEvent.TOOL_TYPE_STYLUS))

        val strokes = strokesOf(view)
        assertEquals("exactly one stroke, from the stylus only", 1, strokes.size)
        // The palm was far below (view y≈900); its page-y would be large. Every sampled point must
        // come from the stylus's own path (y≈300 view px → small page y), proving the palm was ignored.
        val maxY = strokes[0].points.maxOf { it.y }
        assertTrue("no palm samples leaked into the stroke (maxY=$maxY)", maxY < 200.0)
    }

    // --- harness -------------------------------------------------------------------------------

    private fun onView(body: (DrawingSurfaceView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = DrawingSurfaceView(instrumentation.targetContext)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, VIEW_W, VIEW_H)
            view.load(blankDocument())
            body(view)
        }
        instrumentation.waitForIdleSync()
    }

    /** Down -> a few moves -> up, all with the given [toolType] (and optional held [buttonState]). */
    private fun drawLine(view: DrawingSurfaceView, toolType: Int, buttonState: Int = 0) {
        val downTime = SystemClock.uptimeMillis()
        var x = 200f
        val y = 300f
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(x), floatArrayOf(y), intArrayOf(toolType), buttonState)
        repeat(5) {
            x += 30f
            send(view, downTime, now(), MotionEvent.ACTION_MOVE, floatArrayOf(x), floatArrayOf(y), intArrayOf(toolType), buttonState)
        }
        send(view, downTime, now(), MotionEvent.ACTION_UP, floatArrayOf(x), floatArrayOf(y), intArrayOf(toolType), buttonState)
    }

    /** Build and dispatch a multi-pointer [MotionEvent] with explicit tool types and button state. */
    private fun send(
        view: View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        xs: FloatArray,
        ys: FloatArray,
        toolTypes: IntArray,
        buttonState: Int = 0,
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
            0, buttonState, 1f, 1f, 0, 0, 0x00004002 /* InputDevice.SOURCE_STYLUS */, 0,
        )
        try {
            view.onTouchEvent(event)
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
    }
}
