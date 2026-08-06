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
import com.xopp.android.render.DrawingSurfaceView
import com.xopp.android.render.copySelection
import com.xopp.android.render.deleteSelection
import com.xopp.android.render.pasteClipboard
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device verification of the selection tool's gesture wiring (see `docs/architecture.md` →
 * "Selecting objects"): lasso (free-form) select, copy/paste, and cross-page move. These need a
 * continuous multi-point finger gesture, which `adb input` can't inject as one stroke, so they run
 * here via synthetic `MotionEvent`s. The pure geometry (`SelectionTester.inPolygon`, `SelectionOps`)
 * is unit-tested in `SelectionTest`; this proves the view routes touches into it. Effects are read
 * back off the document (delete/paste change stroke counts) since the selection state is private.
 */
@RunWith(AndroidJUnit4::class)
class SelectionInputTest {

    /** A traced lasso selects only the strokes wholly inside it; deleting proves which were caught. */
    @Test
    fun lassoSelectsEnclosedStrokesOnly() = onView(strokes(inside, outside)) { view ->
        view.selectMode = true
        view.lassoMode = true
        // A hexagon over the top region: encloses `inside` (near the origin), not `outside` (far down).
        gesture(view, listOf(20f to 20f, 500f to 20f, 520f to 200f, 500f to 400f, 20f to 400f, 0f to 200f))
        view.deleteSelection()

        val left = strokesOf(view)
        assertEquals("only the un-enclosed stroke survives", 1, left.size)
        assertEquals("it is `outside` (its first point is far down the page)", 300.0, left[0].points[0].y, 1.0)
    }

    /** Copy then paste adds an offset duplicate; the paste is what's selected (deleting removes it). */
    @Test
    fun copyPasteAddsSelectedDuplicate() = onView(strokes(inside)) { view ->
        view.selectMode = true
        // Rectangle marquee around the single stroke.
        gesture(view, listOf(40f to 60f, 90f to 90f, 150f to 160f))
        view.copySelection()
        view.pasteClipboard()
        assertEquals("paste produced a second stroke", 2, strokesOf(view).size)

        // The pasted copy is the live selection: deleting it drops back to the original only.
        view.deleteSelection()
        assertEquals("delete removed just the pasted copy", 1, strokesOf(view).size)
    }

    /** Dragging a selection onto the next page re-homes the strokes there (cross-page move). */
    @Test
    fun movingSelectionOntoNextPageRehomesIt() = onView(twoPages()) { view ->
        view.selectMode = true
        // Select the stroke on page 0.
        gesture(view, listOf(40f to 60f, 90f to 90f, 150f to 160f))
        // Then grab inside the outline and drag far down, dropping over page 1's band.
        gesture(view, listOf(80f to 105f, 80f to 800f, 80f to 1650f))

        assertEquals("page 0 gave up its stroke", 0, strokesOfPage(view, 0).size)
        assertEquals("page 1 received it", 1, strokesOfPage(view, 1).size)
    }

    // --- harness -------------------------------------------------------------------------------

    private fun onView(doc: Document, body: (DrawingSurfaceView) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = DrawingSurfaceView(instrumentation.targetContext)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, VIEW_W, VIEW_H)
            view.load(doc)
            body(view)
        }
        instrumentation.waitForIdleSync()
    }

    /** Send a single-finger gesture through [points] (view px): DOWN, a MOVE per point, then UP. */
    private fun gesture(view: DrawingSurfaceView, points: List<Pair<Float, Float>>) {
        val downTime = SystemClock.uptimeMillis()
        val (x0, y0) = points.first()
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, x0, y0)
        for (i in 1 until points.size) {
            val (x, y) = points[i]
            send(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
        }
        val (xn, yn) = points.last()
        send(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xn, yn)
    }

    private fun send(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(
            downTime, eventTime, action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, 0x00001002 /* SOURCE_TOUCHSCREEN */, 0,
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun strokesOf(view: DrawingSurfaceView): List<Stroke> =
        view.toDocument().pages.flatMap { p -> p.layers.flatMap { it.elements } }.filterIsInstance<Stroke>()

    private fun strokesOfPage(view: DrawingSurfaceView, page: Int): List<Stroke> =
        view.toDocument().pages[page].layers.flatMap { it.elements }.filterIsInstance<Stroke>()

    private val inside = stroke(30.0 to 30.0, 60.0 to 60.0)     // near the page origin
    private val outside = stroke(30.0 to 300.0, 60.0 to 330.0)  // far down the page

    private fun stroke(vararg pts: Pair<Double, Double>) =
        Stroke(Tool.PEN, 0xFF000000.toInt(), "round", pts.map { StrokePoint(it.first, it.second, 0.0) }, true)

    private fun strokes(vararg els: Stroke): Document =
        Document(pages = listOf(page(Layer(els.toList()))))

    private fun twoPages(): Document =
        Document(pages = listOf(page(Layer(listOf(inside))), page(Layer(emptyList()))))

    private fun page(vararg layers: Layer) =
        Page(A4_WIDTH_PT, A4_HEIGHT_PT, Background.Solid(0xFFFFFFFF.toInt(), "graph"), layers.toList())

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val VIEW_W = 1080
        const val VIEW_H = 1920
    }
}
