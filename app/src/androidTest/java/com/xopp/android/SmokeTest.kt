package com.xopp.android

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xopp.android.format.Xopp
import com.xopp.android.format.model.Background
import com.xopp.android.format.model.Document
import com.xopp.android.format.model.Layer
import com.xopp.android.format.model.Page
import com.xopp.android.format.model.Stroke
import com.xopp.android.format.model.StrokePoint
import com.xopp.android.format.model.Tool
import com.xopp.android.render.DrawingSurfaceView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * On-device smoke test of the real runtime path: the actual Android gzip/XML round-trip and the
 * live drawing view. This is the mechanical check that the runtime path works end-to-end, run on
 * the emulator via the `connectedDebugAndroidTest` gradle task (see `docs/tools.md`). It differs
 * from the JVM host unit tests because it exercises Android's own `java.util.zip` and `View`.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    /**
     * Save a hand-built document to `.xopp` bytes and read it straight back on-device, asserting
     * the page/layer structure and the single stroke survive the gzip + XML round-trip.
     */
    @Test
    fun roundTripsAxoppDocumentOnDevice() {
        val stroke = Stroke(
            tool = Tool.PEN,
            color = 0xFF000000.toInt(),
            capStyle = "round",
            points = listOf(
                StrokePoint(10.0, 20.0, 1.5),
                StrokePoint(30.0, 40.0, 1.5),
            ),
            uniformWidth = false,
        )
        val doc = Document(
            pages = listOf(
                Page(
                    width = A4_WIDTH_PT,
                    height = A4_HEIGHT_PT,
                    background = Background.Solid(0xFFFFFFFF.toInt(), "graph"),
                    layers = listOf(Layer(listOf(stroke))),
                ),
            ),
        )

        val reopened = saveAndReopen(doc)

        assertEquals("page count", 1, reopened.pages.size)
        assertEquals("layer count", 1, reopened.pages[0].layers.size)
        val strokes = strokesOf(reopened)
        assertEquals("stroke count", 1, strokes.size)
        val got = strokes[0]
        assertEquals("tool", Tool.PEN, got.tool)
        assertEquals("color", 0xFF000000.toInt(), got.color)
        assertEquals("point count", 2, got.points.size)
        assertEquals("first x", 10.0, got.points[0].x, 1e-6)
        assertEquals("first y", 20.0, got.points[0].y, 1e-6)
    }

    /**
     * Drive the live [DrawingSurfaceView]: lay it out so pages get a width, dispatch a synthetic
     * one-finger stroke, and assert the document gained a stroke that then survives a save/reopen.
     */
    @Test
    fun drawingOnTheViewAddsAStrokeThatRoundTrips() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        var before = -1
        var after = -1
        var reopened: Document? = null

        instrumentation.runOnMainSync {
            val view = DrawingSurfaceView(context)

            // Give the view a real width FIRST so that load()'s relayout gives pages a non-zero
            // PageStacker box — strokes only commit when the page under the finger has a width.
            view.measure(
                View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, VIEW_W, VIEW_H)
            view.load(blankDocument())

            before = strokesOf(view.toDocument()).size
            dispatchStroke(view)
            after = strokesOf(view.toDocument()).size

            reopened = saveAndReopen(view.toDocument())
        }

        instrumentation.waitForIdleSync()

        assertEquals("view should gain exactly one stroke", before + 1, after)
        val strokes = strokesOf(reopened!!)
        assertTrue("the drawn stroke should survive save/reopen", strokes.isNotEmpty())
        assertTrue("stroke should have multiple sampled points", strokes.last().points.size >= 2)
    }

    /** Send DOWN -> several MOVE -> UP well inside page 0, recycling every event. */
    private fun dispatchStroke(view: DrawingSurfaceView) {
        val downTime = SystemClock.uptimeMillis()
        var x = 200f
        val y = 300f
        send(view, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        repeat(5) {
            x += 40f
            send(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
        }
        send(view, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    private fun send(view: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun saveAndReopen(doc: Document): Document {
        val out = ByteArrayOutputStream()
        Xopp.save(doc, out)
        return ByteArrayInputStream(out.toByteArray()).use { Xopp.open(it) }
    }

    private fun strokesOf(doc: Document): List<Stroke> =
        doc.pages.flatMap { page -> page.layers.flatMap { it.elements } }.filterIsInstance<Stroke>()

    private fun blankDocument(): Document =
        Document(
            pages = listOf(
                Page(
                    width = A4_WIDTH_PT,
                    height = A4_HEIGHT_PT,
                    background = Background.Solid(0xFFFFFFFF.toInt(), "graph"),
                    layers = listOf(Layer(emptyList())),
                ),
            ),
        )

    private companion object {
        const val A4_WIDTH_PT = 595.276
        const val A4_HEIGHT_PT = 841.89
        const val VIEW_W = 1080
        const val VIEW_H = 1920
    }
}
